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
            }

            revokeAllRuntimePermissions(context, dpm, admin, packageName)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to freeze $packageName", e)
        }
    }

    private fun unfreezeOne(context: Context, packageName: String) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = FortressAdminReceiver.getComponentName(context)

        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        try {
            dpm.setApplicationHidden(admin, packageName, false)
            dpm.setPackagesSuspended(admin, arrayOf(packageName), false)
            // NOTE: we deliberately do NOT restore permissions automatically —
            // the user re-grants them the normal way on next launch. This is
            // a small extra bit of friction that's intentional, not a bug.
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
                    // Some permissions (e.g. signature-level) can't be set this way — expected, skip.
                }
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Package $packageName not found (may not be installed yet).")
        }
    }

    /** Called right after a freeze target gets reinstalled — re-applies the freeze instantly. */
    fun onPackageReinstalled(context: Context, packageName: String) {
        if (VaultManager.isSealed(context) && packageName in VaultManager.blockedPackages(context)) {
            freezeOne(context, packageName)
        }
    }
}
