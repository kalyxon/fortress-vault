package com.fortress.vault.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fortress.vault.core.VaultManager

/**
 * Runs at minimum every ~15 minutes regardless of whether SentinelService is
 * alive. Its main job: if the vault is sealed but the foreground service got
 * killed, restart it. It also independently re-runs the enforcement check so
 * there's never more than one WorkManager interval of unguarded time.
 */
class SentinelWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!VaultManager.isSealed(applicationContext)) {
            return Result.success()
        }

        VaultManager.verifyAndEnforce(applicationContext)

        val serviceIntent = Intent(applicationContext, SentinelService::class.java)
        ContextCompat.startForegroundService(applicationContext, serviceIntent)

        return Result.success()
    }
}
