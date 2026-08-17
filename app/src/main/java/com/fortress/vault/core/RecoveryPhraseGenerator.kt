package com.fortress.vault.core

import java.security.MessageDigest
import java.security.SecureRandom

object RecoveryPhraseGenerator {

    private const val ENTROPY_BITS = 128
    private const val CHECKSUM_BITS = ENTROPY_BITS / 32 // = 4
    private const val WORD_COUNT = (ENTROPY_BITS + CHECKSUM_BITS) / 11 // = 12

    fun generate(): String {
        val entropy = ByteArray(ENTROPY_BITS / 8)
        SecureRandom().nextBytes(entropy)
        return entropyToPhrase(entropy)
    }

    fun isValidPhrase(phrase: String): Boolean {
        val words = normalize(phrase).split(" ")
        if (words.size != WORD_COUNT) return false
        val indices = words.map { BIP39_ENGLISH_WORDLIST.indexOf(it) }
        if (indices.any { it == -1 }) return false

        val bits = indices.joinToString("") { it.toString(2).padStart(11, '0') }
        val entropyBits = bits.substring(0, ENTROPY_BITS)
        val checksumBits = bits.substring(ENTROPY_BITS)

        val entropyBytes = bitsToBytes(entropyBits)
        val expectedChecksum = sha256(entropyBytes)
            .let { hash -> (hash[0].toInt() and 0xFF).toString(2).padStart(8, '0') }
            .substring(0, CHECKSUM_BITS)

        return checksumBits == expectedChecksum
    }

    private fun entropyToPhrase(entropy: ByteArray): String {
        val entropyBits = entropy.joinToString("") {
            (it.toInt() and 0xFF).toString(2).padStart(8, '0')
        }
        val checksumByte = sha256(entropy)[0]
        val checksumBits = (checksumByte.toInt() and 0xFF).toString(2)
            .padStart(8, '0').substring(0, CHECKSUM_BITS)

        val allBits = entropyBits + checksumBits
        return allBits.chunked(11)
            .map { BIP39_ENGLISH_WORDLIST[it.toInt(2)] }
            .joinToString(" ")
    }

    private fun bitsToBytes(bits: String): ByteArray =
        bits.chunked(8).map { it.toInt(2).toByte() }.toByteArray()

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    fun normalize(phrase: String): String =
        phrase.trim().lowercase().replace(Regex("\\s+"), " ")
}
