package com.fortress.vault.core

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.UserManager
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fortress.vault.FortressAdminReceiver
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class DeviceControl(val key: String, val title: String) {
    USB_DEBUGGING("usb_debugging", "Block USB debugging"),
    USER_ACCOUNTS("user_accounts", "Block additional user accounts"),
    APP_INSTALLS("app_installs", "Block new apps / uninstalls")
}

const val MAX_UPDATE_FRIENDLY_APP_CHANGE_DAYS = 90
const val MAX_FULL_APP_CHANGE_BLOCK_DAYS = 30

data class ControlLock(
    val control: DeviceControl,
    val unlockAtMillis: Long,
    val recoverySalt: String,
    val recoveryHash: String,
    val fullAppChangeBlock: Boolean = false
)

data class PreparedControls(
    val locks: Map<DeviceControl, ControlLock>,
    val phrases: Map<DeviceControl, String>,
    val fullAppChangeBlock: Boolean = false
)

object ControlManager {
    private const val PREFS_NAME = "fortress_device_controls"
    private const val LOCKS_JSON = "locks_json"
    private const val BLOCKED_NEW_PACKAGES = "blocked_new_packages"
    private const val INSTALL_BASELINE_PACKAGES = "install_baseline_packages"
    private const val LEGACY_PROTECTED_UNINSTALL_PACKAGES = "protected_uninstall_packages"
    private val lock = Any()
    private lateinit var prefs: android.content.SharedPreferences
    private var cachedLocks: List<ControlLock>? = null

    // Tracks the last state that was actually applied to DevicePolicyManager.
    // apply() compares against this and skips IPC calls when nothing changed.
    private data class AppliedState(
        val usbDebugging: Boolean,
        val userAccounts: Boolean,
        val fullAppChangeBlock: Boolean,
        val isBlockingAppChanges: Boolean
    )
    @Volatile private var lastAppliedState: AppliedState? = null

