package com.fortress.vault.core

import android.app.admin.DevicePolicyManager
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import com.fortress.vault.FortressAdminReceiver
import java.util.concurrent.TimeUnit

object PackageFreezer {

    private const val TAG = "PackageFreezer"
    private val freezeLock = Any()

    fun freezeAll(context: Context, packages: Set<String>, retryOnFailure: Boolean = true): Boolean {
        synchronized(freezeLock) {
            val userContexts = allUserContexts(context)
            return packages.all { freezeOne(context, it, retryOnFailure, userContexts) }
        }
    }

    fun freezePackage(context: Context, packageName: String): Boolean = synchronized(freezeLock) {
        freezeOne(context, packageName, retryOnFailure = false)
    }

    fun quarantineNewPackage(context: Context, packageName: String): Boolean = synchronized(freezeLock) {
        freezeOne(
            context,
            packageName,
            retryOnFailure = false,
            userContexts = allUserContexts(context),
            protectUninstall = false,
            hidePackage = false
        )
    }

    fun uninstallNewPackage(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName) return false
        val callback = Intent(context, com.fortress.vault.receiver.PackageChangeReceiver::class.java).apply {
            action = com.fortress.vault.receiver.PackageChangeReceiver.ACTION_UNINSTALL_RESULT
            putExtra(com.fortress.vault.receiver.PackageChangeReceiver.EXTRA_PACKAGE_NAME, packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            packageName.hashCode(),
            callback,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return runCatching {
            context.packageManager.packageInstaller.uninstall(packageName, pendingIntent.intentSender)
            true
        }.onFailure { error ->
            Log.e(TAG, "Could not uninstall newly installed package $packageName", error)
        }.getOrDefault(false)
    }

    fun unfreezeAll(context: Context, packages: Set<String>) {
        packages.forEach { unfreezeOne(context, it) }
    }

    private fun freezeOne(context: Context, packageName: String, retryOnFailure: Boolean): Boolean {
        return freezeOne(context, packageName, retryOnFailure, allUserContexts(context))
    }

    private fun freezeOne(
        context: Context,
        packageName: String,
        retryOnFailure: Boolean,
        userContexts: List<Context>,
        protectUninstall: Boolean = true,
        hidePackage: Boolean = true
    ): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = FortressAdminReceiver.getComponentName(context)

        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w(TAG, "Not device owner — cannot freeze $packageName. See setup instructions.")
            return false
        }

        Log.i(TAG, "Freezing $packageName across ${userContexts.size} user profile(s)")

        // Apply to every user so guest / secondary accounts are also blocked.
        var enforcedForEveryUser = true
        for (userContext in userContexts) {
            val uId = userContext.userId()
            val userDpm = if (uId == context.userId()) dpm else {
                userContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            }
            try {
                // Reinstall the package for this profile first when Android has
                // not yet exposed it through the profile package manager.
                runCatching {
                    val m = DevicePolicyManager::class.java.getMethod("installExistingPackage", android.content.ComponentName::class.java, String::class.java)
                    m.invoke(userDpm, admin, packageName) as? Boolean
                }.onFailure {
                    runCatching { dpm.installExistingPackage(admin, packageName) }
                }

                if (!isInstalledForUser(userContext, packageName)) {
                    enforcedForEveryUser = false
                    if (retryOnFailure) scheduleFreezeRetry(context, packageName)
                    continue
                }

                if (protectUninstall) {
                    userDpm.setUninstallBlocked(admin, packageName, true)
                }
                val uninstallBlocked = !protectUninstall || isUninstallBlocked(userDpm, admin, packageName)
                val failed = userDpm.setPackagesSuspended(admin, arrayOf(packageName), true)
                val suspended = runCatching {
                    userContext.packageManager.isPackageSuspended(packageName)
                }.getOrDefault(false)
                val hiddenSuccess = if (hidePackage) {
                    runCatching {
                        userDpm.setApplicationHidden(admin, packageName, true)
                    }.getOrDefault(false)
                } else {
                    true
                }
                runCatching {
                    val activityManager = userContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    ActivityManager::class.java.getMethod("forceStopPackage", String::class.java)
                        .invoke(activityManager, packageName)
                }.onFailure { error ->
                    Log.w(TAG, "Could not stop already-running $packageName for user $uId: ${error.message}")
                }
                val enforced = uninstallBlocked && failed.isEmpty() && suspended
                Log.d(TAG, "Freeze $packageName for user $uId: uninstallBlocked=$uninstallBlocked, hidden=$hiddenSuccess, suspended=$suspended, suspendFailed=${failed.joinToString()}")
                if (!enforced) {
                    enforcedForEveryUser = false
                }
                if (!enforced && retryOnFailure) {
                    scheduleFreezeRetry(context, packageName)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to freeze $packageName for user $uId", e)
                enforcedForEveryUser = false
                if (retryOnFailure) scheduleFreezeRetry(context, packageName)
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected error freezing $packageName for user $uId: ${e.message}")
                enforcedForEveryUser = false
            }
        }

        // Revoke runtime permissions on the primary user context (device owner).
        revokeAllRuntimePermissions(context, dpm, admin, packageName)
        return enforcedForEveryUser
    }

