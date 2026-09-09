package com.fortress.vault.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fortress.vault.FortressApplication
import com.fortress.vault.MainActivity
import com.fortress.vault.R
import com.fortress.vault.core.VaultManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
                    delay(CHECK_INTERVAL_MS)
                }
            }
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, openAppIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val seals = VaultManager.activeSeals(applicationContext)
        val text = when (seals.size) {
            0 -> "No seals active"
            1 -> "${VaultManager.remainingTimeLabel(applicationContext)} remaining"
            else -> "${seals.size} seals active · next unlock in ${VaultManager.remainingTimeLabel(applicationContext)}"
        }

        return NotificationCompat.Builder(this, FortressApplication.SENTINEL_CHANNEL_ID)
            .setContentTitle("Fortress Active")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        loopJob?.cancel()
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHECK_INTERVAL_MS = 15 * 60 * 1_000L
    }
}
