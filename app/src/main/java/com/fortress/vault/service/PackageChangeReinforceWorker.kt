package com.fortress.vault.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fortress.vault.core.PackageFreezer
import com.fortress.vault.core.VaultManager
import com.fortress.vault.core.ControlManager
import com.fortress.vault.core.DeviceControl

class PackageChangeReinforceWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val packageName = inputData.getString(KEY_PACKAGE) ?: return Result.success()
        val appChangesBlocked = ControlManager.activeLocks(applicationContext).any {
            it.control == DeviceControl.APP_INSTALLS && ControlManager.remainingMillis(applicationContext, it) > 0
        }
        if (!VaultManager.isSealed(applicationContext) && !appChangesBlocked) {
            return Result.success()
        }

        val blockedPackages = VaultManager.blockedPackages(applicationContext)
        if (!isPackageInstalled(packageName)) {
            return Result.success()
        }
        if (!appChangesBlocked && packageName !in blockedPackages) {
            return Result.success()
        }

        val enforced = if (appChangesBlocked && packageName !in blockedPackages) {
            PackageFreezer.quarantineNewPackage(applicationContext, packageName)
        } else {
            PackageFreezer.freezePackage(applicationContext, packageName)
        }
        if (appChangesBlocked && packageName !in blockedPackages) {
            if (enforced) {
                ControlManager.rememberNewlyBlockedPackage(applicationContext, packageName)
            }
            PackageFreezer.uninstallNewPackage(applicationContext, packageName)
        }
        return if (enforced || runAttemptCount >= MAX_RETRIES) {
            Result.success()
        } else {
            Result.retry()
        }
    }

    companion object {
        const val KEY_PACKAGE = "package_name"
        private const val MAX_RETRIES = 8
    }

    private fun isPackageInstalled(packageName: String): Boolean = runCatching {
        applicationContext.packageManager.getApplicationInfo(packageName, 0)
        true
    }.getOrDefault(false)
}
