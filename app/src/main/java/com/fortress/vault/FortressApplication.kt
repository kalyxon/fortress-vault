package com.fortress.vault

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.Configuration
import androidx.work.WorkManager
import com.fortress.vault.core.PackageFreezer
import com.fortress.vault.core.PersistentVaultStore
import com.fortress.vault.core.VaultManager
import com.fortress.vault.core.SentinelController
import com.fortress.vault.core.ControlManager
import com.fortress.vault.service.PackageChangeReinforceWorker
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class FortressApplication : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setExecutor(Executors.newSingleThreadExecutor())
            .build()

    override fun onCreate() {
        super.onCreate()

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()).launch {
            VaultManager.init(this@FortressApplication)
            ControlManager.apply(this@FortressApplication)
            val persistedSeals = PersistentVaultStore.read(this@FortressApplication)
            val seals = persistedSeals ?: VaultManager.activeSeals(this@FortressApplication)
            VaultManager.enforceDeviceOwnerRestrictions(this@FortressApplication)
            if (seals.isNotEmpty() || ControlManager.activeLocks(this@FortressApplication).isNotEmpty()) {
                PackageFreezer.freezeAll(this@FortressApplication, seals.flatMap { it.packages }.toSet())
                runCatching { SentinelController.start(this@FortressApplication) }
            }
        }
        
        runCatching {
            WorkManager.initialize(this, workManagerConfiguration)
        }
        runCatching {
            WorkManager.getInstance(this).apply {
                cancelAllWorkByTag(PackageChangeReinforceWorker::class.java.name)
                pruneWork()
            }
        }

        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SENTINEL_CHANNEL_ID,
                "Fortress Sentinel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows the ongoing status of your sealed vault."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val SENTINEL_CHANNEL_ID = "fortress_sentinel_channel"
    }
}
