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
