package com.fortress.vault.core

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.UserManager
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
    private val lock = Any()
    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        synchronized(lock) {
            if (::prefs.isInitialized) return
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            prefs = EncryptedSharedPreferences.create(
                context, PREFS_NAME, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    fun activeLocks(context: Context): List<ControlLock> = synchronized(lock) {
        init(context)
        val json = prefs.getString(LOCKS_JSON, null) ?: return emptyList()
        runCatching {
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
        synchronized(lock) {
            val merged = activeLocks(context).associateBy { it.control }.toMutableMap()
            prepared.locks.forEach { (control, value) -> merged[control] = value }
            save(merged.values.toList())
        }
        apply(context)
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

    fun rememberNewlyBlockedPackage(context: Context, packageName: String) {
        synchronized(lock) {
            init(context)
            val packages = prefs.getStringSet(BLOCKED_NEW_PACKAGES, emptySet()).orEmpty() + packageName
            prefs.edit().putStringSet(BLOCKED_NEW_PACKAGES, packages).apply()
        }
    }

    fun apply(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val admin = FortressAdminReceiver.getComponentName(context)
        val allLocks = activeLocks(context)
        val wasBlockingAppChanges = allLocks.any { it.control == DeviceControl.APP_INSTALLS }
        val active = allLocks.filter { remainingMillis(context, it) > 0 }
        val appChangeLock = active.firstOrNull { it.control == DeviceControl.APP_INSTALLS }
        val isBlockingAppChanges = appChangeLock != null
        val fullAppChangeBlock = appChangeLock?.fullAppChangeBlock == true
        if (active.size != allLocks.size) {
            synchronized(lock) { save(active) }
        }
        if (wasBlockingAppChanges && !isBlockingAppChanges) {
            val packages = prefs.getStringSet(BLOCKED_NEW_PACKAGES, emptySet()).orEmpty()
            PackageFreezer.unfreezeAll(context, packages - VaultManager.blockedPackages(context))
            prefs.edit()
                .remove(BLOCKED_NEW_PACKAGES)
                .remove(INSTALL_BASELINE_PACKAGES)
                .apply()
        }
        if ((!wasBlockingAppChanges && isBlockingAppChanges) ||
            (isBlockingAppChanges && !prefs.contains(INSTALL_BASELINE_PACKAGES))) {
            val installed = runCatching {
                context.packageManager.getInstalledPackages(0).map { it.packageName }.toSet()
            }.getOrDefault(emptySet())
            prefs.edit().putStringSet(INSTALL_BASELINE_PACKAGES, installed).apply()
        }
        if (isBlockingAppChanges && !fullAppChangeBlock) {
            enforceNewlyInstalledPackagesIfNeeded(context)
        }
        val controls = active.map { it.control }.toSet()
        setRestriction(dpm, admin, UserManager.DISALLOW_DEBUGGING_FEATURES, DeviceControl.USB_DEBUGGING in controls)
        setRestriction(dpm, admin, UserManager.DISALLOW_ADD_USER, DeviceControl.USER_ACCOUNTS in controls)
        setRestriction(dpm, admin, UserManager.DISALLOW_USER_SWITCH, DeviceControl.USER_ACCOUNTS in controls)
        setRestriction(dpm, admin, UserManager.DISALLOW_INSTALL_APPS, fullAppChangeBlock)
        setRestriction(dpm, admin, UserManager.DISALLOW_UNINSTALL_APPS, fullAppChangeBlock)
    }

    fun enforceNewlyInstalledPackagesIfNeeded(context: Context) {
        init(context)
        val appChangeLock = activeLocks(context)
            .firstOrNull { it.control == DeviceControl.APP_INSTALLS && remainingMillis(context, it) > 0 }
        if (appChangeLock == null || appChangeLock.fullAppChangeBlock) return

        val baseline = prefs.getStringSet(INSTALL_BASELINE_PACKAGES, emptySet()).orEmpty()
        if (baseline.isEmpty()) return
        val sealed = VaultManager.blockedPackages(context)
        val newPackages = runCatching {
            context.packageManager.getInstalledPackages(0)
                .map { it.packageName }
                .filter { it !in baseline && it !in sealed && it != context.packageName }
        }.getOrDefault(emptyList())

        newPackages.forEach { packageName ->
            PackageFreezer.quarantineNewPackage(context, packageName)
            PackageFreezer.uninstallNewPackage(context, packageName)
        }
    }

    private fun setRestriction(dpm: DevicePolicyManager, admin: android.content.ComponentName, restriction: String, enabled: Boolean) {
        if (enabled) dpm.addUserRestriction(admin, restriction) else dpm.clearUserRestriction(admin, restriction)
    }

    private fun save(locks: List<ControlLock>) {
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
