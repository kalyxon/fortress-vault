package com.fortress.vault.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.fortress.vault.FortressApplication
import com.fortress.vault.MainActivity
import com.fortress.vault.R
import com.fortress.vault.core.VaultManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Persistent foreground service — the "always-on guard." Device Owner status
 * prevents the user from force-stopping this from Settings. If the OS itself
 * kills it under memory pressure, SentinelWorker (WorkManager) resurrects it.
 */
class SentinelService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loopJob?.isActive != true) {
            loopJob = serviceScope.launch {
                while (true) {
                    if (!VaultManager.isSealed(applicationContext)) {
                        stopSelf()
                        break
                    }
                    VaultManager.verifyAndEnforce(applicationContext)
                    updateNotification()
                    delay(TimeUnit.MINUTES.toMillis(15))
                }
            }
        }
        // START_STICKY: ask the OS to recreate us with a null intent if killed.
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, openAppIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, FortressApplication.SENTINEL_CHANNEL_ID)
            .setContentTitle("Fortress Active")
            .setContentText("${VaultManager.remainingTimeLabel(applicationContext)} remaining")
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        loopJob?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}
