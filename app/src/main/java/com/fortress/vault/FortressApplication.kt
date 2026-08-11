package com.fortress.vault

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.fortress.vault.core.VaultManager

class FortressApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // VaultManager is our single source of truth for sealed/unsealed state.
        // Everything else (freezer, sentinel, boot receiver, UI) reads from it.
        VaultManager.init(this)

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