    private fun scheduleFreezeRetry(context: Context, packageName: String) {
        try {
            val work = androidx.work.OneTimeWorkRequestBuilder<com.fortress.vault.service.PackageChangeReinforceWorker>()
                .setInitialDelay(3, TimeUnit.SECONDS)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.LINEAR,
                    10,
                    TimeUnit.SECONDS
                )
                .setInputData(
                    androidx.work.workDataOf(
                        com.fortress.vault.service.PackageChangeReinforceWorker.KEY_PACKAGE to packageName
                    )
                )
                .build()
            androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                "fortress_freeze_retry",
                androidx.work.ExistingWorkPolicy.REPLACE,
                work
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule freeze retry for $packageName: ${e.message}")
        }
    }

    private fun unfreezeOne(context: Context, packageName: String) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = FortressAdminReceiver.getComponentName(context)

        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        // Unfreeze for every user.
        for (userContext in allUserContexts(context)) {
            val userDpm = userContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            try {
                userDpm.setUninstallBlocked(admin, packageName, false)
                userDpm.setApplicationHidden(admin, packageName, false)
                userDpm.setPackagesSuspended(admin, arrayOf(packageName), false)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unfreeze $packageName for user ${userContext.userId()}", e)
            }
        }
    }

    private fun revokeAllRuntimePermissions(
        context: Context,
        dpm: DevicePolicyManager,
        admin: android.content.ComponentName,
        packageName: String
    ) {
        try {
            val packageInfo = context.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_PERMISSIONS
            )
            val permissions = packageInfo.requestedPermissions ?: return
            for (permission in permissions) {
                try {
                    dpm.setPermissionGrantState(
                        admin,
                        packageName,
                        permission,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
                    )
                } catch (e: Exception) {
                }
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Package $packageName not found (may not be installed yet).")
        }
    }

    private fun isInstalledForUser(context: Context, packageName: String): Boolean =
        runCatching {
            context.packageManager.getApplicationInfo(packageName, 0)
            true
        }.getOrDefault(false)

    private fun isUninstallBlocked(
        dpm: DevicePolicyManager,
        admin: android.content.ComponentName,
        packageName: String
    ): Boolean = runCatching {
        val method = DevicePolicyManager::class.java.getMethod(
            "isUninstallBlocked",
            android.content.ComponentName::class.java,
            String::class.java
        )
        method.invoke(dpm, admin, packageName) as Boolean
    }.getOrDefault(false)

    fun onPackageReinstalled(context: Context, packageName: String) {
        if (!VaultManager.isSealed(context)) return

        val blocked = VaultManager.blockedPackages(context)
        if (blocked.isEmpty()) return

        Log.i(TAG, "Package $packageName reinstalled/updated while sealed; reapplying freeze to ${blocked.size} blocked package(s)")
        freezeAll(context, blocked)
    }

    // ── User enumeration ────────────────────────────────────────────────────

    /**
     * Returns a Context scoped to every currently active user on the device.
     */
    @Suppress("DEPRECATION")
    private fun allUserContexts(context: Context): List<Context> {
        val results = mutableListOf<Context>()
        val addedUserIds = mutableSetOf<Int>()

        fun addContextForUser(user: UserHandle) {
            runCatching {
                val userIdMethod = UserHandle::class.java.getMethod("getIdentifier")
                val uId = userIdMethod.invoke(user) as Int
                if (!addedUserIds.contains(uId)) {
                    val ctx: Context? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        val m = Context::class.java.getMethod(
                            "createContextAsUser",
                            UserHandle::class.java,
                            Int::class.java
                        )
                        m.invoke(context, user, 0) as? Context
                    } else {
                        val m = Context::class.java.getMethod(
                            "createPackageContextAsUser",
                            String::class.java,
                            Int::class.java,
                            UserHandle::class.java
                        )
                        m.invoke(context, context.packageName, 0, user) as? Context
                    }
                    if (ctx != null) {
                        results.add(ctx)
                        addedUserIds.add(uId)
                    }
                }
            }.onFailure { e ->
                Log.w(TAG, "Could not create context for user $user: ${e.message}")
            }
        }

        try {
            val um = context.getSystemService(Context.USER_SERVICE) as UserManager

            // 1. Try userProfiles (API 21+)
            runCatching {
                um.userProfiles.forEach { addContextForUser(it) }
            }

            // 2. Try getUsers() via reflection (Device Owner MANAGE_USERS permission)
            runCatching {
                val method = UserManager::class.java.getMethod(
                    "getUsers",
                    Boolean::class.javaPrimitiveType
                )
                @Suppress("UNCHECKED_CAST")
                val usersList = method.invoke(um, true) as? List<*>
                usersList?.forEach { userObj ->
                    if (userObj is UserHandle) {
                        addContextForUser(userObj)
                    } else if (userObj != null) {
                        val userHandle = runCatching {
                            userObj.javaClass.getMethod("getUserHandle")
                                .invoke(userObj) as? UserHandle
                        }.getOrNull() ?: runCatching {
                            val idField = userObj.javaClass.getField("id")
                            UserHandle::class.java.getMethod(
                                "of",
                                Int::class.javaPrimitiveType
                            ).invoke(null, idField.getInt(userObj)) as? UserHandle
                        }.getOrNull()
                        if (userHandle != null) addContextForUser(userHandle)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enumerate users: ${e.message}")
        }

        // Always ensure at least the calling-user context is present.
        if (results.isEmpty()) results.add(context)
        return results
    }

    private fun Context.userId(): Int = runCatching {
        val method = Context::class.java.getMethod("getUserId")
        method.invoke(this) as Int
    }.getOrDefault(-1)
}
