package com.fortress.vault.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fortress.vault.core.VaultManager
import com.fortress.vault.core.ControlManager
import com.fortress.vault.core.WebProtectionLockManager
import com.fortress.vault.core.AppTimeLimitManager

class SentinelWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        WebProtectionLockManager.reconcileExpired(applicationContext)
        if (!VaultManager.isSealed(applicationContext) &&
            ControlManager.activeLocks(applicationContext).isEmpty() &&
            WebProtectionLockManager.activeLocks(applicationContext).isEmpty() &&
            !AppTimeLimitManager.hasActiveWork(applicationContext)
        ) {
            return Result.success()
        }

        val serviceIntent = Intent(applicationContext, SentinelService::class.java)
        ContextCompat.startForegroundService(applicationContext, serviceIntent)

        return Result.success()
    }
}
