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
const val MIN_SEAL_DURATION_HOURS = 1
const val MAX_SEAL_DURATION_HOURS = MAX_SEAL_DURATION_DAYS * 24
const val MAX_APPS_PER_SEAL = 20
const val MAX_TEMPORARY_ACCESS_DURATION_MILLIS = 30_000L

object VaultManager {

    private const val PREFS_NAME = "fortress_vault_encrypted_prefs"
    private const val KEY_SEALS_JSON = "seals_json"

    /**
     * Grace window before we treat a time discrepancy as a clock-rollback attempt.
     * Estimation via elapsedRealtime + last-sync can drift by a minute or two;
     * only call it a rollback if the gap exceeds 5 minutes.
     */
    private val ROLLBACK_TOLERANCE_MS = TimeUnit.MINUTES.toMillis(5)

    /**
     * Minimum gap between successive rollback penalties on the same seal.
     * Prevents stacking from concurrent SentinelService / SentinelWorker calls.
     */
    private val PENALTY_COOLDOWN_MS = TimeUnit.HOURS.toMillis(25)

    private lateinit var prefs: android.content.SharedPreferences
    private var initialized = false
    private var cachedSeals: List<Seal>? = null
    private val lock = Any()

    fun init(context: Context) {
        synchronized(lock) {
            if (initialized) return
            try {
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
            } catch (e: Exception) {
                Log.e("VaultManager", "Failed to init EncryptedSharedPreferences, resetting", e)
                context.deleteSharedPreferences(PREFS_NAME)
                runCatching {
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
                }.getOrElse {
                    prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                }
            }
            initialized = true
            reconcileWithPersistentStore(context)
        }
    }

    fun activeSeals(context: Context): List<Seal> = synchronized(lock) {
        init(context)
        cachedSeals?.let { return it }
        val seals = SealCodec.decodeList(prefs.getString(KEY_SEALS_JSON, null))
        cachedSeals = seals
        seals
    }

    fun isSealed(context: Context): Boolean = activeSeals(context).isNotEmpty()

    fun blockedPackages(context: Context): Set<String> =
        activeSeals(context).flatMap { it.packages }.toSet()

    fun enforceDeviceOwnerRestrictions(context: Context) {
        init(context)
        updateDeviceOwnerRestrictions(context)
    }

    fun synchronizePersistentState(context: Context) {
        init(context)
        synchronized(lock) {
            reconcileWithPersistentStore(context)
        }
    }

    fun sealFor(context: Context, packageName: String): Seal? =
        activeSeals(context).firstOrNull { packageName in it.packages }

    fun restoreChromeAfterOrphanedSeal(context: Context) {
        restoreOrphanedPackage(context, "com.android.chrome")
    }

    fun restoreOrphanedPackage(context: Context, packageName: String) {
        init(context)
        synchronized(lock) {
            reconcileWithPersistentStore(context)
            require(packageName.isNotBlank() && packageName != context.packageName) {
                "Choose a valid app to restore."
            }
            check(activeSeals(context).none { packageName in it.packages }) {
                "This app is still included in an active seal. Unlock that seal first."
            }
            check(!WebProtectionManager.isPackageHiddenByBypassProtection(context, packageName)) {
                "This app is hidden by the active browser/VPN protection setting. Turn off that setting before restoring it."
            }
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            check(dpm.isDeviceOwnerApp(context.packageName)) {
                "Fortress must be the device owner to restore apps."
            }
            ControlManager.markUnsealedPackagesAsExisting(context, setOf(packageName))
            val restored = PackageFreezer.restorePackageVisibility(
                context,
                packageName,
                preserveUninstallBlock = ControlManager.isAppChangeBlocked(context)
            )
            check(restored) {
                "Android did not confirm that this app is installed, enabled, and launchable for this user."
            }
        }
    }

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

    suspend fun createSeal(context: Context, packages: Set<String>, durationHours: Int): Pair<String, String> {
        check(DeviceSecurity.hasSecureLock(context)) {
            "Set a secure phone lock before creating a seal."
        }
        val (seal, phrase) = prepareSeal(context, packages, durationHours)
        commitSeal(context, seal)
        return seal.id to phrase
    }