    fun init(context: Context) {
        synchronized(lock) {
            if (::prefs.isInitialized) return
            try {
                val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                prefs = EncryptedSharedPreferences.create(
                    context, PREFS_NAME, key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.e("ControlManager", "Failed to init EncryptedSharedPreferences, resetting", e)
                context.deleteSharedPreferences(PREFS_NAME)
                runCatching {
                    val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                    prefs = EncryptedSharedPreferences.create(
                        context, PREFS_NAME, key,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                }.getOrElse {
                    prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                }
            }
        }
    }

    fun activeLocks(context: Context): List<ControlLock> = synchronized(lock) {
        init(context)
        cachedLocks?.let { return it }
        val json = prefs.getString(LOCKS_JSON, null) ?: run {
            cachedLocks = emptyList()
            return emptyList()
        }
        val list = runCatching {
            val root = JSONObject(json)
            DeviceControl.entries.mapNotNull { control ->
                val item = root.optJSONObject(control.key) ?: return@mapNotNull null
                ControlLock(
                    control,
                    item.getLong("unlockAt"),
                    item.getString("salt"),
                    item.getString("hash"),
                    item.optBoolean("fullAppChangeBlock", false)
                )
            }
        }.getOrDefault(emptyList())
        cachedLocks = list
        list
    }

    suspend fun prepare(
        context: Context,
        controls: Set<DeviceControl>,
        durationDays: Int,
        fullAppChangeBlock: Boolean = false
    ): PreparedControls {
        check(DeviceSecurity.hasSecureLock(context)) {
            "Set a secure phone lock before enabling device controls."
        }
        require(controls.isNotEmpty()) { "Select at least one control." }
        val maximumDays = when {
            DeviceControl.APP_INSTALLS !in controls -> MAX_SEAL_DURATION_DAYS
            fullAppChangeBlock -> MAX_FULL_APP_CHANGE_BLOCK_DAYS
            else -> MAX_UPDATE_FRIENDLY_APP_CHANGE_DAYS
        }
        require(durationDays in MIN_SEAL_DURATION_DAYS..maximumDays) {
            "Duration must be between $MIN_SEAL_DURATION_DAYS and $maximumDays days for the selected controls."
        }
        val unlockAt = TimeKeeper.fetchTrustedTimeMillis(context) + TimeUnit.DAYS.toMillis(durationDays.toLong())
        val newLocks = controls.associateWith { control ->
            val phrase = RecoveryPhraseGenerator.generate()
            val hashed = PhraseHasher.hash(RecoveryPhraseGenerator.normalize(phrase))
            ControlLock(control, unlockAt, hashed.saltHex, hashed.hashHex) to phrase
        }
        val locks = newLocks.mapValues { (control, value) ->
            value.first.copy(
                fullAppChangeBlock = control == DeviceControl.APP_INSTALLS && fullAppChangeBlock
            )
        }
        return PreparedControls(
            locks,
            newLocks.mapValues { it.value.second },
            fullAppChangeBlock
        )
    }

    fun commit(context: Context, prepared: PreparedControls) {
        check(DeviceSecurity.hasSecureLock(context)) {
            "Set a secure phone lock before enabling device controls."
        }
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        check(dpm.isDeviceOwnerApp(context.packageName)) {
            "Set Fortress Vault as the device owner before enabling device controls."
        }
        val previous = activeLocks(context)
        synchronized(lock) {
            val merged = previous.associateBy { it.control }.toMutableMap()
            prepared.locks.forEach { (control, value) -> merged[control] = value }
            save(merged.values.toList())
        }
        try {
            check(apply(context)) {
                "Android did not apply the device-control policies. Confirm Fortress is still the device owner and try again."
            }
        } catch (error: Exception) {
            synchronized(lock) { save(previous) }
            try {
                apply(context)
            } catch (rollbackError: Exception) {
                Log.e("ControlManager", "Could not restore device-control policies after a failed lock", rollbackError)
            }
            throw IllegalStateException(
                error.message ?: "Android could not apply the device-control policies.",
                error
            )
        }
        SentinelController.start(context)
    }

    fun unlock(context: Context, control: DeviceControl, phrase: String): Boolean {
        val target = activeLocks(context).firstOrNull { it.control == control } ?: return false
        if (!PhraseHasher.matches(RecoveryPhraseGenerator.normalize(phrase), target.recoverySalt, target.recoveryHash)) return false
        synchronized(lock) { save(activeLocks(context).filterNot { it.control == control }) }
        if (control == DeviceControl.APP_INSTALLS) {
            val packages = prefs.getStringSet(BLOCKED_NEW_PACKAGES, emptySet()).orEmpty()
            PackageFreezer.unfreezeAll(context, packages - VaultManager.blockedPackages(context))
            prefs.edit()
                .remove(BLOCKED_NEW_PACKAGES)
                .remove(INSTALL_BASELINE_PACKAGES)
                .apply()
        }
        apply(context)
        return true
    }

    fun remainingMillis(context: Context, lock: ControlLock): Long =
        (lock.unlockAtMillis - TimeKeeper.estimateCurrentTrustedTimeMillis(context)).coerceAtLeast(0)

    fun isAppChangeBlocked(context: Context): Boolean =
        activeLocks(context).any {
            it.control == DeviceControl.APP_INSTALLS && remainingMillis(context, it) > 0
        }

    fun newlyBlockedPackages(context: Context): Set<String> {
        init(context)
        return prefs.getStringSet(BLOCKED_NEW_PACKAGES, emptySet()).orEmpty()
    }

    fun isInInstallBaseline(context: Context, packageName: String): Boolean {
        init(context)
        return packageName in prefs.getStringSet(INSTALL_BASELINE_PACKAGES, emptySet()).orEmpty()
    }

    fun markUnsealedPackagesAsExisting(context: Context, packages: Set<String>) {
        init(context)
        if (!isAppChangeBlocked(context)) return

        synchronized(lock) {
            val restoredPackages = packages - context.packageName
            if (restoredPackages.isEmpty()) return
            val baseline = prefs.getStringSet(INSTALL_BASELINE_PACKAGES, emptySet()).orEmpty()
            val newlyBlocked = prefs.getStringSet(BLOCKED_NEW_PACKAGES, emptySet()).orEmpty()
            check(
                prefs.edit()
                    .putStringSet(INSTALL_BASELINE_PACKAGES, baseline + restoredPackages)
                    .putStringSet(BLOCKED_NEW_PACKAGES, newlyBlocked - restoredPackages)
                    .commit()
            ) {
                "Could not preserve restored apps while app-install protection is active."
            }
        }
    }

    fun rememberNewlyBlockedPackage(context: Context, packageName: String) {
        synchronized(lock) {
            init(context)
            val packages = prefs.getStringSet(BLOCKED_NEW_PACKAGES, emptySet()).orEmpty() + packageName
            prefs.edit().putStringSet(BLOCKED_NEW_PACKAGES, packages).apply()
        }
    }

    fun apply(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) return false
        val admin = FortressAdminReceiver.getComponentName(context)
        val allLocks = activeLocks(context)
        val wasBlockingAppChanges = allLocks.any { it.control == DeviceControl.APP_INSTALLS }
        val active = allLocks.filter { remainingMillis(context, it) > 0 }
        val appChangeLock = active.firstOrNull { it.control == DeviceControl.APP_INSTALLS }
        val isBlockingAppChanges = appChangeLock != null
        val fullAppChangeBlock = appChangeLock?.fullAppChangeBlock == true
        val controls = active.map { it.control }.toSet()
        val wantsUsbDebugBlock = DeviceControl.USB_DEBUGGING in controls
        val wantsUserAccountsBlock = DeviceControl.USER_ACCOUNTS in controls

        // Compute the desired state and compare with last applied state.
        // If nothing changed, skip all IPC calls entirely.
        val desiredState = AppliedState(wantsUsbDebugBlock, wantsUserAccountsBlock, fullAppChangeBlock, isBlockingAppChanges)
        val prev = lastAppliedState
        val stateChanged = prev != desiredState
        val expiredLocks = active.size != allLocks.size

        if (!stateChanged && !expiredLocks) {
            // No DPM state change needed — skip all IPC calls
            return true
        }

        var policiesApplied = true
        if (!isBlockingAppChanges && !clearProtectedUninstallPackages(context, dpm, admin)) {
            policiesApplied = false
        }
        if (expiredLocks) {
            synchronized(lock) { save(active) }
        }
        if (wasBlockingAppChanges && !isBlockingAppChanges) {
            val packages = prefs.getStringSet(BLOCKED_NEW_PACKAGES, emptySet()).orEmpty()
            PackageFreezer.unfreezeAll(context, packages - VaultManager.blockedPackages(context))
            if (!clearProtectedUninstallPackages(context, dpm, admin)) policiesApplied = false
            prefs.edit()
                .remove(BLOCKED_NEW_PACKAGES)
                .remove(INSTALL_BASELINE_PACKAGES)
                .apply()
        }
        if ((!wasBlockingAppChanges && isBlockingAppChanges) ||
            (isBlockingAppChanges && !prefs.contains(INSTALL_BASELINE_PACKAGES))) {
            val installed = try {
                context.packageManager.getInstalledPackages(
                    android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS
                ).map { it.packageName }.toSet() + VaultManager.blockedPackages(context)
            } catch (error: Exception) {
                policiesApplied = false
                Log.e("ControlManager", "Could not capture the installed-app baseline", error)
                null
            }
            if (installed != null) prefs.edit().putStringSet(INSTALL_BASELINE_PACKAGES, installed).apply()
        }
        policiesApplied = setRestriction(
            dpm, admin, UserManager.DISALLOW_DEBUGGING_FEATURES, wantsUsbDebugBlock
        ) && policiesApplied
        policiesApplied = setRestriction(
            dpm, admin, UserManager.DISALLOW_ADD_USER, wantsUserAccountsBlock
        ) && policiesApplied
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            policiesApplied = setRestriction(
                dpm, admin, UserManager.DISALLOW_USER_SWITCH, wantsUserAccountsBlock
            ) && policiesApplied
        }
        policiesApplied = setRestriction(
            dpm, admin, UserManager.DISALLOW_INSTALL_APPS, fullAppChangeBlock
        ) && policiesApplied
        policiesApplied = setRestriction(
            dpm, admin, UserManager.DISALLOW_UNINSTALL_APPS, fullAppChangeBlock
        ) && policiesApplied
        if (isBlockingAppChanges && !fullAppChangeBlock) {
            if (!protectBaselinePackages(context, dpm, admin)) policiesApplied = false
        }
        if (policiesApplied) lastAppliedState = desiredState
        return policiesApplied
    }

