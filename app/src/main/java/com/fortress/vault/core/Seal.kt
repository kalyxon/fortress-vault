package com.fortress.vault.core

import org.json.JSONArray
import org.json.JSONObject

data class Seal(
    val id: String,
    val packages: Set<String>,
    val sealedAtMillis: Long,
    val unlockAtMillis: Long,
    val lastKnownGoodMillis: Long,
    val recoverySalt: String,
    val recoveryHash: String,
    val allowAdb: Boolean = false,
    val failedAttempts: Int = 0,
    val cooldownUntilMillis: Long = 0L,
    /** Epoch-millis of the last time a 24-hour rollback-penalty was applied.
     *  0 means no penalty has ever been applied. Used to prevent stacking. */
    val lastPenaltyAtMillis: Long = 0L,
    /** True while user-switching is restricted for this seal's lifetime. */
    val blockUserSwitch: Boolean = false
)

object SealCodec {

    fun encodeList(seals: List<Seal>): String {
        val array = JSONArray()
        seals.forEach { array.put(encodeOne(it)) }
        return array.toString()
    }

    fun decodeList(json: String?): List<Seal> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { decodeOne(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun encodeOne(seal: Seal): JSONObject = JSONObject().apply {
        put("id", seal.id)
        put("packages", JSONArray(seal.packages.toList()))
        put("sealedAt", seal.sealedAtMillis)
        put("unlockAt", seal.unlockAtMillis)
        put("lastKnownGood", seal.lastKnownGoodMillis)
        put("recoverySalt", seal.recoverySalt)
        put("recoveryHash", seal.recoveryHash)
        put("allowAdb", seal.allowAdb)
        put("failedAttempts", seal.failedAttempts)
        put("cooldownUntil", seal.cooldownUntilMillis)
        put("lastPenaltyAt", seal.lastPenaltyAtMillis)
        put("blockUserSwitch", seal.blockUserSwitch)
    }

    private fun decodeOne(obj: JSONObject): Seal {
        val packagesArray = obj.getJSONArray("packages")
        val packages = (0 until packagesArray.length()).map { packagesArray.getString(it) }.toSet()
        return Seal(
            id = obj.getString("id"),
            packages = packages,
            sealedAtMillis = obj.getLong("sealedAt"),
            unlockAtMillis = obj.getLong("unlockAt"),
            lastKnownGoodMillis = obj.optLong("lastKnownGood", 0L),
            recoverySalt = obj.getString("recoverySalt"),
            recoveryHash = obj.getString("recoveryHash"),
            allowAdb = obj.optBoolean("allowAdb", false),
            failedAttempts = obj.optInt("failedAttempts", 0),
            cooldownUntilMillis = obj.optLong("cooldownUntil", 0L),
            lastPenaltyAtMillis = obj.optLong("lastPenaltyAt", 0L),
            blockUserSwitch = obj.optBoolean("blockUserSwitch", false)
        )
    }
}