    suspend fun prepareSeal(
        context: Context,
        packages: Set<String>,
        durationHours: Int,
        websiteDomains: Map<String, Set<String>> = emptyMap()
    ): Pair<Seal, String> {
        check(DeviceSecurity.hasSecureLock(context)) {
            "Set a secure phone lock before creating a seal."
        }
        init(context)
        require(durationHours in MIN_SEAL_DURATION_HOURS..MAX_SEAL_DURATION_HOURS) {
            "Seal duration must be between $MIN_SEAL_DURATION_HOURS hour and $MAX_SEAL_DURATION_DAYS days."
        }
        require(packages.size <= MAX_APPS_PER_SEAL) {
            "A seal can include at most $MAX_APPS_PER_SEAL apps."
        }
        require(websiteDomains.keys.all { it in packages }) {
            "Website rules can only be added for apps in this seal."
        }
        val normalizedWebsiteDomains = websiteDomains.mapValues { (_, domains) ->
            domains.map(::normalizeWebsiteDomain).toSet()
        }.filterValues { it.isNotEmpty() }

        val alreadySealed = blockedPackages(context)
        val newPackages = packages - alreadySealed
        require(newPackages.isNotEmpty()) { "All selected apps are already sealed elsewhere." }

        val networkTime = TimeKeeper.fetchTrustedTimeMillis(context)
        val unlockAt = networkTime + TimeUnit.HOURS.toMillis(durationHours.toLong())
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
            websiteDomains = normalizedWebsiteDomains
        )

