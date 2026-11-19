package com.fortress.vault.core

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class WebProtectionSetting(val key: String, val title: String) {
    ADULT_SITES("adult_sites", "Block adult websites"),
    CHROME_ONLY("chrome_only", "Allow only Chrome and block VPNs"),
    SEALED_APP_WEBSITES("sealed_app_websites", "Block websites for sealed apps"),
    CHROME_INCOGNITO("chrome_incognito", "Disable Chrome Incognito"),
    APP_TIME_LIMITS("app_time_limits", "Daily app time limits"),
    APP_SCHEDULE("app_schedule", "App time scheduler"),
    REELS_BLOCKER("reels_blocker", "Reels & Shorts blocker")
}

data class WebProtectionLock(
    val setting: WebProtectionSetting,
    val salt: String,
    val hash: String,
    val failedAttempts: Int = 0,
    val cooldownUntilMillis: Long = 0L,
    val unlockAtMillis: Long = 0L
)

data class PreparedWebProtectionLock(
    val lock: WebProtectionLock,
    val recoveryPhrase: String
)

enum class WebProtectionUnlockResult {
    UNLOCKED,
    WRONG_PHRASE,
    COOLDOWN,
    NOT_LOCKED
}

object WebProtectionLockManager {
    private const val TAG = "WebProtectionLock"
    private const val MAX_FAILED_ATTEMPTS = 3
    const val MIN_LOCK_DAYS = 7
    const val DEFAULT_LOCK_DAYS = 30
    const val MAX_LOCK_DAYS = 365
    private val lock = Any()
    @Volatile private var cachedLocks: Map<WebProtectionSetting, WebProtectionLock>? = null

    fun activeLocks(context: Context): Map<WebProtectionSetting, WebProtectionLock> =
        synchronized(lock) {
            cachedLocks?.let { return it }
            val persisted = PersistentVaultStore.readWebProtectionLocks(context)
            val encoded = persisted ?: context.getSharedPreferences(
                "fortress_web_protection_locks",
                Context.MODE_PRIVATE
            ).getString("locks_json", null)
            val decoded = decode(encoded)
            cachedLocks = decoded
            decoded
        }

    fun isLocked(context: Context, setting: WebProtectionSetting): Boolean {
        reconcileExpired(context)
        return activeLocks(context).containsKey(setting)
    }

