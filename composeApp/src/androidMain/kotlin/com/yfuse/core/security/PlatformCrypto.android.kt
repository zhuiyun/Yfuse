package com.yfuse.core.security

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal actual fun platformCryptoPrimitives(): CryptoPrimitives = AndroidCryptoPrimitives

private object AndroidCryptoPrimitives : CryptoPrimitives {
    private val secureRandom = SecureRandom()

    override fun randomBytes(size: Int): ByteArray {
        require(size > 0) { "Random-byte count must be positive" }
        return ByteArray(size).also(secureRandom::nextBytes)
    }

    override fun aesGcmEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray =
        try {
            aesCipher(Cipher.ENCRYPT_MODE, key, nonce).run {
                if (aad.isNotEmpty()) updateAAD(aad)
                doFinal(plaintext)
            }
        } catch (error: GeneralSecurityException) {
            throw SecurityPrimitiveException("AES-GCM encryption failed", error)
        }

    override fun aesGcmDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray =
        try {
            aesCipher(Cipher.DECRYPT_MODE, key, nonce).run {
                if (aad.isNotEmpty()) updateAAD(aad)
                doFinal(ciphertext)
            }
        } catch (error: AEADBadTagException) {
            throw VaultAuthenticationException(cause = error)
        } catch (error: BadPaddingException) {
            // Some Android providers report a failed GCM tag as BadPaddingException.
            throw VaultAuthenticationException(cause = error)
        } catch (error: GeneralSecurityException) {
            throw SecurityPrimitiveException("AES-GCM decryption failed", error)
        }

    override fun pbkdf2HmacSha256(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
        outputSizeBytes: Int,
    ): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, outputSizeBytes * Byte.SIZE_BITS)
        return try {
            SecretKeyFactory
                .getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } catch (error: GeneralSecurityException) {
            throw SecurityPrimitiveException("PBKDF2-HMAC-SHA256 derivation failed", error)
        } finally {
            spec.clearPassword()
        }
    }

    private fun aesCipher(
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
    ): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                mode,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(VaultCrypto.GCM_TAG_SIZE_BYTES * Byte.SIZE_BITS, nonce),
            )
        }
}
