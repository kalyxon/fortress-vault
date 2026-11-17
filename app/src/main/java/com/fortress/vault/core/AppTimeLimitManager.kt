package com.fortress.vault.core

import android.app.admin.DevicePolicyManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import com.fortress.vault.FortressAdminReceiver
import org.json.JSONObject
import java.util.Calendar

data class DailyAppLimit(val packageName: String, val limitMinutes: Int)

object AppTimeLimitManager {
    private const val TAG = "AppTimeLimit"
    private const val PREFS_NAME = "fortress_app_time_limits"
    private const val KEY_STATE = "state"
    private const val KEY_ENFORCEMENT_ERROR = "enforcement_error"
    private const val MIN_LIMIT_MINUTES = 1
    private const val MAX_LIMIT_MINUTES = 24 * 60
    private val lock = Any()
    @Volatile private var cachedState: State? = null
    @Volatile private var lastEnforcementWarningAt = 0L

    private data class State(
        val enabled: Boolean,
        val limits: Map<String, Int>,
        val suspendedPackages: Set<String>
    )

    fun isEnabled(context: Context): Boolean = readState(context).enabled

    fun hasActiveWork(context: Context): Boolean {
        val state = readState(context)
        return state.enabled || state.suspendedPackages.isNotEmpty()
    }

    fun limits(context: Context): List<DailyAppLimit> =
        readState(context).limits.map { (packageName, minutes) ->
            DailyAppLimit(packageName, minutes)
        }.sortedBy { it.packageName }

    @Suppress("DEPRECATION")
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    fun enforcementError(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ENFORCEMENT_ERROR, null)

