package com.example.core

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Cryptographic helper for 4-digit Transaction PIN hashing (salted SHA-256).
 * Plaintext PINs are never persisted in Firestore or local storage.
 */
object PinHasher {
    private const val SALT_BYTES = 16

    fun generateSaltHex(): String {
        val bytes = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hashPin(pin: String, saltHex: String): String {
        require(pin.length == 4 && pin.all { it.isDigit() }) {
            "Transaction PIN must be exactly 4 digits."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val input = "MSAGEER_PIN_V1:$saltHex:$pin".toByteArray(Charsets.UTF_8)
        return digest.digest(input).joinToString("") { "%02x".format(it) }
    }

    fun verifyPin(pin: String, saltHex: String, expectedHashHex: String): Boolean {
        if (pin.length != 4 || !pin.all { it.isDigit() }) return false
        val computed = hashPin(pin, saltHex)
        return MessageDigest.isEqual(
            computed.toByteArray(Charsets.UTF_8),
            expectedHashHex.toByteArray(Charsets.UTF_8)
        )
    }
}
