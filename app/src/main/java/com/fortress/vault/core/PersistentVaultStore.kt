package com.fortress.vault.core

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Bundle
import com.fortress.vault.FortressAdminReceiver

object PersistentVaultStore {

    private const val KEY_SEALS_JSON = "p_seals_json"

    fun write(context: Context, seals: List<Seal>) {
        val dpm = deviceOwnerDpm(context) ?: return
        val admin = FortressAdminReceiver.getComponentName(context)
        val bundle = Bundle().apply {
            putString(KEY_SEALS_JSON, SealCodec.encodeList(seals))
        }
        runCatching { dpm.setApplicationRestrictions(admin, context.packageName, bundle) }
    }

    fun read(context: Context): List<Seal>? {
        val dpm = deviceOwnerDpm(context) ?: return null
        val admin = FortressAdminReceiver.getComponentName(context)
        val bundle = runCatching { dpm.getApplicationRestrictions(admin, context.packageName) }
            .getOrNull() ?: return null
        if (bundle.isEmpty) return null
        val json = bundle.getString(KEY_SEALS_JSON) ?: return null
        return SealCodec.decodeList(json)
    }

    private fun deviceOwnerDpm(context: Context): DevicePolicyManager? {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return if (dpm.isDeviceOwnerApp(context.packageName)) dpm else null
    }
}
