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
import com.fortress.vault.core.ControlManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SentinelService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // Static notification — zero disk/IPC reads so startForeground() is instant
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val fgsType = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            startForeground(NOTIFICATION_ID, buildStaticNotification(), fgsType)
        } else {
            startForeground(NOTIFICATION_ID, buildStaticNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loopJob?.isActive != true) {
            loopJob = serviceScope.launch {
                var lastTrustedVerification = 0L

                // Run enforcement immediately on first start so we catch any packages
                // that slipped through while the service was not running.
                withContext(Dispatchers.IO) {
                    ControlManager.enforceNewlyInstalledPackagesIfNeeded(applicationContext)
                }

                while (true) {
                    // Fast in-memory check — reads cachedSeals / cachedLocks, no I/O
                    if (!VaultManager.isSealed(applicationContext) && ControlManager.activeLocks(applicationContext).isEmpty()) {
                        stopSelf()
                        break
                    }

                    // ── Recovery path (runs every loop) ───────────────────────────
                    // Catches any newly-installed apps that PackageChangeReceiver may
                    // have missed (e.g. some OEMs drop PACKAGE_ADDED on app stores).
                    // Runs on IO so it never touches the main thread.
                    withContext(Dispatchers.IO) {
                        ControlManager.enforceNewlyInstalledPackagesIfNeeded(applicationContext)
                    }

                    // ── Trusted verification (every 15 min) ───────────────────────
                    // Fetches network time, checks for clock rollback, re-applies DPM
                    // state. All heavy I/O + IPC happens on IO dispatcher.
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastTrustedVerification >= TRUSTED_VERIFICATION_INTERVAL_MS) {
                        withContext(Dispatchers.IO) {
                            VaultManager.verifyAndEnforce(applicationContext)
                            ControlManager.apply(applicationContext)
                        }
                        lastTrustedVerification = now

                        // Refresh notification text after enforcement cycle
                        val updatedNotif = withContext(Dispatchers.IO) { buildDynamicNotification() }
                        runCatching {
                            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, updatedNotif)
                        }
                    }

                    delay(CHECK_INTERVAL_MS)
                }
            }
        }
        return START_STICKY
    }

    /**
     * Static notification used at service creation — zero I/O, no crypto reads.
     * Updated asynchronously after the first enforcement cycle.
     */
    private fun buildStaticNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, openAppIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, FortressApplication.SENTINEL_CHANNEL_ID)
            .setContentTitle("Fortress Active")
            .setContentText("Vault sealed — tap to view")
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    /**
     * Dynamic notification built on a background thread after enforcement cycle.
     * Reads cachedSeals (no ESP re-read — cache is warm from enforcement).
     */
    private fun buildDynamicNotification(): Notification {
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
        private const val CHECK_INTERVAL_MS = 5_000L
        private const val TRUSTED_VERIFICATION_INTERVAL_MS = 15 * 60 * 1_000L
    }
}
