package com.fortress.vault.core

import android.app.admin.DevicePolicyManager
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import com.fortress.vault.FortressAdminReceiver
import java.util.concurrent.TimeUnit

object PackageFreezer {

    private const val TAG = "PackageFreezer"
    private val freezeLock = Any()

    data class RecoverableApp(
        val packageName: String,
        val label: String,
        val hidden: Boolean,
        val suspended: Boolean
    )

    fun findRecoverableApps(context: Context): List<RecoverableApp> {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) return emptyList()

        val admin = FortressAdminReceiver.getComponentName(context)
        val protectedPackages = VaultManager.blockedPackages(context) +
            ControlManager.newlyBlockedPackages(context)
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or
            PackageManager.MATCH_UNINSTALLED_PACKAGES

        return context.packageManager.getInstalledApplications(flags)
            .asSequence()
            .filter { it.packageName != context.packageName && it.packageName !in protectedPackages }
            .mapNotNull { application ->
                val packageName = application.packageName
                if (WebProtectionManager.isPackageHiddenByBypassProtection(context, packageName)) {
                    return@mapNotNull null
                }
                val hidden = runCatching {
                    dpm.isApplicationHidden(admin, packageName)
                }.onFailure { error ->
                    Log.e(TAG, "Could not inspect hidden state for $packageName", error)
                }.getOrNull() ?: return@mapNotNull null
                val suspended = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching {
                        context.packageManager.isPackageSuspended(packageName)
                    }.onFailure { error ->
                        Log.e(TAG, "Could not inspect suspended state for $packageName", error)
                    }.getOrDefault(false)
                } else {
                    false
                }
                if (!hidden && !suspended) return@mapNotNull null
                val label = runCatching {
                    context.packageManager.getApplicationLabel(application).toString()
                }.getOrDefault(packageName)
                RecoverableApp(packageName, label, hidden, suspended)
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

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
            hidePackage = true
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

    fun unfreezeAll(
        context: Context,
        packages: Set<String>,
        preserveUninstallBlock: Boolean = ControlManager.isAppChangeBlocked(context)
    ): Boolean {
        var fullyUnfrozen = true
        packages.forEach { packageName ->
            if (!unfreezeOne(context, packageName, preserveUninstallBlock)) fullyUnfrozen = false
        }
        return fullyUnfrozen
    }

    fun restorePackageVisibility(
        context: Context,
        packageName: String,
        preserveUninstallBlock: Boolean = false
    ): Boolean {
        val admin = FortressAdminReceiver.getComponentName(context)
        var installedForEveryUser = true
        allUserContexts(context).forEach { userContext ->
            val userDpm = userContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val installed = isInstalledForUser(userContext, packageName)
            val hidden = runCatching {
                userDpm.isApplicationHidden(admin, packageName)
            }.onFailure { error ->
                Log.e(TAG, "Could not check hidden state for $packageName, user ${userContext.userId()}", error)
            }.getOrNull()
            if (installed || hidden == true) {
                enableSystemPackageIfNeeded(userContext, userDpm, admin, packageName)
                return@forEach
            }
            if (hidden == null) {
                installedForEveryUser = false
                Log.e(TAG, "Cannot safely determine whether $packageName is hidden for user ${userContext.userId()}")
                return@forEach
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                installedForEveryUser = false
                Log.e(TAG, "Cannot restore $packageName for user ${userContext.userId()}: install-existing is unsupported")
                return@forEach
            }
            val installResult = runCatching {
                val method = DevicePolicyManager::class.java.getMethod(
                    "installExistingPackage",
                    android.content.ComponentName::class.java,
                    String::class.java
                )
                method.invoke(userDpm, admin, packageName) as? Boolean
            }.onFailure { error ->
                Log.e(TAG, "Could not install existing $packageName for user ${userContext.userId()}", error)
            }.getOrNull()
            if (installResult != true && !isInstalledForUser(userContext, packageName)) {
                installedForEveryUser = false
                Log.e(TAG, "Android did not install existing $packageName for user ${userContext.userId()}")
            } else {
                enableSystemPackageIfNeeded(userContext, userDpm, admin, packageName)
            }
        }

        if (!installedForEveryUser ||
            !unfreezeOne(context, packageName, preserveUninstallBlock = preserveUninstallBlock)
        ) {
            return false
        }
        if (!isInstalledForUser(context, packageName)) {
            Log.e(TAG, "$packageName is still not installed for the current user after restoration")
            return false
        }
        val applicationInfo = runCatching {
            context.packageManager.getApplicationInfo(packageName, 0)
        }.onFailure { error ->
            Log.e(TAG, "Could not read restored $packageName application state", error)
        }.getOrNull() ?: return false
        if (!applicationInfo.enabled) {
            Log.e(TAG, "$packageName remains disabled for the current user after restoration")
            return false
        }
        if (context.packageManager.getLaunchIntentForPackage(packageName) == null) {
            Log.e(TAG, "$packageName has no launchable activity for the current user after restoration")
            return false
        }
        return true
    }

