package com.fortress.vault.core

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fortress.vault.FortressApplication
import com.fortress.vault.MainActivity
import com.fortress.vault.R

object WelcomeBackNotifier {

    private const val NOTIFICATION_ID = 2001

    fun show(context: Context, reason: String) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && !hasPermission) {
            return
        }

        val openAppIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, FortressApplication.SENTINEL_CHANNEL_ID)
            .setContentTitle("Welcome back")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(welcomeBody(reason)))
            .setSmallIcon(R.drawable.ic_shield)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun welcomeBody(reason: String): String = when (reason) {
        "Sentence complete" -> "Your sentence is complete. Everything you sealed is unfrozen."
        else -> reason
    }
}
