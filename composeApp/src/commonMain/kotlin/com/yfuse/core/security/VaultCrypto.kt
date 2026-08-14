package com.yfuse.core.security

/** Immutable AES-256-GCM result. The ciphertext includes the 128-bit authentication tag. */
class AesGcmPayload(
    nonce: ByteArray,
    ciphertext: ByteArray,
) {
    private val nonceBytes = nonce.copyOf()
    private val ciphertextBytes = ciphertext.copyOf()

    init {
        require(nonceBytes.size == VaultCrypto.GCM_NONCE_SIZE_BYTES) {
            "AES-GCM nonce must be ${VaultCrypto.GCM_NONCE_SIZE_BYTES} bytes"
        }
        require(ciphertextBytes.size >= VaultCrypto.GCM_TAG_SIZE_BYTES) {
            "AES-GCM ciphertext must include a ${VaultCrypto.GCM_TAG_SIZE_BYTES}-byte tag"
        }
    }

    val nonce: ByteArray
        get() = nonceBytes.copyOf()

    val ciphertext: ByteArray
        get() = ciphertextBytes.copyOf()

    internal fun nonceForCrypto(): ByteArray = nonceBytes.copyOf()

    internal fun ciphertextForCrypto(): ByteArray = ciphertextBytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is AesGcmPayload &&
            nonceBytes.contentEquals(other.nonceBytes) &&
            ciphertextBytes.contentEquals(other.ciphertextBytes)

    override fun hashCode(): Int = 31 * nonceBytes.contentHashCode() + ciphertextBytes.contentHashCode()

    override fun toString(): String =
        "AesGcmPayload(nonce=<${nonceBytes.size} bytes>, ciphertext=<${ciphertextBytes.size} bytes>)"
}

/** Parameters and ciphertext required to recover a vault key from a passphrase. */
class RecoveryKeyEnvelope(
    val version: Int,
    salt: ByteArray,
    val iterations: Int,
    val wrappedKey: AesGcmPayload,
) {
    private val saltBytes = salt.copyOf()

    init {
        require(version == CURRENT_VERSION) { "Unsupported recovery envelope version: $version" }
        require(saltBytes.size in MIN_SALT_SIZE_BYTES..MAX_SALT_SIZE_BYTES) {
            "Recovery salt must be $MIN_SALT_SIZE_BYTES..$MAX_SALT_SIZE_BYTES bytes"
        }
        require(iterations in VaultCrypto.MIN_PBKDF2_ITERATIONS..VaultCrypto.MAX_PBKDF2_ITERATIONS) {
            "PBKDF2 iterations must be ${VaultCrypto.MIN_PBKDF2_ITERATIONS}..${VaultCrypto.MAX_PBKDF2_ITERATIONS}"
        }
    }

    val salt: ByteArray
        get() = saltBytes.copyOf()

    internal fun saltForCrypto(): ByteArray = saltBytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is RecoveryKeyEnvelope &&
            version == other.version &&
            iterations == other.iterations &&
            saltBytes.contentEquals(other.saltBytes) &&
            wrappedKey == other.wrappedKey

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + saltBytes.contentHashCode()
        result = 31 * result + iterations
        result = 31 * result + wrappedKey.hashCode()
        return result
    }

    override fun toString(): String =
        "RecoveryKeyEnvelope(version=$version, salt=<${saltBytes.size} bytes>, " +
            "iterations=$iterations, wrappedKey=$wrappedKey)"

    companion object {
        const val CURRENT_VERSION = 1
        const val MIN_SALT_SIZE_BYTES = 16
        const val MAX_SALT_SIZE_BYTES = 64
    }
}

/**
 * Cryptographic operations for the sync vault.
 *
 * The caller owns returned byte arrays and should overwrite raw key material after use. Passphrases
 * are never converted to immutable Strings and the platform PBKDF2 implementation clears its copy.
 */
