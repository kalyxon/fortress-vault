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
        val packageName = inputData.getString(KEY_PACKAGE) ?: return Result.success()
        if (!VaultManager.isSealed(applicationContext)) {
            return Result.success()
        }

        val blockedPackages = VaultManager.blockedPackages(applicationContext)
        if (packageName !in blockedPackages) {
            return Result.success()
        }

        val enforced = PackageFreezer.freezePackage(applicationContext, packageName)
        return if (enforced || runAttemptCount >= MAX_RETRIES) {
            Result.success()
        } else {
            Result.retry()
        }
    }

    companion object {
        const val KEY_PACKAGE = "package_name"
        private const val MAX_RETRIES = 8
    }
}
