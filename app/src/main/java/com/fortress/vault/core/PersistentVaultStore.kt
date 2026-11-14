package com.fortress.vault.core

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.fortress.vault.FortressAdminReceiver

object PersistentVaultStore {

    private const val KEY_SEALS_JSON = "p_seals_json"
    private const val KEY_WEB_PROTECTION_LOCKS = "p_web_protection_locks"
    private const val KEY_ADULT_DOMAIN_BLOCKS = "p_adult_domain_blocks"
    private val restrictionsWriteLock = Any()

    fun write(context: Context, seals: List<Seal>) {
        val dpm = deviceOwnerDpm(context)
            ?: throw IllegalStateException("Fortress must be the device owner to persist seal state.")
        val admin = FortressAdminReceiver.getComponentName(context)
        val encoded = SealCodec.encodeList(seals)
        try {
            synchronized(restrictionsWriteLock) {
                val bundle = dpm.getApplicationRestrictions(admin, context.packageName)
                bundle.putString(KEY_SEALS_JSON, encoded)
                dpm.setApplicationRestrictions(admin, context.packageName, bundle)
                val persisted = dpm.getApplicationRestrictions(admin, context.packageName)
                    .getString(KEY_SEALS_JSON)
                check(persisted == encoded) {
                    "Android did not persist the updated active seal state."
                }
            }
        } catch (error: Exception) {
            Log.e("PersistentVaultStore", "Could not persist active seals", error)
            throw IllegalStateException("Could not safely persist active seals.", error)
        }
    }

    fun read(context: Context): List<Seal>? {
        val dpm = deviceOwnerDpm(context) ?: return null
        val admin = FortressAdminReceiver.getComponentName(context)
        val bundle = try {
            dpm.getApplicationRestrictions(admin, context.packageName)
        } catch (error: Exception) {
            Log.e("PersistentVaultStore", "Could not read persistent seals", error)
            return null
        }
        if (bundle.isEmpty) return null
        val json = bundle.getString(KEY_SEALS_JSON) ?: return null
        return SealCodec.decodeList(json)
    }

    fun writeWebProtectionLocks(context: Context, json: String) {
        val dpm = deviceOwnerDpm(context)
            ?: throw IllegalStateException("Fortress must be the device owner to lock web-protection settings.")
        val admin = FortressAdminReceiver.getComponentName(context)
        synchronized(restrictionsWriteLock) {
            val bundle = dpm.getApplicationRestrictions(admin, context.packageName)
            if (json.isBlank()) bundle.remove(KEY_WEB_PROTECTION_LOCKS)
            else bundle.putString(KEY_WEB_PROTECTION_LOCKS, json)
            dpm.setApplicationRestrictions(admin, context.packageName, bundle)
        }
    }

    fun readWebProtectionLocks(context: Context): String? {
        val dpm = deviceOwnerDpm(context) ?: return null
        val admin = FortressAdminReceiver.getComponentName(context)
        return dpm.getApplicationRestrictions(admin, context.packageName)
            .getString(KEY_WEB_PROTECTION_LOCKS)
    }

    fun writeAdultDomainBlocks(context: Context, json: String) {
        val dpm = deviceOwnerDpm(context)
            ?: throw IllegalStateException("Fortress must be the device owner to add Chrome domain blocks.")
        val admin = FortressAdminReceiver.getComponentName(context)
        synchronized(restrictionsWriteLock) {
            val bundle = dpm.getApplicationRestrictions(admin, context.packageName)
            if (json.isBlank()) bundle.remove(KEY_ADULT_DOMAIN_BLOCKS)
            else bundle.putString(KEY_ADULT_DOMAIN_BLOCKS, json)
            dpm.setApplicationRestrictions(admin, context.packageName, bundle)
        }
    }

    fun readAdultDomainBlocks(context: Context): String? {
        val dpm = deviceOwnerDpm(context) ?: return null
        val admin = FortressAdminReceiver.getComponentName(context)
        return dpm.getApplicationRestrictions(admin, context.packageName)
            .getString(KEY_ADULT_DOMAIN_BLOCKS)
    }

    private fun deviceOwnerDpm(context: Context): DevicePolicyManager? {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return if (dpm.isDeviceOwnerApp(context.packageName)) dpm else null
    }
}
