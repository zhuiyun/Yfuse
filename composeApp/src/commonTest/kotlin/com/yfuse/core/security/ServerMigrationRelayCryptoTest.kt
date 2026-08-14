package com.yfuse.core.security

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServerMigrationRelayCryptoTest {
    @Test
    fun relayEnvelopeRoundTripsWithRandomSecret() {
        val crypto = ServerMigrationRelayCrypto(VaultCrypto(RelayTestCryptoPrimitives()))
        val plaintext = "credential-bearing-backup".encodeToByteArray()
        val migration = crypto.protect(plaintext, 1_000, 1_900)
        try {
            assertTrue(crypto.isRelayEnvelope(migration.envelope))
            assertEquals(migration.relayId, crypto.inspect(migration.envelope).relayId)
            assertContentEquals(plaintext, crypto.unprotect(migration.envelope, migration.transferSecret, 1_001))
        } finally {
            migration.clearSecret()
            plaintext.fill(0)
        }
    }

    @Test
    fun expiredEnvelopeAndWrongSecretFailClosed() {
        val crypto = ServerMigrationRelayCrypto(VaultCrypto(RelayTestCryptoPrimitives()))
        val migration = crypto.protect(byteArrayOf(1, 2, 3), 1_000, 1_900)
        try {
            assertFailsWith<IllegalArgumentException> {
                crypto.unprotect(migration.envelope, ByteArray(32), 1_001)
            }
            assertFailsWith<IllegalArgumentException> {
                crypto.unprotect(migration.envelope, migration.transferSecret, 1_901)
            }
        } finally {
            migration.clearSecret()
        }
    }
}

private class RelayTestCryptoPrimitives : CryptoPrimitives {
    private var randomByte = 1

    override fun randomBytes(size: Int): ByteArray = ByteArray(size) { randomByte++.toByte() }

    override fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

    override fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
        platformTestCipher(javax.crypto.Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext)

    override fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray =
        platformTestCipher(javax.crypto.Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(ciphertext)

    override fun pbkdf2HmacSha256(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
        outputSizeBytes: Int,
    ): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(passphrase, salt, iterations, outputSizeBytes * 8)
        return try {
            javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun platformTestCipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray) =
        javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce))
            updateAAD(aad)
        }
}
