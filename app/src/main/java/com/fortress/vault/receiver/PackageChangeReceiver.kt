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
import com.fortress.vault.service.PackageChangeReinforceWorker

class PackageChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return
        if (!VaultManager.isSealed(context)) return

        val blockedPackages = VaultManager.blockedPackages(context)
        if (packageName !in blockedPackages) return

        Log.i("PackageChangeReceiver", "Package event ${intent.action} for $packageName while sealed; reapplying freeze")
        PackageFreezer.freezeAll(context, setOf(packageName))
        SentinelController.start(context)

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
        private const val REINFORCE_WORK_NAME = "fortress_package_change_reinforce"
    }
}
