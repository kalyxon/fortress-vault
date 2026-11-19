package com.fortress.vault.core

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import com.fortress.vault.FortressAdminReceiver
import org.json.JSONObject
import java.util.Calendar

data class AppSchedule(
    val packageName: String,
    val startMinute: Int,
    val endMinute: Int,
    val enabled: Boolean = true
)

object AppScheduleManager {
    private const val PREFS_NAME = "fortress_app_schedules"
    private const val KEY_STATE = "state"
    private val lock = Any()
    @Volatile private var cachedSchedules: Map<String, AppSchedule>? = null

    fun isEnabled(context: Context): Boolean =
        schedules(context).any { it.enabled }

    fun schedules(context: Context): List<AppSchedule> =
        readState(context).values.sortedBy { it.packageName }

    fun setEnabled(context: Context, enabled: Boolean) {
        check(!WebProtectionLockManager.isLocked(context, WebProtectionSetting.APP_SCHEDULE)) {
            "Unlock App Time Scheduler with its recovery phrase before changing it."
        }
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        check(dpm.isDeviceOwnerApp(context.packageName)) {
            "Set Fortress Vault as the device owner before using app schedules."
        }
        val previous = readState(context)
        check(!enabled || previous.isNotEmpty()) {
            "Add at least one app schedule before enabling it."
        }
        val updated = previous.mapValues { (_, schedule) -> schedule.copy(enabled = enabled) }
        save(context, updated)
        if (enabled) {
            check(SentinelController.start(context)) {
                "Fortress could not start schedule monitoring."
            }
        }
        enforce(context)
        if (!enabled) stopSentinelIfIdle(context)
    }

    fun setSchedule(context: Context, schedule: AppSchedule) {
        check(!WebProtectionLockManager.isLocked(context, WebProtectionSetting.APP_SCHEDULE)) {
            "Unlock App Time Scheduler with its recovery phrase before editing schedules."
        }
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        check(dpm.isDeviceOwnerApp(context.packageName)) {
            "Set Fortress Vault as the device owner before editing app schedules."
        }
        require(schedule.startMinute in 0..1439)
        require(schedule.endMinute in 0..1439)
        require(schedule.startMinute != schedule.endMinute)
        require(schedule.packageName != context.packageName)

        val previous = readState(context)
        val updated = previous.toMutableMap()
        updated[schedule.packageName] = schedule
        save(context, updated)

        if (isEnabled(context)) {
            check(SentinelController.start(context)) {
                "Fortress could not start schedule monitoring."
            }
        }
        enforce(context)
    }

    fun removeSchedule(context: Context, packageName: String) {
        check(!WebProtectionLockManager.isLocked(context, WebProtectionSetting.APP_SCHEDULE)) {
            "Unlock App Time Scheduler with its recovery phrase before editing schedules."
        }
        val previous = readState(context)
        val updated = previous.toMutableMap().also { it.remove(packageName) }
        save(context, updated)
        setSuspended(context, packageName, false)
        if (updated.values.none { it.enabled }) stopSentinelIfIdle(context)
    }

    fun enforce(context: Context) {
        val schedules = readState(context)
        if (schedules.values.none { it.enabled }) return
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        schedules.values.filter { it.enabled }.forEach { schedule ->
            val allowed = minuteIsInsideWindow(
                currentMinuteOfDay(),
                schedule.startMinute,
                schedule.endMinute
            )
            setSuspended(context, schedule.packageName, !allowed)
        }
    }

    fun isAllowedNow(context: Context, packageName: String): Boolean {
        val schedule = readState(context)[packageName] ?: return true
        if (!schedule.enabled) return true
        return minuteIsInsideWindow(
            currentMinuteOfDay(),
            schedule.startMinute,
            schedule.endMinute
        )
    }

    private fun currentMinuteOfDay(): Int {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    }

    private fun minuteIsInsideWindow(now: Int, start: Int, end: Int): Boolean =
        if (start < end) now in start until end
        else now >= start || now < end

    private fun setSuspended(context: Context, packageName: String, suspended: Boolean) {
        if (packageName == context.packageName) return
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = FortressAdminReceiver.getComponentName(context)
        try {
            val installed = context.packageManager.getApplicationInfo(packageName, 0)
            if (installed.enabled) {
                dpm.setPackagesSuspended(admin, arrayOf(packageName), suspended)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return
        } catch (_: Exception) {
            return
        }
    }

    private fun readState(context: Context): Map<String, AppSchedule> {
        cachedSchedules?.let { return it }
        return synchronized(lock) {
            cachedSchedules?.let { return it }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_STATE, null)
            val result = mutableMapOf<String, AppSchedule>()
            if (!json.isNullOrBlank()) {
                val root = JSONObject(json)
                val schedules = root.optJSONArray("schedules") ?: org.json.JSONArray()
                for (index in 0 until schedules.length()) {
                    val item = schedules.getJSONObject(index)
                    val schedule = AppSchedule(
                        item.getString("packageName"),
                        item.getInt("startMinute"),
                        item.getInt("endMinute"),
                        item.optBoolean("enabled", true)
                    )
                    result[schedule.packageName] = schedule
                }
            }
            result.also { cachedSchedules = it }
        }
    }

    private fun save(context: Context, schedules: Map<String, AppSchedule>) {
        val array = org.json.JSONArray()
        schedules.values.forEach { schedule ->
            array.put(JSONObject().apply {
                put("packageName", schedule.packageName)
                put("startMinute", schedule.startMinute)
                put("endMinute", schedule.endMinute)
                put("enabled", schedule.enabled)
            })
        }
        val json = JSONObject().put("schedules", array).toString()
        check(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_STATE, json).commit()) {
            "Could not save app schedules."
        }
        synchronized(lock) { cachedSchedules = schedules }
    }

    private fun stopSentinelIfIdle(context: Context) {
        if (!VaultManager.isSealed(context) &&
            ControlManager.activeLocks(context).isEmpty() &&
            WebProtectionLockManager.activeLocks(context).isEmpty() &&
            !AppTimeLimitManager.hasActiveWork(context) &&
            !isEnabled(context)
        ) {
            SentinelController.stop(context)
        }
    }

}