class VaultCrypto internal constructor(
    private val primitives: CryptoPrimitives,
) {
    constructor() : this(platformCryptoPrimitives())

    fun generateVaultKey(): ByteArray = primitives.randomBytes(AES_KEY_SIZE_BYTES)

    fun sha256(value: ByteArray): ByteArray = primitives.sha256(value)

    fun encrypt(
        key: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = byteArrayOf(),
    ): AesGcmPayload {
        requireAesKey(key)
        val nonce = primitives.randomBytes(GCM_NONCE_SIZE_BYTES)
        return AesGcmPayload(
            nonce = nonce,
            ciphertext = primitives.aesGcmEncrypt(
                key = key,
                nonce = nonce,
                plaintext = plaintext,
                aad = aad,
            ),
        )
    }

    fun decrypt(
        key: ByteArray,
        payload: AesGcmPayload,
        aad: ByteArray = byteArrayOf(),
    ): ByteArray {
        requireAesKey(key)
        return primitives.aesGcmDecrypt(
            key = key,
            nonce = payload.nonceForCrypto(),
            ciphertext = payload.ciphertextForCrypto(),
            aad = aad,
        )
    }

    /**
     * Derives a 256-bit recovery key with PBKDF2-HMAC-SHA256.
     *
     * The returned array is sensitive and should be overwritten with zeros as soon as possible.
     */
    fun deriveRecoveryKey(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int = DEFAULT_PBKDF2_ITERATIONS,
    ): ByteArray {
        require(passphrase.isNotEmpty()) { "Recovery passphrase must not be empty" }
        require(salt.size in RecoveryKeyEnvelope.MIN_SALT_SIZE_BYTES..RecoveryKeyEnvelope.MAX_SALT_SIZE_BYTES) {
            "Recovery salt must be ${RecoveryKeyEnvelope.MIN_SALT_SIZE_BYTES}.." +
                "${RecoveryKeyEnvelope.MAX_SALT_SIZE_BYTES} bytes"
        }
        require(iterations in MIN_PBKDF2_ITERATIONS..MAX_PBKDF2_ITERATIONS) {
            "PBKDF2 iterations must be $MIN_PBKDF2_ITERATIONS..$MAX_PBKDF2_ITERATIONS"
        }
        return primitives.pbkdf2HmacSha256(
            passphrase = passphrase,
            salt = salt,
            iterations = iterations,
            outputSizeBytes = AES_KEY_SIZE_BYTES,
        )
    }

    fun wrapVaultKey(
        vaultKey: ByteArray,
        passphrase: CharArray,
        aad: ByteArray = byteArrayOf(),
        iterations: Int = DEFAULT_PBKDF2_ITERATIONS,
    ): RecoveryKeyEnvelope {
        requireAesKey(vaultKey)
        val salt = primitives.randomBytes(RECOVERY_SALT_SIZE_BYTES)
        val wrappingKey = deriveRecoveryKey(passphrase, salt, iterations)
        return try {
            RecoveryKeyEnvelope(
                version = RecoveryKeyEnvelope.CURRENT_VERSION,
                salt = salt,
                iterations = iterations,
                wrappedKey = encrypt(
                    key = wrappingKey,
                    plaintext = vaultKey,
                    aad = recoveryAad(
                        version = RecoveryKeyEnvelope.CURRENT_VERSION,
                        salt = salt,
                        iterations = iterations,
                        callerAad = aad,
                    ),
                ),
            )
        } finally {
            wrappingKey.fill(0)
        }
    }

    fun unwrapVaultKey(
        envelope: RecoveryKeyEnvelope,
        passphrase: CharArray,
        aad: ByteArray = byteArrayOf(),
    ): ByteArray {
        val salt = envelope.saltForCrypto()
        val wrappingKey = deriveRecoveryKey(passphrase, salt, envelope.iterations)
        return try {
            decrypt(
                key = wrappingKey,
                payload = envelope.wrappedKey,
                aad = recoveryAad(
                    version = envelope.version,
                    salt = salt,
                    iterations = envelope.iterations,
                    callerAad = aad,
                ),
            ).also(::requireAesKey)
        } finally {
            wrappingKey.fill(0)
        }
    }

    private fun requireAesKey(key: ByteArray) {
        require(key.size == AES_KEY_SIZE_BYTES) {
            "AES-256 key must be $AES_KEY_SIZE_BYTES bytes"
        }
    }

    private fun recoveryAad(
        version: Int,
        salt: ByteArray,
        iterations: Int,
        callerAad: ByteArray,
    ): ByteArray {
        val result = ByteArray(
            RECOVERY_AAD_PREFIX.size +
                1 +
                Int.SIZE_BYTES +
                1 +
                salt.size +
                callerAad.size,
        )
        var offset = 0
        RECOVERY_AAD_PREFIX.copyInto(result, destinationOffset = offset)
        offset += RECOVERY_AAD_PREFIX.size
        result[offset++] = version.toByte()
        result[offset++] = (iterations ushr 24).toByte()
        result[offset++] = (iterations ushr 16).toByte()
        result[offset++] = (iterations ushr 8).toByte()
        result[offset++] = iterations.toByte()
        result[offset++] = salt.size.toByte()
        salt.copyInto(result, destinationOffset = offset)
        offset += salt.size
        callerAad.copyInto(result, destinationOffset = offset)
        return result
    }

    companion object {
        const val AES_KEY_SIZE_BYTES = 32
        const val GCM_NONCE_SIZE_BYTES = 12
        const val GCM_TAG_SIZE_BYTES = 16
        const val RECOVERY_SALT_SIZE_BYTES = 16
        const val DEFAULT_PBKDF2_ITERATIONS = 600_000
        const val MIN_PBKDF2_ITERATIONS = 100_000
        const val MAX_PBKDF2_ITERATIONS = 2_000_000

        private val RECOVERY_AAD_PREFIX = "yfuse-recovery-key-v1".encodeToByteArray()
    }
}