    fun usageMillisToday(context: Context, packageNames: Set<String>): Map<String, Long> {
        if (!hasUsageAccess(context)) return emptyMap()
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val usage = context.getSystemService(UsageStatsManager::class.java)
            .queryAndAggregateUsageStats(start, System.currentTimeMillis())
        return packageNames.associateWith { packageName ->
            usage[packageName]?.totalTimeInForeground ?: 0L
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        check(!WebProtectionLockManager.isLocked(context, WebProtectionSetting.APP_TIME_LIMITS)) {
            "Unlock daily app time limits with its recovery phrase before changing them."
        }
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        check(dpm.isDeviceOwnerApp(context.packageName)) {
            "Set Fortress Vault as the device owner before enabling daily app time limits."
        }
        val previous = readState(context)
        if (enabled) {
            check(previous.limits.isNotEmpty()) { "Add at least one app limit before enabling this feature." }
            check(hasUsageAccess(context)) {
                "Grant Fortress Vault Usage access in Android Settings before enabling app time limits."
            }
        }
        save(context, previous.copy(enabled = enabled))
        if (enabled) {
            if (!SentinelController.start(context)) {
                save(context, previous)
                throw IllegalStateException(
                    "Fortress could not start app-limit monitoring. Keep Fortress open and try again."
                )
            }
        } else {
            enforce(context)
            check(readState(context).suspendedPackages.isEmpty()) {
                "Some limited apps could not be resumed yet. Fortress will keep retrying; check the error shown in Settings."
            }
            stopSentinelIfIdle(context)
        }
    }

    fun setLimit(context: Context, packageName: String, limitMinutes: Int?) {
        check(!WebProtectionLockManager.isLocked(context, WebProtectionSetting.APP_TIME_LIMITS)) {
            "Unlock daily app time limits with its recovery phrase before editing app limits."
        }
        require(packageName != context.packageName) { "Fortress Vault cannot be limited by its own app timer." }
        if (limitMinutes != null) {
            require(limitMinutes in MIN_LIMIT_MINUTES..MAX_LIMIT_MINUTES) {
                "Daily limits must be between 1 minute and 24 hours."
            }
            check(
                packageName !in VaultManager.blockedPackages(context) &&
                    packageName !in ControlManager.newlyBlockedPackages(context)
            ) {
                "An app already blocked by the vault cannot also have a daily time limit."
            }
            context.packageManager.getApplicationInfo(packageName, 0)
        }
        val previous = synchronized(lock) {
            val previous = readState(context)
            val updated = previous.limits.toMutableMap()
            if (limitMinutes == null) updated.remove(packageName)
            else updated[packageName] = limitMinutes
            save(
                context,
                previous.copy(
                    enabled = previous.enabled && updated.isNotEmpty(),
                    limits = updated
                )
            )
            previous
        }
        if (hasActiveWork(context) && !SentinelController.start(context)) {
            save(context, previous)
            throw IllegalStateException("Fortress could not start app-limit monitoring. Keep Fortress open and try again.")
        }
    }

    fun enforce(context: Context) {
        val state = readState(context)
        if (!state.enabled) {
            val failures = mutableListOf<String>()
            val remaining = state.suspendedPackages.filterTo(mutableSetOf()) { packageName ->
                if (packageName in VaultManager.blockedPackages(context) ||
                    packageName in ControlManager.newlyBlockedPackages(context)
                ) {
                    false
                } else {
                    when (isInstalled(context, packageName)) {
                        false -> false
                        null -> {
                            failures += "Could not check whether $packageName is installed."
                            true
                        }
                        true -> if (setSuspended(context, packageName, false)) false else {
                            failures += "Could not resume $packageName."
                            true
                        }
                    }
                }
            }
            if (remaining != state.suspendedPackages) saveSuspendedPackages(context, remaining)
            saveEnforcementError(context, failures.takeIf { it.isNotEmpty() }?.joinToString(" "))
            return
        }
        if (!hasUsageAccess(context)) {
            val message = "App limits cannot be enforced because Usage access is not granted."
            saveEnforcementError(context, message)
            logEnforcementWarning(message)
            return
        }
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            val message = "App limits cannot be enforced because Fortress Vault is no longer device owner."
            saveEnforcementError(context, message)
            logEnforcementWarning(message)
            return
        }

        val failures = mutableListOf<String>()
        val usage = usageMillisToday(context, state.limits.keys)
        val shouldSuspend = state.limits.filter { (packageName, limitMinutes) ->
            packageName != context.packageName &&
                packageName !in VaultManager.blockedPackages(context) &&
                packageName !in ControlManager.newlyBlockedPackages(context) &&
                isInstalled(context, packageName) == true &&
                (usage[packageName] ?: 0L) >= limitMinutes * 60_000L
        }.keys
        val toRelease = state.suspendedPackages - shouldSuspend
        val toSuspend = shouldSuspend - state.suspendedPackages

        val stillSuspended = state.suspendedPackages.toMutableSet()
        toRelease.forEach { packageName ->
            if (packageName in VaultManager.blockedPackages(context) ||
                packageName in ControlManager.newlyBlockedPackages(context)
            ) {
                stillSuspended.remove(packageName)
            } else {
                when (isInstalled(context, packageName)) {
                    false -> stillSuspended.remove(packageName)
                    null -> failures += "Could not check whether $packageName is installed."
                    true -> if (setSuspended(context, packageName, false)) {
                        stillSuspended.remove(packageName)
                    } else {
                        failures += "Could not resume $packageName after its daily limit reset."
                    }
                }
            }
        }
        toSuspend.forEach { packageName ->
            val alreadySuspended = try {
                context.packageManager.isPackageSuspended(packageName)
            } catch (error: Exception) {
                Log.e(TAG, "Could not check suspended state for $packageName", error)
                failures += "Could not verify suspended state for $packageName."
                return@forEach
            }
            if (alreadySuspended) return@forEach
            if (setSuspended(context, packageName, true)) {
                stillSuspended.add(packageName)
            } else {
                failures += "Android would not suspend the app that reached its daily limit."
            }
        }
        if (stillSuspended != state.suspendedPackages) {
            saveSuspendedPackages(context, stillSuspended)
        }
        saveEnforcementError(context, failures.takeIf { it.isNotEmpty() }?.joinToString(" "))
    }

    private fun setSuspended(context: Context, packageName: String, suspended: Boolean): Boolean {
        return try {
            val failed = context.getSystemService(DevicePolicyManager::class.java)
                .setPackagesSuspended(
                    FortressAdminReceiver.getComponentName(context),
                    arrayOf(packageName),
                    suspended
                )
            if (failed.isNotEmpty()) {
                Log.e(TAG, "Android refused to ${if (suspended) "suspend" else "resume"} $packageName")
                false
            } else {
                true
            }
        } catch (error: Exception) {
            Log.e(TAG, "Could not ${if (suspended) "suspend" else "resume"} $packageName", error)
            false
        }
    }

