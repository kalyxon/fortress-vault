package com.fortress.vault.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fortress.vault.core.PackageFreezer
import com.fortress.vault.core.VaultManager

class TemporaryRefreezeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val packageName = inputData.getString(KEY_PACKAGE) ?: return Result.success()
        if (VaultManager.isSealed(applicationContext)) {
            PackageFreezer.freezeAll(applicationContext, setOf(packageName))
        }
        return Result.success()
    }

    companion object {
        const val KEY_PACKAGE = "package"
    }
}
