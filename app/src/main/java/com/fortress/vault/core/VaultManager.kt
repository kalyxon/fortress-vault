package com.fortress.vault.core

import android.app.admin.DevicePolicyManager
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fortress.vault.FortressAdminReceiver
import java.util.UUID
import java.util.concurrent.TimeUnit

object VaultManager {

    private const val PREFS_NAME = "fortress_vault_encrypted_prefs"
    private const val KEY_SEALS_JSON = "seals_json"

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

    // ---- Reads ----

    fun activeSeals(context: Context): List<Seal> {
        init(context)
        return SealCodec.decodeList(prefs.getString(KEY_SEALS_JSON, null))
    }

    fun isSealed(context: Context): Boolean = activeSeals(context).isNotEmpty()

    fun blockedPackages(context: Context): Set<String> =
        activeSeals(context).flatMap { it.packages }.toSet()

    fun sealFor(context: Context, packageName: String): Seal? =
        activeSeals(context).firstOrNull { packageName in it.packages }

    fun isInCooldown(context: Context, sealId: String): Boolean {
        val seal = activeSeals(context).firstOrNull { it.id == sealId } ?: return false
        return TimeKeeper.estimateCurrentTrustedTimeMillis(context) < seal.cooldownUntilMillis
    }

    /** Nearest unlock across all active seals — used for the admin-disable dialog copy. */
    fun remainingTimeLabel(context: Context): String {
        val seals = activeSeals(context)
        if (seals.isEmpty()) return "0 minutes"
        val nearest = seals.minOf { it.unlockAtMillis }
        return formatRemaining(nearest - TimeKeeper.estimateCurrentTrustedTimeMillis(context))
    }

    fun remainingLabelFor(context: Context, seal: Seal): String =
        formatRemaining(seal.unlockAtMillis - TimeKeeper.estimateCurrentTrustedTimeMillis(context))

    private fun formatRemaining(remainingMillisRaw: Long): String {
        val remainingMillis = remainingMillisRaw.coerceAtLeast(0)
        val days = TimeUnit.MILLISECONDS.toDays(remainingMillis)
        val hours = TimeUnit.MILLISECONDS.toHours(remainingMillis) % 24
        val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMillis) % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    fun isUsbDebuggingCurrentlyEnabled(context: Context): Boolean = runCatching {
        android.provider.Settings.Global.getInt(
            context.contentResolver,
            android.provider.Settings.Global.ADB_ENABLED,
            0
        ) == 1
    }.getOrDefault(false)

    suspend fun createSeal(context: Context, packages: Set<String>, durationDays: Int, allowAdb: Boolean = false): Pair<String, String> {
        // Backwards-compatible convenience: prepare then commit immediately.
        val (seal, phrase) = prepareSeal(context, packages, durationDays, allowAdb)
        commitSeal(context, seal)
        return seal.id to phrase
    }

    suspend fun prepareSeal(context: Context, packages: Set<String>, durationDays: Int, allowAdb: Boolean = false): Pair<Seal, String> {
        init(context)
        val alreadySealed = blockedPackages(context)
        val newPackages = packages - alreadySealed
        require(newPackages.isNotEmpty()) { "All selected apps are already sealed elsewhere." }

        val networkTime = TimeKeeper.fetchTrustedTimeMillis(context)
        val unlockAt = networkTime + TimeUnit.DAYS.toMillis(durationDays.toLong())
        val recoveryPhrase = RecoveryPhraseGenerator.generate()
        val hashed = PhraseHasher.hash(RecoveryPhraseGenerator.normalize(recoveryPhrase))
        val sealId = UUID.randomUUID().toString()

        val seal = Seal(
            id = sealId,
            packages = newPackages,
            sealedAtMillis = networkTime,
            unlockAtMillis = unlockAt,
            lastKnownGoodMillis = networkTime,
            recoverySalt = hashed.saltHex,
            recoveryHash = hashed.hashHex,
            allowAdb = allowAdb
        )

        return seal to recoveryPhrase
    }

    suspend fun commitSeal(context: Context, seal: Seal) {
        init(context)
        saveSeals(context, activeSeals(context) + seal)
        PackageFreezer.freezeAll(context, seal.packages)
        // Device-owner restrictions are updated by saveSeals -> updateDeviceOwnerRestrictions
        SentinelController.start(context)
    }

    /** Adds more time to an existing, still-active seal — same apps, later unlock, no new phrase. */
    suspend fun extendSeal(context: Context, sealId: String, extraDays: Int) {
        init(context)
        val seals = activeSeals(context)
        val target = seals.firstOrNull { it.id == sealId } ?: return
        TimeKeeper.fetchTrustedTimeMillis(context) // opportunistic resync
        val updated = target.copy(unlockAtMillis = target.unlockAtMillis + TimeUnit.DAYS.toMillis(extraDays.toLong()))
        saveSeals(context, seals.map { if (it.id == sealId) updated else it })
    }