    private fun protectBaselinePackages(
        context: Context,
        dpm: DevicePolicyManager,
        admin: android.content.ComponentName
    ): Boolean {
        val baseline = prefs.getStringSet(INSTALL_BASELINE_PACKAGES, emptySet()).orEmpty()
        val protected = prefs.getStringSet(LEGACY_PROTECTED_UNINSTALL_PACKAGES, emptySet()).orEmpty()
        val packagesToProtect = baseline + protected
        if (packagesToProtect.isEmpty()) return true

        var policiesApplied = true
        packagesToProtect
            .filter { it != context.packageName }
            .forEach { packageName ->
                try {
                    dpm.setUninstallBlocked(admin, packageName, true)
                } catch (error: Exception) {
                    policiesApplied = false
                    Log.e("ControlManager", "Could not block uninstall for $packageName", error)
                }
            }
        prefs.edit().putStringSet(LEGACY_PROTECTED_UNINSTALL_PACKAGES, packagesToProtect).apply()
        return policiesApplied
    }

    private fun clearProtectedUninstallPackages(
        context: Context,
        dpm: DevicePolicyManager,
        admin: android.content.ComponentName
    ): Boolean {
        val protected = prefs.getStringSet(LEGACY_PROTECTED_UNINSTALL_PACKAGES, emptySet()).orEmpty()
        val sealedPackages = VaultManager.blockedPackages(context)
        val failedRestores = mutableSetOf<String>()
        protected
            .filter { it !in sealedPackages && it != context.packageName }
            .forEach { packageName ->
                try {
                    dpm.setUninstallBlocked(admin, packageName, false)
                } catch (error: Exception) {
                    failedRestores += packageName
                    Log.e("ControlManager", "Could not restore uninstall for $packageName", error)
                }
            }
        prefs.edit().apply {
            if (failedRestores.isEmpty()) remove(LEGACY_PROTECTED_UNINSTALL_PACKAGES)
            else putStringSet(LEGACY_PROTECTED_UNINSTALL_PACKAGES, failedRestores)
        }.apply()
        return failedRestores.isEmpty()
    }

