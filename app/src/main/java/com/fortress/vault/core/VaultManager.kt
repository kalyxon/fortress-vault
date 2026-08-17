package com.fortress.vault.core

import android.app.admin.DevicePolicyManager
import android.util.Log
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fortress.vault.FortressAdminReceiver
import java.util.UUID
import java.util.concurrent.TimeUnit

const val MIN_SEAL_DURATION_DAYS = 1
const val MAX_SEAL_DURATION_DAYS = 365

object VaultManager {

    private const val PREFS_NAME = "fortress_vault_encrypted_prefs"
    private const val KEY_SEALS_JSON = "seals_json"

    private lateinit var prefs: android.content.SharedPreferences
    private var initialized = false
    private val lock = Any()

    fun init(context: Context) {
        synchronized(lock) {
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
    }

    fun activeSeals(context: Context): List<Seal> = synchronized(lock) {
        init(context)
        SealCodec.decodeList(prefs.getString(KEY_SEALS_JSON, null))
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
        val (seal, phrase) = prepareSeal(context, packages, durationDays, allowAdb)
        commitSeal(context, seal)
        return seal.id to phrase
    }

    suspend fun prepareSeal(context: Context, packages: Set<String>, durationDays: Int, allowAdb: Boolean = false): Pair<Seal, String> {
        init(context)
        require(durationDays in MIN_SEAL_DURATION_DAYS..MAX_SEAL_DURATION_DAYS) {
            "Seal duration must be between $MIN_SEAL_DURATION_DAYS and $MAX_SEAL_DURATION_DAYS days."
        }

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
        synchronized(lock) {
            saveSeals(context, activeSeals(context) + seal)
        }
        PackageFreezer.freezeAll(context, seal.packages)
        SentinelController.start(context)
    }

    suspend fun extendSeal(context: Context, sealId: String, extraDays: Int) {
        init(context)
        require(extraDays in MIN_SEAL_DURATION_DAYS..MAX_SEAL_DURATION_DAYS) {
            "Added time must be between $MIN_SEAL_DURATION_DAYS and $MAX_SEAL_DURATION_DAYS days."
        }

        val networkTime = TimeKeeper.fetchTrustedTimeMillis(context)
        synchronized(lock) {
            val seals = activeSeals(context)
            val target = seals.firstOrNull { it.id == sealId } ?: return
            val updated = target.copy(unlockAtMillis = target.unlockAtMillis + TimeUnit.DAYS.toMillis(extraDays.toLong()), lastKnownGoodMillis = networkTime)
            saveSeals(context, seals.map { if (it.id == sealId) updated else it })
        }
    }

    suspend fun addPackagesToSeal(context: Context, sealId: String, newPackages: Set<String>) {
        init(context)
        require(newPackages.isNotEmpty()) { "No apps selected to add to this seal." }

        val updated: Seal
        synchronized(lock) {
            val seals = activeSeals(context)
            val target = seals.firstOrNull { it.id == sealId } ?: return

            val alreadyBlocked = blockedPackages(context) - target.packages
            val validPackages = newPackages - alreadyBlocked
            require(validPackages.isNotEmpty()) { "All selected apps are already sealed elsewhere or already in this seal." }

            updated = target.copy(packages = target.packages + validPackages)
            saveSeals(context, seals.map { if (it.id == sealId) updated else it })
        }
        PackageFreezer.freezeAll(context, updated.packages)
    }

    suspend fun verifyAndEnforce(context: Context) {
        init(context)
        reconcileWithPersistentStore(context)

        val networkTime = TimeKeeper.fetchTrustedTimeMillis(context)

        synchronized(lock) {
            val seals = activeSeals(context)
            if (seals.isEmpty()) return

            try {
                if (isUsbDebuggingCurrentlyEnabled(context)) {
                    val offenders = seals.filter { !it.allowAdb }
                    if (offenders.isNotEmpty()) {
                        val extended = seals.map { seal ->
                            if (!seal.allowAdb) seal.copy(unlockAtMillis = seal.unlockAtMillis + TimeUnit.HOURS.toMillis(24))
                            else seal
                        }
                        Log.i("VaultManager", "USB debugging detected while sealed — extending ${offenders.size} seal(s) by 24h")
                        saveSeals(context, extended)
                        PackageFreezer.freezeAll(context, extended.flatMap { it.packages }.toSet())
                        WelcomeBackNotifier.show(context, "USB debugging detected while sealed — affected seals extended by 24h.")
                    }
                }
            } catch (_: Exception) {
            }

            val (expired, stillActive) = seals.partition { networkTime >= it.unlockAtMillis }

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

            PackageFreezer.freezeAll(context, adjustedActive.flatMap { it.packages }.toSet())

            if (adjustedActive.isEmpty()) {
                WelcomeBackNotifier.clear(context)
                releaseDeviceOwnerLock(context)
                SentinelController.stop(context)
            }
        }
    }

    fun attemptEmergencyUnlock(context: Context, sealId: String, enteredPhrase: String): Boolean {
        init(context)
        synchronized(lock) {
            val seals = activeSeals(context)
            val target = seals.firstOrNull { it.id == sealId } ?: return false
            val trustedNow = TimeKeeper.estimateCurrentTrustedTimeMillis(context)
            if (trustedNow < target.cooldownUntilMillis) return false

            val normalized = RecoveryPhraseGenerator.normalize(enteredPhrase)
            val matches = PhraseHasher.matches(normalized, target.recoverySalt, target.recoveryHash)

            if (matches) {
                val remaining = seals - target
                saveSeals(context, remaining)
                val stillBlocked = remaining.flatMap { it.packages }.toSet()
                PackageFreezer.unfreezeAll(context, target.packages - stillBlocked)
                WelcomeBackNotifier.show(
                    context,
                    "Emergency recovery phrase accepted for ${target.packages.size} app(s)."
                )
                if (remaining.isEmpty()) {
                    WelcomeBackNotifier.clear(context)
                    releaseDeviceOwnerLock(context)
                    SentinelController.stop(context)
                }
                return true
            }

            val attempts = target.failedAttempts + 1
            val updated = if (attempts >= 3) {
                target.copy(failedAttempts = 0, cooldownUntilMillis = trustedNow + TimeUnit.HOURS.toMillis(24))
            } else {
                target.copy(failedAttempts = attempts)
            }
            saveSeals(context, seals.map { if (it.id == sealId) updated else it })
            return false
        }
    }

    fun grantTemporaryAccess(context: Context, packageName: String, durationMillis: Long, enteredPhrase: String): Boolean {
        init(context)
        val seals = activeSeals(context)
        val target = seals.firstOrNull { packageName in it.packages } ?: return false
        val normalized = RecoveryPhraseGenerator.normalize(enteredPhrase)
        val matches = PhraseHasher.matches(normalized, target.recoverySalt, target.recoveryHash)
        val trustedNow = TimeKeeper.estimateCurrentTrustedTimeMillis(context)
        if (!matches) {
            return false
        }

        PackageFreezer.unfreezeAll(context, setOf(packageName))
        try {
            val data = androidx.work.Data.Builder().putString(com.fortress.vault.service.TemporaryRefreezeWorker.KEY_PACKAGE, packageName).build()
            val work = androidx.work.OneTimeWorkRequestBuilder<com.fortress.vault.service.TemporaryRefreezeWorker>()
                .setInitialDelay(durationMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setInputData(data)
                .build()
            androidx.work.WorkManager.getInstance(context).enqueue(work)
        } catch (e: Exception) {
        }
        return true
    }

    private fun saveSeals(context: Context, seals: List<Seal>) {
        synchronized(lock) {
            prefs.edit().putString(KEY_SEALS_JSON, SealCodec.encodeList(seals)).apply()
            PersistentVaultStore.write(context, seals)
            updateDeviceOwnerRestrictions(context)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun lockDeviceOwnerIfNeeded(context: Context) {
    }

    private fun updateDeviceOwnerRestrictions(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = FortressAdminReceiver.getComponentName(context)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        val seals = activeSeals(context)
        if (seals.isEmpty()) {
            dpm.setUninstallBlocked(admin, context.packageName, false)
            dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_SAFE_BOOT)
            dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_DEBUGGING_FEATURES)
            return
        }

        dpm.setUninstallBlocked(admin, context.packageName, true)
        dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_SAFE_BOOT)

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
