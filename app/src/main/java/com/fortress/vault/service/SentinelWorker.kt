package com.fortress.vault.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fortress.vault.core.VaultManager

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
