package com.fortress.vault.core

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import com.fortress.vault.FortressAdminReceiver

object PackageFreezer {

    private const val TAG = "PackageFreezer"

    fun freezeAll(context: Context, packages: Set<String>) {
        packages.forEach { freezeOne(context, it) }
    }

    fun unfreezeAll(context: Context, packages: Set<String>) {
        packages.forEach { unfreezeOne(context, it) }
    }

    private fun freezeOne(context: Context, packageName: String) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = FortressAdminReceiver.getComponentName(context)

        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w(TAG, "Not device owner — cannot freeze $packageName. See setup instructions.")
            return
        }

        val userContexts = allUserContexts(context)
        Log.i(TAG, "Freezing $packageName across ${userContexts.size} user profile(s)")

        // Apply to every user so guest / secondary accounts are also blocked.
        for (userContext in userContexts) {
            val userDpm = userContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val uId = userContext.userId()
            try {
                // Ensure the package is installed in the target user space so freeze API works.
                runCatching {
                    val m = DevicePolicyManager::class.java.getMethod("installExistingPackage", android.content.ComponentName::class.java, String::class.java)
                    m.invoke(userDpm, admin, packageName)
                }.onFailure {
                    runCatching { dpm.installExistingPackage(admin, packageName) }
                }

                val hiddenSuccess = userDpm.setApplicationHidden(admin, packageName, true)
                val failed = userDpm.setPackagesSuspended(admin, arrayOf(packageName), true)
                Log.d(TAG, "Freeze $packageName for user $uId: hidden=$hiddenSuccess, suspendFailed=${failed.joinToString()}")
                if (failed.isNotEmpty()) {
                    scheduleFreezeRetry(context, packageName)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to freeze $packageName for user $uId", e)
                scheduleFreezeRetry(context, packageName)
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected error freezing $packageName for user $uId: ${e.message}")
            }
        }

        // Revoke runtime permissions on the primary user context (device owner).
        revokeAllRuntimePermissions(context, dpm, admin, packageName)
    }

    private fun scheduleFreezeRetry(context: Context, packageName: String) {
        try {
            val work = androidx.work.OneTimeWorkRequestBuilder<com.fortress.vault.service.PackageChangeReinforceWorker>()
                .setInitialDelay(2, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            androidx.work.WorkManager.getInstance(context).enqueue(work)
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
                val method = UserManager::class.java.getMethod("getUsers", Boolean::class.java)
                @Suppress("UNCHECKED_CAST")
                val usersList = method.invoke(um, true) as? List<*>
                usersList?.forEach { userObj ->
                    if (userObj is UserHandle) {
                        addContextForUser(userObj)
                    } else if (userObj != null) {
                        // UserInfo object on older APIs -> get UserHandle via UserInfo.getUserHandle()
                        runCatching {
                            val getUserHandleMethod = userObj.javaClass.getMethod("getUserHandle")
                            val userHandle = getUserHandleMethod.invoke(userObj) as? UserHandle
                            if (userHandle != null) addContextForUser(userHandle)
                        }
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
