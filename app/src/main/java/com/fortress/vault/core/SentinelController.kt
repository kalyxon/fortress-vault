package com.fortress.vault.core

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.fortress.vault.service.SentinelService
import com.fortress.vault.service.SentinelWorker
import java.util.concurrent.TimeUnit

/**
 * Two overlapping enforcement mechanisms, on purpose:
 *  1. SentinelService — a foreground service that keeps a persistent
 *     notification and runs a tight in-process check loop while alive.
 *  2. SentinelWorker — a WorkManager periodic job that acts as the
 *     dead-man's-switch: if the OS kills the foreground service to reclaim
 *     memory, this worker fires (minimum interval Android allows is 15 min)
 *     and both re-enforces the freeze AND restarts the service.
 */
object SentinelController {

    private const val WORK_NAME = "fortress_sentinel_periodic_work"

    fun start(context: Context) {
        val serviceIntent = Intent(context, SentinelService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)

        val request = PeriodicWorkRequestBuilder<SentinelWorker>(15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, SentinelService::class.java))
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
