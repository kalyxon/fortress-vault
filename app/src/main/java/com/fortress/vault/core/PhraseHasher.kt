package com.fortress.vault.core

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * We never store the recovery phrase itself — only a salted PBKDF2 hash.
 */
object PhraseHasher {

    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256

    data class HashResult(val saltHex: String, val hashHex: String)

    fun hash(phrase: String): HashResult {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(phrase, salt)
        return HashResult(salt.toHex(), hash.toHex())
    }

    fun matches(phrase: String, saltHex: String, expectedHashHex: String): Boolean {
        val salt = saltHex.fromHex()
        val computed = pbkdf2(phrase, salt).toHex()
        return constantTimeEquals(computed, expectedHashHex)
    }

    private fun pbkdf2(phrase: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(phrase.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.fromHex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
