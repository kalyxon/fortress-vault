package com.fortress.vault.core

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.fortress.vault.service.SentinelService
import com.fortress.vault.service.SentinelWorker
import java.util.concurrent.TimeUnit

object SentinelController {

    private const val WORK_NAME = "fortress_sentinel_periodic_work"

    fun start(context: Context): Boolean {
        val serviceIntent = Intent(context, SentinelService::class.java)
        val serviceStarted = try {
            ContextCompat.startForegroundService(context, serviceIntent)
            true
        } catch (error: Exception) {
            Log.e("SentinelController", "Could not start the enforcement service", error)
            false
        }

        try {
            val request = PeriodicWorkRequestBuilder<SentinelWorker>(15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        } catch (error: Exception) {
            Log.e("SentinelController", "Could not schedule periodic enforcement", error)
        }
        return serviceStarted
    }

    fun stop(context: Context) {
        runCatching {
            context.stopService(Intent(context, SentinelService::class.java))
        }
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
