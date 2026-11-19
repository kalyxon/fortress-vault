package com.fortress.vault.service

import android.content.pm.PackageManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fortress.vault.core.PackageFreezer
import com.fortress.vault.core.VaultManager

class TemporaryRefreezeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val packageName = inputData.getString(KEY_PACKAGE) ?: return Result.success()
        if (packageName !in VaultManager.blockedPackages(applicationContext)) return Result.success()
        try {
            applicationContext.packageManager.getApplicationInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return Result.success()
        }
        return if (PackageFreezer.freezeAll(applicationContext, setOf(packageName))) {
            Result.success()
        } else {
            Result.retry()
        }
    }

    companion object {
        const val KEY_PACKAGE = "package"
    }
}
