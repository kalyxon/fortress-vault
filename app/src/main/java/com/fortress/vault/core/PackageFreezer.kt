package com.fortress.vault.core

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
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

        try {
            dpm.setApplicationHidden(admin, packageName, true)
            val failedPackages = dpm.setPackagesSuspended(admin, arrayOf(packageName), true)
            if (failedPackages.isNotEmpty()) {
                Log.w(TAG, "Could not suspend: ${failedPackages.joinToString()}")
                scheduleFreezeRetry(context, packageName)
            }
            revokeAllRuntimePermissions(context, dpm, admin, packageName)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to freeze $packageName", e)
            scheduleFreezeRetry(context, packageName)
        }
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

        try {
            dpm.setApplicationHidden(admin, packageName, false)
            dpm.setPackagesSuspended(admin, arrayOf(packageName), false)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to unfreeze $packageName", e)
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
}
