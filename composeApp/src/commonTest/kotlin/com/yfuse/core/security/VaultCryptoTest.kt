package com.yfuse.core.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultCryptoTest {
    private val crypto = VaultCrypto()

    @Test
    fun aes256GcmRoundTripsAndUsesFreshNonces() {
        val key = crypto.generateVaultKey()
        val plaintext = "server-token-令牌".encodeToByteArray()
        val aad = "user-42".encodeToByteArray()

        val first = crypto.encrypt(key, plaintext, aad)
        val second = crypto.encrypt(key, plaintext, aad)

        assertTrue(plaintext.contentEquals(crypto.decrypt(key, first, aad)))
        assertTrue(plaintext.contentEquals(crypto.decrypt(key, second, aad)))
        assertFalse(first.nonce.contentEquals(second.nonce))
        assertEquals(VaultCrypto.GCM_NONCE_SIZE_BYTES, first.nonce.size)
        key.fill(0)
    }

    @Test
    fun aes256GcmRejectsTamperingAndWrongAad() {
        val key = crypto.generateVaultKey()
        val payload = crypto.encrypt(key, "secret".encodeToByteArray(), "correct".encodeToByteArray())
        val tamperedBytes =
            payload.ciphertext.apply {
                this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte()
            }

        assertFailsWith<VaultAuthenticationException> {
            crypto.decrypt(
                key,
                AesGcmPayload(payload.nonce, tamperedBytes),
                "correct".encodeToByteArray(),
            )
        }
        assertFailsWith<VaultAuthenticationException> {
            crypto.decrypt(key, payload, "wrong".encodeToByteArray())
        }
        key.fill(0)
    }

    @Test
    fun recoveryEnvelopeRoundTripsVaultKey() {
        val vaultKey = crypto.generateVaultKey()
        val passphrase = "correct horse battery staple".toCharArray()
        val envelope =
            crypto.wrapVaultKey(
                vaultKey = vaultKey,
                passphrase = passphrase,
                aad = "account-7".encodeToByteArray(),
                iterations = VaultCrypto.MIN_PBKDF2_ITERATIONS,
            )

        val recovered =
            crypto.unwrapVaultKey(
                envelope = envelope,
                passphrase = passphrase,
                aad = "account-7".encodeToByteArray(),
            )

        assertTrue(vaultKey.contentEquals(recovered))
        assertEquals(VaultCrypto.RECOVERY_SALT_SIZE_BYTES, envelope.salt.size)
        assertEquals(VaultCrypto.GCM_NONCE_SIZE_BYTES, envelope.wrappedKey.nonce.size)
        recovered.fill(0)
        vaultKey.fill(0)
        passphrase.fill('\u0000')
    }

    @Test
    fun recoveryEnvelopeBindsPassphraseAadSaltAndIterations() {
        val vaultKey = crypto.generateVaultKey()
        val passphrase = "correct horse battery staple".toCharArray()
        val envelope =
            crypto.wrapVaultKey(
                vaultKey = vaultKey,
                passphrase = passphrase,
                aad = "account-7".encodeToByteArray(),
                iterations = VaultCrypto.MIN_PBKDF2_ITERATIONS,
            )

        assertFailsWith<VaultAuthenticationException> {
            crypto.unwrapVaultKey(envelope, "wrong passphrase".toCharArray(), "account-7".encodeToByteArray())
        }
        assertFailsWith<VaultAuthenticationException> {
            crypto.unwrapVaultKey(envelope, passphrase, "account-8".encodeToByteArray())
        }

        val changedSalt = envelope.salt.apply { this[0] = (this[0].toInt() xor 1).toByte() }
        assertFailsWith<VaultAuthenticationException> {
            crypto.unwrapVaultKey(
                RecoveryKeyEnvelope(
                    version = envelope.version,
                    salt = changedSalt,
                    iterations = envelope.iterations,
                    wrappedKey = envelope.wrappedKey,
                ),
                passphrase,
                "account-7".encodeToByteArray(),
            )
        }

        assertFailsWith<VaultAuthenticationException> {
            crypto.unwrapVaultKey(
                RecoveryKeyEnvelope(
                    version = envelope.version,
                    salt = envelope.salt,
                    iterations = envelope.iterations + 1,
                    wrappedKey = envelope.wrappedKey,
                ),
                passphrase,
                "account-7".encodeToByteArray(),
            )
        }
        vaultKey.fill(0)
        passphrase.fill('\u0000')
    }

    @Test
    fun hmacSha256MatchesRfc4231() {
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            crypto.hmacSha256(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray()).hex(),
        )
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            crypto.hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray()).hex(),
        )
        assertEquals(
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            crypto
                .hmacSha256(
                    ByteArray(131) { 0xaa.toByte() },
                    "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray(),
                ).hex(),
        )
    }

    @Test
    fun hkdfExpandMatchesRfc5869() {
        val prk = "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5".unhex()
        val info = "f0f1f2f3f4f5f6f7f8f9".unhex()

        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            crypto.hkdfExpandSha256(prk, info, 42).hex(),
        )
    }

    @Test
    fun accountKeysSplitOnePasswordIntoUnrelatedKeysThatOpenTheVault() {
        val salt = ByteArray(VaultCrypto.RECOVERY_SALT_SIZE_BYTES) { it.toByte() }
        val keys = crypto.deriveAccountKeys("correct horse".toCharArray(), salt, VaultCrypto.MIN_PBKDF2_ITERATIONS)
        val again = crypto.deriveAccountKeys("correct horse".toCharArray(), salt, VaultCrypto.MIN_PBKDF2_ITERATIONS)
        val otherSalt =
            crypto.deriveAccountKeys(
                "correct horse".toCharArray(),
                ByteArray(VaultCrypto.RECOVERY_SALT_SIZE_BYTES) { 9 },
                VaultCrypto.MIN_PBKDF2_ITERATIONS,
            )

        assertTrue(keys.authKey.contentEquals(again.authKey))
        assertTrue(keys.wrapKey.contentEquals(again.wrapKey))
        assertFalse(keys.authKey.contentEquals(keys.wrapKey))
        assertFalse(keys.authKey.contentEquals(otherSalt.authKey))

        val vaultKey = crypto.generateVaultKey()
        val wrapped = crypto.wrapAccountVaultKey(vaultKey, keys.wrapKey, keyVersion = 3)
        assertTrue(vaultKey.contentEquals(crypto.unwrapAccountVaultKey(wrapped, again.wrapKey, keyVersion = 3)))
        assertFailsWith<VaultAuthenticationException> {
            crypto.unwrapAccountVaultKey(wrapped, keys.wrapKey, keyVersion = 2)
        }
        assertFailsWith<VaultAuthenticationException> {
            crypto.unwrapAccountVaultKey(wrapped, otherSalt.wrapKey, keyVersion = 3)
        }
        listOf(keys, again, otherSalt).forEach(AccountKeys::wipe)
        assertTrue(keys.authKey.all { it == 0.toByte() })
    }

    @Test
    fun rejectsUnsafeParameterSizesBeforeCallingPlatformCrypto() {
        assertFailsWith<IllegalArgumentException> {
            crypto.encrypt(ByteArray(VaultCrypto.AES_KEY_SIZE_BYTES - 1), byteArrayOf(1))
        }
        assertFailsWith<IllegalArgumentException> {
            crypto.deriveRecoveryKey(
                passphrase = charArrayOf(),
                salt = ByteArray(VaultCrypto.RECOVERY_SALT_SIZE_BYTES),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            crypto.deriveRecoveryKey(
                passphrase = "passphrase".toCharArray(),
                salt = ByteArray(RecoveryKeyEnvelope.MIN_SALT_SIZE_BYTES - 1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            crypto.deriveRecoveryKey(
                passphrase = "passphrase".toCharArray(),
                salt = ByteArray(VaultCrypto.RECOVERY_SALT_SIZE_BYTES),
                iterations = VaultCrypto.MIN_PBKDF2_ITERATIONS - 1,
            )
        }
    }
}

private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

private fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
