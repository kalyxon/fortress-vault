package com.fortress.vault.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
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
        if (blockedPackages.isEmpty()) return

        Log.i("PackageChangeReceiver", "Package event ${intent.action} for $packageName while sealed; reapplying freeze to ${blockedPackages.size} blocked app(s)")
        PackageFreezer.freezeAll(context, blockedPackages)
        SentinelController.start(context)

        val workRequest = OneTimeWorkRequestBuilder<PackageChangeReinforceWorker>()
            .build()
        WorkManager.getInstance(context).enqueue(workRequest)

        val retryDelays = listOf(300L, 800L, 1600L)
        retryDelays.forEach { delay ->
            Handler(Looper.getMainLooper()).postDelayed({
                PackageFreezer.freezeAll(context, blockedPackages)
            }, delay)
        }
    }
}
