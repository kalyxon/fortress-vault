package com.fortress.vault.core

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Bundle
import com.fortress.vault.FortressAdminReceiver

object PersistentVaultStore {

    private const val KEY_SEALED = "p_sealed"
    private const val KEY_UNLOCK_TIME = "p_unlock_time_millis"
    private const val KEY_LAST_KNOWN_GOOD_TIME = "p_last_known_good_millis"
    private const val KEY_BLOCKED_PACKAGES = "p_blocked_packages"
    private const val KEY_RECOVERY_SALT = "p_recovery_salt"
    private const val KEY_RECOVERY_HASH = "p_recovery_hash"

    data class PersistedState(
        val sealed: Boolean,
        val unlockTimeMillis: Long,
        val lastKnownGoodMillis: Long,
        val blockedPackages: Set<String>,
        val recoverySalt: String?,
        val recoveryHash: String?
    )

    fun write(context: Context, state: PersistedState) {
        val dpm = deviceOwnerDpm(context) ?: return
        val admin = FortressAdminReceiver.getComponentName(context)
        val bundle = Bundle().apply {
            putBoolean(KEY_SEALED, state.sealed)
            putString(KEY_UNLOCK_TIME, state.unlockTimeMillis.toString())
            putString(KEY_LAST_KNOWN_GOOD_TIME, state.lastKnownGoodMillis.toString())
            putStringArray(KEY_BLOCKED_PACKAGES, state.blockedPackages.toTypedArray())
            putString(KEY_RECOVERY_SALT, state.recoverySalt.orEmpty())
            putString(KEY_RECOVERY_HASH, state.recoveryHash.orEmpty())
        }
        runCatching { dpm.setApplicationRestrictions(admin, context.packageName, bundle) }
    }

    /** Wipes the persisted bundle — call this only from a legitimate unseal(). */
    fun clear(context: Context) {
        val dpm = deviceOwnerDpm(context) ?: return
        val admin = FortressAdminReceiver.getComponentName(context)
        runCatching { dpm.setApplicationRestrictions(admin, context.packageName, Bundle()) }
    }

    fun read(context: Context): PersistedState? {
        val dpm = deviceOwnerDpm(context) ?: return null
        val admin = FortressAdminReceiver.getComponentName(context)
        val bundle = runCatching { dpm.getApplicationRestrictions(admin, context.packageName) }
            .getOrNull() ?: return null
        if (bundle.isEmpty) return null

        return PersistedState(
            sealed = bundle.getBoolean(KEY_SEALED, false),
            unlockTimeMillis = bundle.getString(KEY_UNLOCK_TIME)?.toLongOrNull() ?: 0L,
            lastKnownGoodMillis = bundle.getString(KEY_LAST_KNOWN_GOOD_TIME)?.toLongOrNull() ?: 0L,
            blockedPackages = bundle.getStringArray(KEY_BLOCKED_PACKAGES)?.toSet() ?: emptySet(),
            recoverySalt = bundle.getString(KEY_RECOVERY_SALT)?.ifEmpty { null },
            recoveryHash = bundle.getString(KEY_RECOVERY_HASH)?.ifEmpty { null }
        )
    }

    private fun deviceOwnerDpm(context: Context): DevicePolicyManager? {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return if (dpm.isDeviceOwnerApp(context.packageName)) dpm else null
    }
}
