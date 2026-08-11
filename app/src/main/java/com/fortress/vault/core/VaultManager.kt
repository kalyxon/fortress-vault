package com.fortress.vault.core

import android.app.admin.DevicePolicyManager
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fortress.vault.FortressAdminReceiver
import java.util.concurrent.TimeUnit

object VaultManager {

    private const val PREFS_NAME = "fortress_vault_encrypted_prefs"
    private const val KEY_SEALED = "is_sealed"
    private const val KEY_UNLOCK_TIME_MILLIS = "unlock_time_millis"
    private const val KEY_BLOCKED_PACKAGES = "blocked_packages"
    private const val KEY_RECOVERY_SALT = "recovery_salt"
    private const val KEY_RECOVERY_HASH = "recovery_hash"
    private const val KEY_FAILED_ATTEMPTS = "failed_unlock_attempts"
    private const val KEY_COOLDOWN_UNTIL = "cooldown_until_millis"
    private const val KEY_LAST_KNOWN_GOOD_TIME = "last_known_good_time_millis"

    private lateinit var prefs: android.content.SharedPreferences
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        initialized = true
        reconcileWithPersistentStore(context)
    }

    private fun reconcileWithPersistentStore(context: Context) {
        val persisted = PersistentVaultStore.read(context) ?: return
        val locallySealed = prefs.getBoolean(KEY_SEALED, false)

        if (persisted.sealed && !locallySealed) {
            prefs.edit()
                .putBoolean(KEY_SEALED, true)
                .putLong(KEY_UNLOCK_TIME_MILLIS, persisted.unlockTimeMillis + TimeUnit.HOURS.toMillis(24))
                .putLong(KEY_LAST_KNOWN_GOOD_TIME, persisted.lastKnownGoodMillis)
                .putStringSet(KEY_BLOCKED_PACKAGES, persisted.blockedPackages)
                .putString(KEY_RECOVERY_SALT, persisted.recoverySalt)
                .putString(KEY_RECOVERY_HASH, persisted.recoveryHash)
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .apply()

            PackageFreezer.freezeAll(context, persisted.blockedPackages)
            syncToPersistentStore(context)
            SentinelController.start(context)
        }
    }

    /** Pushes the current local state into the durable system-held copy. */
    private fun syncToPersistentStore(context: Context) {
        PersistentVaultStore.write(
            context,
            PersistentVaultStore.PersistedState(
                sealed = prefs.getBoolean(KEY_SEALED, false),
                unlockTimeMillis = prefs.getLong(KEY_UNLOCK_TIME_MILLIS, 0L),
                lastKnownGoodMillis = prefs.getLong(KEY_LAST_KNOWN_GOOD_TIME, 0L),
                blockedPackages = prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet()) ?: emptySet(),
                recoverySalt = prefs.getString(KEY_RECOVERY_SALT, null),
                recoveryHash = prefs.getString(KEY_RECOVERY_HASH, null)
            )
        )
    }

    fun isSealed(context: Context): Boolean {
        init(context)
        return prefs.getBoolean(KEY_SEALED, false)
    }

    fun unlockTimeMillis(context: Context): Long {
        init(context)
        return prefs.getLong(KEY_UNLOCK_TIME_MILLIS, 0L)
    }

    fun blockedPackages(context: Context): Set<String> {
        init(context)
        return prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet()) ?: emptySet()
    }

    suspend fun seal(
        context: Context,
        packages: Set<String>,
        durationDays: Int,
        recoveryPhrase: String = RecoveryPhraseGenerator.generate()
    ): String {
        init(context)
        val networkTime = TimeKeeper.fetchTrustedTimeMillis(context)
        val unlockAt = networkTime + TimeUnit.DAYS.toMillis(durationDays.toLong())
        val hashed = PhraseHasher.hash(RecoveryPhraseGenerator.normalize(recoveryPhrase))

        prefs.edit()
            .putBoolean(KEY_SEALED, true)
            .putLong(KEY_UNLOCK_TIME_MILLIS, unlockAt)
            .putStringSet(KEY_BLOCKED_PACKAGES, packages)
            .putString(KEY_RECOVERY_SALT, hashed.saltHex)
            .putString(KEY_RECOVERY_HASH, hashed.hashHex)
            .putLong(KEY_LAST_KNOWN_GOOD_TIME, networkTime)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .apply()

        PackageFreezer.freezeAll(context, packages)
        syncToPersistentStore(context)

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = FortressAdminReceiver.getComponentName(context)
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            // Device Owner only: makes the app itself un-uninstallable at the OS level.
            dpm.setUninstallBlocked(admin, context.packageName, true)
            dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_SAFE_BOOT)
        }

        SentinelController.start(context)
        return recoveryPhrase
    }

    /** Called every Sentinel tick and on boot. Re-asserts freeze state; unseals if time's up. */
    suspend fun verifyAndEnforce(context: Context) {
        init(context)
        reconcileWithPersistentStore(context)
        if (!isSealed(context)) return

        val networkTime = TimeKeeper.fetchTrustedTimeMillis(context)
        val lastKnownGood = prefs.getLong(KEY_LAST_KNOWN_GOOD_TIME, 0L)

        // Punish backward clock manipulation: if the device claims less time
        // has passed than our last confirmed network reading, something's off.
        if (networkTime < lastKnownGood) {
            extendLock(context, TimeUnit.HOURS.toMillis(24))
        } else {
            prefs.edit().putLong(KEY_LAST_KNOWN_GOOD_TIME, networkTime).apply()
        }

        if (networkTime >= unlockTimeMillis(context)) {
            unseal(context, reason = "Sentence complete")
            return
        }

        // Re-assert freeze in case the user managed to reinstall or re-enable anything.
        PackageFreezer.freezeAll(context, blockedPackages(context))
        syncToPersistentStore(context)
    }

    private fun extendLock(context: Context, extraMillis: Long) {
        val current = unlockTimeMillis(context)
        prefs.edit().putLong(KEY_UNLOCK_TIME_MILLIS, current + extraMillis).apply()
        syncToPersistentStore(context)
    }

    fun remainingTimeLabel(context: Context): String {
        if (!isSealed(context)) return "0 days"
        val remainingMillis = (unlockTimeMillis(context) - System.currentTimeMillis()).coerceAtLeast(0)
        val days = TimeUnit.MILLISECONDS.toDays(remainingMillis)
        val hours = TimeUnit.MILLISECONDS.toHours(remainingMillis) % 24
        return if (days > 0) "$days days" else "$hours hours"
    }

    // --- Emergency unlock ---

    fun isInCooldown(context: Context): Boolean {
        init(context)
        return System.currentTimeMillis() < prefs.getLong(KEY_COOLDOWN_UNTIL, 0L)
    }

    /** Returns true if unlock succeeded. Applies a 24h cooldown after 3 failed attempts. */
    fun attemptEmergencyUnlock(context: Context, enteredPhrase: String): Boolean {
        init(context)
        if (isInCooldown(context)) return false

        val salt = prefs.getString(KEY_RECOVERY_SALT, null)
        val expectedHash = prefs.getString(KEY_RECOVERY_HASH, null)
        val normalized = RecoveryPhraseGenerator.normalize(enteredPhrase)

        return if (salt != null && expectedHash != null &&
            PhraseHasher.matches(normalized, salt, expectedHash)
        ) {
            unseal(context, reason = "Emergency recovery phrase accepted")
            prefs.edit().putInt(KEY_FAILED_ATTEMPTS, 0).apply()
            true
        } else {
            val attempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
            val edit = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, attempts)
            if (attempts >= 3) {
                edit.putLong(KEY_COOLDOWN_UNTIL, System.currentTimeMillis() + TimeUnit.HOURS.toMillis(24))
                edit.putInt(KEY_FAILED_ATTEMPTS, 0)
            }
            edit.apply()
            false
        }
    }

    private fun unseal(context: Context, reason: String) {
        val packages = blockedPackages(context)
        PackageFreezer.unfreezeAll(context, packages)

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = FortressAdminReceiver.getComponentName(context)
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            dpm.setUninstallBlocked(admin, context.packageName, false)
            dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_SAFE_BOOT)
        }

        prefs.edit()
            .putBoolean(KEY_SEALED, false)
            .remove(KEY_UNLOCK_TIME_MILLIS)
            .remove(KEY_BLOCKED_PACKAGES)
            .remove(KEY_RECOVERY_SALT)
            .remove(KEY_RECOVERY_HASH)
            .apply()

        PersistentVaultStore.clear(context)

        SentinelController.stop(context)
        WelcomeBackNotifier.show(context, reason)
    }

    fun onAdminDisabled(context: Context) {
        // Device admin was legitimately removed (vault was already unsealed).
        // Nothing else to clean up — unseal() already handled state.
    }
}
