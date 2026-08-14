package com.fortress.vault.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object TimeKeeper {

    private const val TIME_SOURCE_URL = "https://www.google.com"
    private const val PREFS_NAME = "fortress_timekeeper_prefs"
    private const val KEY_LAST_SYNC_REAL_MILLIS = "last_sync_real_millis"
    private const val KEY_LAST_SYNC_ELAPSED_REALTIME = "last_sync_elapsed_realtime"

    suspend fun fetchTrustedTimeMillis(context: Context): Long = withContext(Dispatchers.IO) {
        try {
            val networkTime = fetchFromHttpHeader()
            persistSyncPoint(context, networkTime)
            networkTime
        } catch (e: Exception) {
            estimateCurrentTrustedTimeMillis(context)
        }
    }

    fun estimateCurrentTrustedTimeMillis(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastReal = prefs.getLong(KEY_LAST_SYNC_REAL_MILLIS, -1L)
        val lastElapsed = prefs.getLong(KEY_LAST_SYNC_ELAPSED_REALTIME, -1L)

        if (lastReal == -1L) {
            // Never synced even once (e.g. sealed with zero connectivity ever).
            // Only real fallback is the wall clock — mitigated by requiring a
            // successful sync before seal creation is allowed to complete.
            return System.currentTimeMillis()
        }

        val elapsedSinceSync = android.os.SystemClock.elapsedRealtime() - lastElapsed
        return lastReal + elapsedSinceSync
    }

    private fun fetchFromHttpHeader(): Long {
        val connection = URL(TIME_SOURCE_URL).openConnection() as HttpURLConnection
        connection.requestMethod = "HEAD"
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.connect()

        val dateHeader = connection.getHeaderField("Date")
            ?: throw IllegalStateException("No Date header in response")
        connection.disconnect()

        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        return format.parse(dateHeader)?.time
            ?: throw IllegalStateException("Could not parse Date header")
    }

    private fun persistSyncPoint(context: Context, networkTimeMillis: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_LAST_SYNC_REAL_MILLIS, networkTimeMillis)
            .putLong(KEY_LAST_SYNC_ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime())
            .apply()
    }
}