    private fun enableSystemPackageIfNeeded(
        context: Context,
        dpm: DevicePolicyManager,
        admin: android.content.ComponentName,
        packageName: String
    ) {
        runCatching {
            dpm.enableSystemApp(admin, packageName)
        }.onFailure { error ->
            Log.e(TAG, "Could not ask Android to enable system app $packageName for user ${context.userId()}", error)
        }
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
        if (packageName == context.packageName) {
            Log.w(TAG, "Cannot freeze self package $packageName")
            return true
        }

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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    runCatching {
                        val m = DevicePolicyManager::class.java.getMethod(
                            "installExistingPackage",
                            android.content.ComponentName::class.java,
                            String::class.java
                        )
                        m.invoke(userDpm, admin, packageName) as? Boolean
                    }.onFailure {
                        runCatching { dpm.installExistingPackage(admin, packageName) }
                    }
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
                val suspended = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching {
                        userContext.packageManager.isPackageSuspended(packageName)
                    }.getOrDefault(false)
                } else {
                    failed.isEmpty()
                }
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
                val enforced = uninstallBlocked && hiddenSuccess && failed.isEmpty() && suspended
                Log.d(TAG, "Freeze $packageName for user $uId: uninstallBlocked=$uninstallBlocked, hidden=$hiddenSuccess, suspended=$suspended, suspendFailed=${failed.joinToString()}")
                if (!enforced) {
                    enforcedForEveryUser = false
                }
                if (!enforced && retryOnFailure) {
                    scheduleFreezeRetry(context, packageName)
                }
                revokeRuntimePermissions(userContext, userDpm, admin, packageName)
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to freeze $packageName for user $uId", e)
                enforcedForEveryUser = false
                if (retryOnFailure) scheduleFreezeRetry(context, packageName)
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected error freezing $packageName for user $uId: ${e.message}")
                enforcedForEveryUser = false
            }
        }

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

    private fun unfreezeOne(
        context: Context,
        packageName: String,
        preserveUninstallBlock: Boolean = false
    ): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = FortressAdminReceiver.getComponentName(context)

        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            Log.e(TAG, "Not device owner — cannot unfreeze $packageName")
            return false
        }

        var fullyUnfrozen = true
        for (userContext in allUserContexts(context)) {
            val userDpm = userContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val installed = isInstalledForUser(userContext, packageName)
            val hidden = runCatching {
                userDpm.isApplicationHidden(admin, packageName)
            }.onFailure { error ->
                Log.e(TAG, "Could not check hidden state for $packageName, user ${userContext.userId()}", error)
            }.getOrNull()
            if (!installed && hidden == false) continue
            if (!installed && hidden == null) {
                fullyUnfrozen = false
                continue
            }

            runCatching {
                userDpm.setUninstallBlocked(admin, packageName, preserveUninstallBlock)
                check(isUninstallBlocked(userDpm, admin, packageName) == preserveUninstallBlock) {
                    "Android did not apply the requested uninstall protection state"
                }
            }.onFailure { error ->
                fullyUnfrozen = false
                Log.e(TAG, "Could not update uninstall protection for $packageName, user ${userContext.userId()}", error)
            }

            runCatching {
                userDpm.setApplicationHidden(admin, packageName, false)
                check(!userDpm.isApplicationHidden(admin, packageName)) {
                    "Android kept the package hidden"
                }
            }.onFailure { error ->
                fullyUnfrozen = false
                Log.e(TAG, "Could not unhide $packageName for user ${userContext.userId()}", error)
            }

            runCatching {
                val failedPackages = userDpm.setPackagesSuspended(admin, arrayOf(packageName), false)
                check(failedPackages.isEmpty()) {
                    "Android refused to unsuspend ${failedPackages.joinToString()}"
                }
                check(!userContext.packageManager.isPackageSuspended(packageName)) {
                    "Android kept the package suspended"
                }
            }.onFailure { error ->
                fullyUnfrozen = false
                Log.e(TAG, "Could not unsuspend $packageName for user ${userContext.userId()}", error)
            }

            restoreRuntimePermissions(userContext, userDpm, admin, packageName)
            if (!isInstalledForUser(userContext, packageName)) {
                fullyUnfrozen = false
                Log.e(TAG, "$packageName remains unavailable for user ${userContext.userId()} after unhide")
            }
        }
        return fullyUnfrozen
    }

    private fun revokeRuntimePermissions(
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
                    if (!isRuntimePermission(context, permission)) continue
                    dpm.setPermissionGrantState(
                        admin,
                        packageName,
                        permission,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
                    )
                } catch (error: Exception) {
                    Log.w(TAG, "Could not revoke $permission for $packageName", error)
                }
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Package $packageName not found (may not be installed yet).")
        }
    }

    private fun restoreRuntimePermissions(
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
            packageInfo.requestedPermissions.orEmpty().forEach { permission ->
                try {
                    if (!isRuntimePermission(context, permission)) return@forEach
                    dpm.setPermissionGrantState(
                        admin,
                        packageName,
                        permission,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
                    )
                } catch (error: Exception) {
                    Log.w(TAG, "Could not restore $permission for $packageName", error)
                }
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return
        }
    }

    private fun isRuntimePermission(context: Context, permission: String): Boolean =
        try {
            context.packageManager.getPermissionInfo(permission, 0)
                .protectionLevel and PermissionInfo.PROTECTION_DANGEROUS != 0
        } catch (_: PackageManager.NameNotFoundException) {
            false
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
        if (packageName !in blocked) return

        Log.i(TAG, "Package $packageName reinstalled/updated while sealed; reapplying freeze to package")
        freezeOne(context, packageName, retryOnFailure = true)
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