    /** Called every Sentinel tick and on boot: expires finished seals, re-asserts freeze on active ones. */
    suspend fun verifyAndEnforce(context: Context) {
        init(context)
        reconcileWithPersistentStore(context)
        val seals = activeSeals(context)
        if (seals.isEmpty()) return

        val networkTime = TimeKeeper.fetchTrustedTimeMillis(context)
        val (expired, stillActive) = seals.partition { networkTime >= it.unlockAtMillis }

        // Punish backward clock manipulation per-seal, same as before.
        val adjustedActive = stillActive.map { seal ->
            if (networkTime < seal.lastKnownGoodMillis) {
                seal.copy(unlockAtMillis = seal.unlockAtMillis + TimeUnit.HOURS.toMillis(24))
            } else {
                seal.copy(lastKnownGoodMillis = networkTime)
            }
        }

        saveSeals(context, adjustedActive)

        if (expired.isNotEmpty()) {
            val stillBlocked = adjustedActive.flatMap { it.packages }.toSet()
            val toUnfreeze = expired.flatMap { it.packages }.toSet() - stillBlocked
            PackageFreezer.unfreezeAll(context, toUnfreeze)
            val unfrozenCount = toUnfreeze.size
            WelcomeBackNotifier.show(
                context,
                "Your sentence is complete. $unfrozenCount app${if (unfrozenCount == 1) "" else "s"} unfrozen."
            )
        }

        // Re-assert freeze on everything still covered by an active seal
        // (e.g. in case a reinstall slipped through between ticks).
        PackageFreezer.freezeAll(context, adjustedActive.flatMap { it.packages }.toSet())

        if (adjustedActive.isEmpty()) {
            releaseDeviceOwnerLock(context)
            SentinelController.stop(context)
        }
    }

    // ---- Emergency unlock (per-seal) ----

    fun attemptEmergencyUnlock(context: Context, sealId: String, enteredPhrase: String): Boolean {
        init(context)
        val seals = activeSeals(context)
        val target = seals.firstOrNull { it.id == sealId } ?: return false
        val trustedNow = TimeKeeper.estimateCurrentTrustedTimeMillis(context)
        if (trustedNow < target.cooldownUntilMillis) return false

        val normalized = RecoveryPhraseGenerator.normalize(enteredPhrase)
        val matches = PhraseHasher.matches(normalized, target.recoverySalt, target.recoveryHash)

        return if (matches) {
            val remaining = seals - target
            saveSeals(context, remaining)
            val stillBlocked = remaining.flatMap { it.packages }.toSet()
            PackageFreezer.unfreezeAll(context, target.packages - stillBlocked)
            WelcomeBackNotifier.show(
                context,
                "Emergency recovery phrase accepted for ${target.packages.size} app(s)."
            )
            if (remaining.isEmpty()) {
                releaseDeviceOwnerLock(context)
                SentinelController.stop(context)
            }
            true
        } else {
            val attempts = target.failedAttempts + 1
            val updated = if (attempts >= 3) {
                target.copy(failedAttempts = 0, cooldownUntilMillis = trustedNow + TimeUnit.HOURS.toMillis(24))
            } else {
                target.copy(failedAttempts = attempts)
            }
            saveSeals(context, seals.map { if (it.id == sealId) updated else it })
            false
        }
    }

    // ---- Internal helpers ----

    private fun saveSeals(context: Context, seals: List<Seal>) {
        prefs.edit().putString(KEY_SEALS_JSON, SealCodec.encodeList(seals)).apply()
        PersistentVaultStore.write(context, seals)
        // Ensure the Device Owner restrictions reflect the current set of active seals
        updateDeviceOwnerRestrictions(context)
    }

    private fun lockDeviceOwnerIfNeeded(context: Context) {
        // Deprecated: this method's semantics are now handled centrally by
        // updateDeviceOwnerRestrictions(). Kept for compatibility but no-op.
    }

    private fun updateDeviceOwnerRestrictions(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = FortressAdminReceiver.getComponentName(context)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        val seals = activeSeals(context)
        if (seals.isEmpty()) {
            // No active seals -> clear restrictive policies
            dpm.setUninstallBlocked(admin, context.packageName, false)
            dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_SAFE_BOOT)
            dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_DEBUGGING_FEATURES)
            return
        }

        // Always block uninstall and safe boot while any seal exists
        dpm.setUninstallBlocked(admin, context.packageName, true)
        dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_SAFE_BOOT)

        // Apply debugging restriction only if at least one active seal does NOT allow ADB
        val anyBlockDebugging = seals.any { !it.allowAdb }
        if (anyBlockDebugging) {
            dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_DEBUGGING_FEATURES)
        } else {
            dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_DEBUGGING_FEATURES)
        }
    }

    private fun releaseDeviceOwnerLock(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = FortressAdminReceiver.getComponentName(context)
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            dpm.setUninstallBlocked(admin, context.packageName, false)
            dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_SAFE_BOOT)
            dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_DEBUGGING_FEATURES)
        }
    }

    private fun reconcileWithPersistentStore(context: Context) {
        val persisted = PersistentVaultStore.read(context) ?: return
        val local = SealCodec.decodeList(prefs.getString(KEY_SEALS_JSON, null))

        if (persisted.isNotEmpty() && local.isEmpty()) {
            val punished = persisted.map { it.copy(unlockAtMillis = it.unlockAtMillis + TimeUnit.HOURS.toMillis(24)) }
            prefs.edit().putString(KEY_SEALS_JSON, SealCodec.encodeList(punished)).apply()
            PackageFreezer.freezeAll(context, punished.flatMap { it.packages }.toSet())
            PersistentVaultStore.write(context, punished)
            SentinelController.start(context)
        }
    }

    fun onAdminDisabled() {
        // No-op: unseal paths (expiry, emergency unlock) already clean up as they go.
    }
}
