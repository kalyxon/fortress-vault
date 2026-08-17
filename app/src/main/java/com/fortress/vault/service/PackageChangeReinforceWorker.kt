package com.fortress.vault.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fortress.vault.core.PackageFreezer
import com.fortress.vault.core.VaultManager

class PackageChangeReinforceWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!VaultManager.isSealed(applicationContext)) {
            return Result.success()
        }

        val blockedPackages = VaultManager.blockedPackages(applicationContext)
        if (blockedPackages.isEmpty()) {
            return Result.success()
        }

        PackageFreezer.freezeAll(applicationContext, blockedPackages)
        VaultManager.verifyAndEnforce(applicationContext)
        return Result.success()
    }
}