    fun prepareLock(context: Context, setting: WebProtectionSetting): PreparedWebProtectionLock {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "Android 11 or newer is required to protect a locked setting from being cleared in system settings."
        }
        check(context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)) {
            "Set Fortress Vault as the device owner before locking web-protection settings."
        }
        check(!isLocked(context, setting)) { "This setting is already locked." }
        check(WebProtectionManager.isSettingEnabled(context, setting)) {
            "Turn on this protection before locking it."
        }
        val phrase = RecoveryPhraseGenerator.generate()
        val hashed = PhraseHasher.hash(RecoveryPhraseGenerator.normalize(phrase))
        return PreparedWebProtectionLock(
            WebProtectionLock(setting, hashed.saltHex, hashed.hashHex),
            phrase
        )
    }

    suspend fun commitLock(
        context: Context,
        prepared: PreparedWebProtectionLock,
        durationDays: Int = DEFAULT_LOCK_DAYS
    ) {
        require(durationDays in MIN_LOCK_DAYS..MAX_LOCK_DAYS) {
            "Recovery locks must last between $MIN_LOCK_DAYS and $MAX_LOCK_DAYS days."
        }
        reconcileExpired(context)
        val trustedNow = TimeKeeper.fetchTrustedTimeMillis(context)
        val committedLock = prepared.lock.copy(
            unlockAtMillis = trustedNow + TimeUnit.DAYS.toMillis(durationDays.toLong())
        )
        synchronized(lock) {
            val locks = activeLocks(context)
            check(committedLock.setting !in locks) { "This setting is already locked." }
            check(WebProtectionManager.isSettingEnabled(context, committedLock.setting)) {
                "Turn on this protection before locking it."
            }
            save(context, locks + (committedLock.setting to committedLock))
        }
        try {
            VaultManager.enforceDeviceOwnerRestrictions(context)
        } catch (error: Exception) {
            val rollbackError = runCatching {
                synchronized(lock) {
                    val locks = activeLocks(context) - committedLock.setting
                    save(context, locks)
                }
            }.exceptionOrNull()
            if (rollbackError != null) {
                error.addSuppressed(rollbackError)
                throw IllegalStateException(
                    "Could not protect this setting, and its recovery lock could not be rolled back. Do not clear Fortress Vault's app data.",
                    error
                )
            }
            throw IllegalStateException(
                "Could not protect this setting from being cleared in Android settings.",
                error
            )
        }
        SentinelController.start(context)
    }

    fun unlock(
        context: Context,
        setting: WebProtectionSetting,
        recoveryPhrase: String
    ): WebProtectionUnlockResult {
        reconcileExpired(context)
        return synchronized(lock) {
            val locks = activeLocks(context)
            val target = locks[setting] ?: return WebProtectionUnlockResult.NOT_LOCKED
            val now = TimeKeeper.estimateCurrentTrustedTimeMillis(context)
            if (now < target.cooldownUntilMillis) return WebProtectionUnlockResult.COOLDOWN
            if (!PhraseHasher.matches(
                    RecoveryPhraseGenerator.normalize(recoveryPhrase),
                    target.salt,
                    target.hash
                )
            ) {
                val attempts = target.failedAttempts + 1
                val updated = if (attempts >= MAX_FAILED_ATTEMPTS) {
                    target.copy(
                        failedAttempts = 0,
                        cooldownUntilMillis = now + TimeUnit.HOURS.toMillis(24)
                    )
                } else {
                    target.copy(failedAttempts = attempts)
                }
                save(context, locks + (setting to updated))
                return WebProtectionUnlockResult.WRONG_PHRASE
            }
            save(context, locks - setting)
            try {
                VaultManager.enforceDeviceOwnerRestrictions(context)
            } catch (error: Exception) {
                val rollbackError = runCatching {
                    save(context, locks)
                    VaultManager.enforceDeviceOwnerRestrictions(context)
                }.exceptionOrNull()
                if (rollbackError != null) {
                    error.addSuppressed(rollbackError)
                    Log.e(TAG, "Could not restore user-control protection after a failed unlock", rollbackError)
                    throw IllegalStateException(
                        "Could not safely unlock this setting or restore its recovery lock. Do not clear Fortress Vault's app data.",
                        error
                    )
                }
                throw IllegalStateException(
                    "Could not safely unlock this web-protection setting.",
                    error
                )
            }
            WebProtectionUnlockResult.UNLOCKED
        }.also {
            if (it == WebProtectionUnlockResult.UNLOCKED &&
                activeLocks(context).isEmpty() &&
                !VaultManager.isSealed(context) &&
                ControlManager.activeLocks(context).isEmpty()
            ) {
                SentinelController.stop(context)
            }
        }
    }

    fun reconcileExpired(context: Context): Boolean {
        val now = TimeKeeper.estimateCurrentTrustedTimeMillis(context)
        val (previous, active) = synchronized(lock) {
            val stored = activeLocks(context)
            val unexpired = stored.filterValues { it.unlockAtMillis == 0L || now < it.unlockAtMillis }
            if (unexpired != stored) save(context, unexpired)
            stored to unexpired
        }
        if (active == previous) return false
        try {
            VaultManager.enforceDeviceOwnerRestrictions(context)
        } catch (error: Exception) {
            val rollbackError = runCatching {
                synchronized(lock) { save(context, previous) }
                VaultManager.enforceDeviceOwnerRestrictions(context)
            }.exceptionOrNull()
            if (rollbackError != null) {
                error.addSuppressed(rollbackError)
                Log.e(TAG, "Could not restore expired web-protection locks after enforcement failed", rollbackError)
            }
            throw IllegalStateException(
                "Could not safely expire the web-protection recovery lock.",
                error
            )
        }
        return true
    }

    private fun save(
        context: Context,
        locks: Map<WebProtectionSetting, WebProtectionLock>
    ) {
        val json = JSONObject().apply {
            locks.forEach { (setting, item) ->
                put(setting.key, JSONObject().apply {
                    put("salt", item.salt)
                    put("hash", item.hash)
                    put("failedAttempts", item.failedAttempts)
                    put("cooldownUntil", item.cooldownUntilMillis)
                    put("unlockAt", item.unlockAtMillis)
                })
            }
        }.toString()
        PersistentVaultStore.writeWebProtectionLocks(context, json)
        cachedLocks = locks
        val mirrored = context.getSharedPreferences(
            "fortress_web_protection_locks",
            Context.MODE_PRIVATE
        ).edit().putString("locks_json", json).commit()
        if (!mirrored) {
            Log.w(TAG, "Could not mirror recovery locks locally; the Device Owner record remains authoritative.")
        }
    }

    private fun decode(json: String?): Map<WebProtectionSetting, WebProtectionLock> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val root = JSONObject(json)
            buildMap {
                WebProtectionSetting.entries.forEach { setting ->
                    val item = root.optJSONObject(setting.key) ?: return@forEach
                    put(
                        setting,
                        WebProtectionLock(
                            setting = setting,
                            salt = item.getString("salt"),
                            hash = item.getString("hash"),
                            failedAttempts = item.optInt("failedAttempts", 0).coerceIn(0, MAX_FAILED_ATTEMPTS - 1),
                            cooldownUntilMillis = item.optLong("cooldownUntil", 0L),
                            unlockAtMillis = item.optLong("unlockAt", 0L)
                        )
                    )
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Could not read web-protection recovery locks", error)
            throw IllegalStateException("Saved web-protection recovery data is damaged.", error)
        }
    }
}