    private fun stopSentinelIfIdle(context: Context) {
        if (!VaultManager.isSealed(context) &&
            ControlManager.activeLocks(context).isEmpty() &&
            WebProtectionLockManager.activeLocks(context).isEmpty() &&
            !hasActiveWork(context)
        ) {
            SentinelController.stop(context)
        }
    }

    private fun isInstalled(context: Context, packageName: String): Boolean? =
        try {
            context.packageManager.getApplicationInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (error: Exception) {
            Log.e(TAG, "Could not inspect $packageName", error)
            null
        }

    private fun saveEnforcementError(context: Context, error: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_ENFORCEMENT_ERROR, null) == error) return
        prefs.edit().apply {
            if (error == null) remove(KEY_ENFORCEMENT_ERROR)
            else putString(KEY_ENFORCEMENT_ERROR, error)
        }.apply()
    }

    private fun readState(context: Context): State {
        cachedState?.let { return it }
        return synchronized(lock) {
            cachedState?.let { return it }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val persistent = PersistentVaultStore.readAppTimeLimits(context)
            val json = persistent ?: prefs.getString(KEY_STATE, null)
                ?: return@synchronized State(false, emptyMap(), emptySet()).also { cachedState = it }
            try {
                val root = JSONObject(json)
                val limitsObject = root.optJSONObject("limits") ?: JSONObject()
                val limits = buildMap {
                    limitsObject.keys().forEach { packageName ->
                        val minutes = limitsObject.getInt(packageName)
                        require(packageName.isNotBlank()) { "Saved app time-limit package name is blank." }
                        require(minutes in MIN_LIMIT_MINUTES..MAX_LIMIT_MINUTES) {
                            "A saved daily app limit is outside the supported range."
                        }
                        put(packageName, minutes)
                    }
                }
                val suspended = root.optJSONArray("suspendedPackages")?.let { array ->
                    buildSet {
                        for (index in 0 until array.length()) {
                            val packageName = array.getString(index)
                            require(packageName.isNotBlank()) { "Saved suspended app package name is blank." }
                            add(packageName)
                        }
                    }
                }.orEmpty()
                val enabled = root.optBoolean("enabled", false)
                require(!enabled || limits.isNotEmpty()) {
                    "Enabled daily app limits have no saved app allowances."
                }
                val state = State(enabled, limits, suspended)
                if (persistent == null &&
                    context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)
                ) {
                    PersistentVaultStore.writeAppTimeLimits(context, json)
                }
                state.also { cachedState = it }
            } catch (error: Exception) {
                Log.e(TAG, "Could not read daily app time limits", error)
                throw IllegalStateException("Saved daily app time-limit data is damaged.", error)
            }
        }
    }

    private fun save(context: Context, state: State) {
        val json = JSONObject().apply {
            put("enabled", state.enabled)
            put("limits", JSONObject().apply {
                state.limits.forEach { (packageName, minutes) -> put(packageName, minutes) }
            })
            put("suspendedPackages", org.json.JSONArray(state.suspendedPackages.toList()))
        }.toString()
        synchronized(lock) {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                PersistentVaultStore.writeAppTimeLimits(context, json)
                if (!context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(KEY_STATE, json).commit()
                ) {
                    Log.w(TAG, "Could not mirror daily app time limits locally; Device Owner storage remains authoritative.")
                }
                cachedState = state
            } else {
                check(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_STATE, json).commit()
                ) { "Could not save daily app time-limit settings." }
                cachedState = state
            }
        }
    }

    private fun saveSuspendedPackages(context: Context, packages: Set<String>) {
        synchronized(lock) {
            save(context, readState(context).copy(suspendedPackages = packages))
        }
    }

    private fun logEnforcementWarning(message: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastEnforcementWarningAt >= 5 * 60_000L) {
            lastEnforcementWarningAt = now
            Log.e(TAG, message)
        }
    }
}
