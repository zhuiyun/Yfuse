package com.yfuse.core.security

internal interface CryptoPrimitives {
    fun randomBytes(size: Int): ByteArray

    fun aesGcmEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray

    fun aesGcmDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray

    fun pbkdf2HmacSha256(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
        outputSizeBytes: Int,
    ): ByteArray
}

internal expect fun platformCryptoPrimitives(): CryptoPrimitives

open class SecurityPrimitiveException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** AES-GCM authentication failed: the key/AAD is wrong or the ciphertext was modified. */
class VaultAuthenticationException(
    message: String = "Encrypted value could not be authenticated",
    cause: Throwable? = null,
) : SecurityPrimitiveException(message, cause)