        return seal to recoveryPhrase
    }

    suspend fun commitSeal(context: Context, seal: Seal) {
        check(DeviceSecurity.hasSecureLock(context)) {
            "Set a secure phone lock before creating a seal."
        }
        init(context)
        check(PackageFreezer.freezeAll(context, seal.packages)) {
            "Fortress could not block one or more selected apps. The seal was not created. Confirm Fortress is Device Owner and try again."
        }
        synchronized(lock) {
            saveSeals(context, activeSeals(context) + seal)
        }
        if (seal.websiteDomains.isNotEmpty() &&
            !WebProtectionManager.applyChromeWebsitePolicy(context)
        ) {
            synchronized(lock) {
                saveSeals(context, activeSeals(context).filterNot { it.id == seal.id })
            }
            PackageFreezer.unfreezeAll(context, seal.packages)
            error("Chrome rejected the website-blocking policy. The seal was not created; confirm Chrome is installed and Fortress is Device Owner, then try again.")
        }
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

        val validPackages: Set<String>
        synchronized(lock) {
            val seals = activeSeals(context)
            val target = seals.firstOrNull { it.id == sealId } ?: return

            val alreadyBlocked = blockedPackages(context) - target.packages
            validPackages = newPackages - alreadyBlocked - target.packages
            require(validPackages.isNotEmpty()) { "All selected apps are already sealed elsewhere or already in this seal." }
            require(target.packages.size + validPackages.size <= MAX_APPS_PER_SEAL) {
                "A seal can include at most $MAX_APPS_PER_SEAL apps."
            }

        }

        val enforced = PackageFreezer.freezeAll(context, validPackages)
        if (!enforced) {
            PackageFreezer.unfreezeAll(context, validPackages)
            error("Fortress could not block one or more selected apps. The seal was not changed. Confirm Fortress is Device Owner and try again.")
        }

        synchronized(lock) {
            val seals = activeSeals(context)
            val target = seals.firstOrNull { it.id == sealId } ?: return
            val updated = target.copy(packages = target.packages + validPackages)
            saveSeals(context, seals.map { if (it.id == sealId) updated else it })
        }
    }

    fun updateSealWebsiteDomains(
        context: Context,
        sealId: String,
        websiteDomains: Map<String, Set<String>>
    ) {
        init(context)
        synchronized(lock) {
            val seals = activeSeals(context)
            val target = seals.firstOrNull { it.id == sealId }
                ?: throw IllegalArgumentException("This seal is no longer active.")
            require(websiteDomains.keys.all { it in target.packages }) {
                "Website rules can only be added for apps in this seal."
            }
            val normalized = websiteDomains.mapValues { (_, domains) ->
                domains.map(::normalizeWebsiteDomain).toSet()
            }.filterValues { it.isNotEmpty() }
            val lockedDomains = target.websiteDomains.mapValues { (packageName, domains) ->
                domains + normalized[packageName].orEmpty()
            }.toMutableMap()
            normalized.forEach { (packageName, domains) ->
                lockedDomains[packageName] = lockedDomains[packageName].orEmpty() + domains
            }
            saveSeals(context, seals.map {
                if (it.id == sealId) it.copy(websiteDomains = lockedDomains) else it
            })
            if (!WebProtectionManager.applyChromeWebsitePolicy(context)) {
                throw IllegalStateException(
                    "Website rule saved and locked until this seal ends, but Android did not confirm Chrome's block policy. Restart Chrome and check chrome://policy."
                )
            }
        }
    }

    suspend fun verifyAndEnforce(context: Context) {
        init(context)
        reconcileWithPersistentStore(context)

        val networkTime = TimeKeeper.fetchTrustedTimeMillis(context)

        synchronized(lock) {
            val seals = activeSeals(context)
            if (seals.isEmpty()) return

            val (expired, stillActive) = seals.partition { networkTime >= it.unlockAtMillis }

            val adjustedActive = stillActive.map { seal ->
                val rollbackDetected = networkTime < (seal.lastKnownGoodMillis - ROLLBACK_TOLERANCE_MS)
                val penaltyAlreadyApplied = (networkTime - seal.lastPenaltyAtMillis) < PENALTY_COOLDOWN_MS

                if (rollbackDetected && !penaltyAlreadyApplied) {
                    Log.i("VaultManager", "Clock rollback detected on seal ${seal.id} — extending by 24h")
                    WelcomeBackNotifier.show(context, "Clock rollback detected — seal extended by 24h.")
                    seal.copy(
                        unlockAtMillis = seal.unlockAtMillis + TimeUnit.HOURS.toMillis(24),
                        lastPenaltyAtMillis = networkTime
                    )
                } else {
                    // Advance lastKnownGood only when time is moving forward.
                    if (networkTime > seal.lastKnownGoodMillis) {
                        seal.copy(lastKnownGoodMillis = networkTime)
                    } else {
                        seal
                    }
                }
            }

            if (expired.isNotEmpty()) {
                val stillBlocked = adjustedActive.flatMap { it.packages }.toSet()
                val toUnfreeze = expired.flatMap { it.packages }.toSet() - stillBlocked
                ControlManager.markUnsealedPackagesAsExisting(context, toUnfreeze)
                try {
                    saveSeals(context, adjustedActive)
                } catch (error: Exception) {
                    runCatching { saveSeals(context, adjustedActive + expired) }
                        .onFailure(error::addSuppressed)
                    if (!PackageFreezer.freezeAll(context, toUnfreeze)) {
                        Log.e("VaultManager", "Could not reapply expired seal packages after a persistence failure")
                    }
                    throw error
                }
                val fullyUnfrozen = PackageFreezer.unfreezeAll(context, toUnfreeze)
                if (!fullyUnfrozen) {
                    Log.e("VaultManager", "Could not confirm unfreeze of expired seal packages; retaining expired seals for retry")
                    runCatching { saveSeals(context, adjustedActive + expired) }
                        .onFailure {
                            Log.e("VaultManager", "Could not restore expired seals after incomplete unfreeze", it)
                        }
                    if (!PackageFreezer.freezeAll(context, toUnfreeze)) {
                        Log.e("VaultManager", "Could not reapply the expired seal after an incomplete unfreeze")
                    }
                }
                if (fullyUnfrozen) {
                    val unfrozenCount = toUnfreeze.size
                    WelcomeBackNotifier.show(
                        context,
                        "Your sentence is complete. $unfrozenCount app${if (unfrozenCount == 1) "" else "s"} unfrozen."
                    )
                } else {
                    WelcomeBackNotifier.show(context, "Android could not unblock every app yet. Fortress will retry.")
                }
            } else {
                saveSeals(context, adjustedActive)
            }

            val sealsAfterEnforcement = activeSeals(context)
            PackageFreezer.freezeAll(context, sealsAfterEnforcement.flatMap { it.packages }.toSet())

            if (sealsAfterEnforcement.isEmpty()) {
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
                val stillBlocked = remaining.flatMap { it.packages }.toSet()
                val toUnfreeze = target.packages - stillBlocked
                ControlManager.markUnsealedPackagesAsExisting(context, toUnfreeze)
                try {
                    saveSeals(context, remaining)
                } catch (error: Exception) {
                    runCatching { saveSeals(context, seals) }
                        .onFailure(error::addSuppressed)
                    if (!PackageFreezer.freezeAll(context, toUnfreeze)) {
                        Log.e("VaultManager", "Could not reapply seal ${target.id} after a persistence failure")
                    }
                    throw IllegalStateException(
                        "Emergency unlock could not be completed safely. The seal remains active; try again.",
                        error
                    )
                }
                if (!PackageFreezer.unfreezeAll(context, toUnfreeze)) {
                    runCatching { saveSeals(context, seals) }
                        .onFailure {
                            Log.e("VaultManager", "Could not restore seal ${target.id} after incomplete emergency unfreeze", it)
                        }
                    if (!PackageFreezer.freezeAll(context, toUnfreeze)) {
                        Log.e("VaultManager", "Could not reapply seal ${target.id} after an incomplete emergency unfreeze")
                    }
                    throw IllegalStateException(
                        "Android could not confirm that every app in this seal was unblocked. " +
                            "The seal remains active; try again or restart the device."
                    )
                }
                WelcomeBackNotifier.show(
                    context,
                    "Emergency recovery phrase accepted for ${target.packages.size} app(s)."
                )
                if (remaining.isEmpty()) {
                    WelcomeBackNotifier.clear(context)
                    releaseDeviceOwnerLock(context)
                    if (ControlManager.activeLocks(context).isEmpty()) {
                        SentinelController.stop(context)
                    }
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
        if (durationMillis !in 1..MAX_TEMPORARY_ACCESS_DURATION_MILLIS) return false
        val target = synchronized(lock) {
            val seals = activeSeals(context)
            val current = seals.firstOrNull { packageName in it.packages } ?: return false
            val trustedNow = TimeKeeper.estimateCurrentTrustedTimeMillis(context)
            if (trustedNow < current.cooldownUntilMillis) return false

            val normalized = RecoveryPhraseGenerator.normalize(enteredPhrase)
            if (!PhraseHasher.matches(normalized, current.recoverySalt, current.recoveryHash)) {
                val attempts = current.failedAttempts + 1
                val updated = if (attempts >= 3) {
                    current.copy(
                        failedAttempts = 0,
                        cooldownUntilMillis = trustedNow + TimeUnit.HOURS.toMillis(24)
                    )
                } else {
                    current.copy(failedAttempts = attempts)
                }
                saveSeals(context, seals.map { if (it.id == current.id) updated else it })
                return false
            }
            current
        }

        try {
            val data = androidx.work.Data.Builder()
                .putString(com.fortress.vault.service.TemporaryRefreezeWorker.KEY_PACKAGE, packageName)
                .build()
            val work = androidx.work.OneTimeWorkRequestBuilder<com.fortress.vault.service.TemporaryRefreezeWorker>()
                .setInitialDelay(durationMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setInputData(data)
                .build()
            androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                "fortress_temporary_refreeze:$packageName",
                androidx.work.ExistingWorkPolicy.REPLACE,
                work
            )
        } catch (error: Exception) {
            Log.e("VaultManager", "Could not schedule temporary refreeze for $packageName", error)
            return false
        }

        if (activeSeals(context).none { it.id == target.id && packageName in it.packages }) {
            androidx.work.WorkManager.getInstance(context)
                .cancelUniqueWork("fortress_temporary_refreeze:$packageName")
            return false
        }
        PackageFreezer.unfreezeAll(context, setOf(packageName))
        return true
    }

    private fun saveSeals(context: Context, seals: List<Seal>) {
        synchronized(lock) {
            val previous = cachedSeals
                ?: SealCodec.decodeList(prefs.getString(KEY_SEALS_JSON, null))
            try {
                PersistentVaultStore.write(context, seals)
                check(prefs.edit().putString(KEY_SEALS_JSON, SealCodec.encodeList(seals)).commit()) {
                    "Could not save active seals to local storage."
                }
                cachedSeals = seals
                updateDeviceOwnerRestrictions(context)
            } catch (error: Exception) {
                cachedSeals = previous
                runCatching {
                    check(prefs.edit().putString(KEY_SEALS_JSON, SealCodec.encodeList(previous)).commit()) {
                        "Could not restore the previous local seal state."
                    }
                }.onFailure(error::addSuppressed)
                runCatching { PersistentVaultStore.write(context, previous) }
                    .onFailure(error::addSuppressed)
                throw IllegalStateException("Could not safely update the active seal state.", error)
            }
        }
        WebProtectionManager.apply(context)
    }

    private fun updateDeviceOwnerRestrictions(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = FortressAdminReceiver.getComponentName(context)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        val seals = activeSeals(context)
        if (seals.isEmpty()) {
            dpm.setUninstallBlocked(admin, context.packageName, false)
            dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_SAFE_BOOT)
            dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            // Release seal-owned restrictions when no seals are active.
            setUserControlDisabledPackages(context, dpm, admin, emptyList())
            return
        }

        dpm.setUninstallBlocked(admin, context.packageName, true)
        dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_SAFE_BOOT)
        dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        setUserControlDisabledPackages(
            context,
            dpm,
            admin,
            seals.flatMap { it.packages }.distinct()
        )

    }

    private fun releaseDeviceOwnerLock(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = FortressAdminReceiver.getComponentName(context)
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            dpm.setUninstallBlocked(admin, context.packageName, false)
            dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_SAFE_BOOT)
            dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            setUserControlDisabledPackages(context, dpm, admin, emptyList())
        }
    }

    private fun setUserControlDisabledPackages(
        context: Context,
        dpm: DevicePolicyManager,
        admin: android.content.ComponentName,
        packages: List<String>
    ) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        val protectedPackages = packages.toMutableSet()
        if (WebProtectionLockManager.activeLocks(context).isNotEmpty()) {
            protectedPackages += context.packageName
        }
        try {
            dpm.setUserControlDisabledPackages(admin, protectedPackages.toList())
        } catch (error: Exception) {
            Log.e("VaultManager", "Could not protect locked apps from user-control changes", error)
            throw error
        }
    }

    private fun reconcileWithPersistentStore(context: Context) {
        val persisted = PersistentVaultStore.read(context) ?: return
        val local = SealCodec.decodeList(prefs.getString(KEY_SEALS_JSON, null))

        if (persisted.isNotEmpty() && local.isEmpty()) {
            val punished = persisted.map { it.copy(unlockAtMillis = it.unlockAtMillis + TimeUnit.HOURS.toMillis(24)) }
            PersistentVaultStore.write(context, punished)
            check(prefs.edit().putString(KEY_SEALS_JSON, SealCodec.encodeList(punished)).commit()) {
                "Could not reconcile the locally stored seal state."
            }
            cachedSeals = punished
            PackageFreezer.freezeAll(context, punished.flatMap { it.packages }.toSet())
            SentinelController.start(context)
        } else {
            cachedSeals = local
        }
    }

    fun onAdminDisabled() {
        // No-op: unseal paths (expiry, emergency unlock) already clean up as they go.
    }
}
