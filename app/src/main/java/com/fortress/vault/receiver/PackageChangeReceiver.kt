package com.fortress.vault.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.fortress.vault.core.PackageFreezer
import com.fortress.vault.core.SentinelController
import com.fortress.vault.core.VaultManager
import com.fortress.vault.core.ControlManager
import com.fortress.vault.core.DeviceControl
import com.fortress.vault.service.PackageChangeReinforceWorker

class PackageChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_UNINSTALL_RESULT) return
        val packageName = intent.data?.schemeSpecificPart ?: return
        val isNewInstall = intent.action == Intent.ACTION_PACKAGE_ADDED &&
            !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        val blocksNewApps = ControlManager.activeLocks(context).any {
            it.control == DeviceControl.APP_INSTALLS && ControlManager.remainingMillis(context, it) > 0
        }
        val isSealedPackage = packageName in VaultManager.blockedPackages(context)
        if (!isSealedPackage && !(blocksNewApps && isNewInstall)) return

        if (isNewInstall && blocksNewApps) {
            Log.i("PackageChangeReceiver", "New app $packageName installed while app changes are blocked; suspending it")
        } else {
            Log.i("PackageChangeReceiver", "Package event ${intent.action} for sealed app $packageName; reapplying freeze")
        }

        val enforced = if (isNewInstall && blocksNewApps) {
            PackageFreezer.quarantineNewPackage(context, packageName)
        } else {
            PackageFreezer.freezeAll(context, setOf(packageName))
        }
        if (isNewInstall && blocksNewApps) {
            if (enforced) {
                ControlManager.rememberNewlyBlockedPackage(context, packageName)
            }
            PackageFreezer.uninstallNewPackage(context, packageName)
        }
        if (VaultManager.isSealed(context) || blocksNewApps) {
            SentinelController.start(context)
        }

        enqueueReinforcement(context, packageName)
    }

    private fun enqueueReinforcement(context: Context, packageName: String) {
        val workRequest = OneTimeWorkRequestBuilder<PackageChangeReinforceWorker>()
            .setInitialDelay(3, java.util.concurrent.TimeUnit.SECONDS)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.LINEAR,
                10,
                java.util.concurrent.TimeUnit.SECONDS
            )
            .setInputData(Data.Builder().putString(PackageChangeReinforceWorker.KEY_PACKAGE, packageName).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$REINFORCE_WORK_NAME:$packageName",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    companion object {
        const val ACTION_UNINSTALL_RESULT = "com.fortress.vault.ACTION_UNINSTALL_RESULT"
        const val EXTRA_PACKAGE_NAME = "package_name"
        private const val REINFORCE_WORK_NAME = "fortress_package_change_reinforce"
    }
}