    fun enforceNewlyInstalledPackagesIfNeeded(context: Context) {
        init(context)
        val appChangeLock = activeLocks(context)
            .firstOrNull { it.control == DeviceControl.APP_INSTALLS && remainingMillis(context, it) > 0 }
        if (appChangeLock == null || appChangeLock.fullAppChangeBlock) return

        val baseline = prefs.getStringSet(INSTALL_BASELINE_PACKAGES, emptySet()).orEmpty()
        if (baseline.isEmpty()) return
        val sealed = VaultManager.blockedPackages(context)
        val newPackages = try {
            context.packageManager.getInstalledPackages(0)
                .map { it.packageName }
                .filter { it !in baseline && it !in sealed && it != context.packageName }
        } catch (error: Exception) {
            Log.e("ControlManager", "Could not check for newly installed apps", error)
            return
        }

        newPackages.forEach { packageName ->
            PackageFreezer.quarantineNewPackage(context, packageName)
            rememberNewlyBlockedPackage(context, packageName)
            PackageFreezer.uninstallNewPackage(context, packageName)
        }
    }

    private fun setRestriction(
        dpm: DevicePolicyManager,
        admin: android.content.ComponentName,
        restriction: String,
        enabled: Boolean
    ): Boolean {
        return try {
            if (enabled) dpm.addUserRestriction(admin, restriction)
            else dpm.clearUserRestriction(admin, restriction)
            true
        } catch (error: Exception) {
            Log.e("ControlManager", "Could not ${if (enabled) "apply" else "clear"} restriction $restriction", error)
            false
        }
    }

    private fun save(locks: List<ControlLock>) {
        cachedLocks = locks
        // Invalidate applied state so the next apply() call re-pushes DPM state
        lastAppliedState = null
        val root = JSONObject()
        locks.forEach { item ->
            root.put(item.control.key, JSONObject().apply {
                put("unlockAt", item.unlockAtMillis)
                put("salt", item.recoverySalt)
                put("hash", item.recoveryHash)
                put("fullAppChangeBlock", item.fullAppChangeBlock)
            })
        }
        prefs.edit().putString(LOCKS_JSON, root.toString()).apply()
    }
}
