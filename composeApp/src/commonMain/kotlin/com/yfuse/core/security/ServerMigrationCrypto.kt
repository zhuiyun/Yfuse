package com.yfuse.core.security

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Creates short-lived, passphrase-protected server migration packages.
 *
 * The package header is authenticated as AES-GCM AAD, so expiry, KDF parameters, and the random
 * salt cannot be changed without invalidating the package. Plaintext and derived keys are wiped at
 * the byte-array boundary; callers should also clear the [CharArray] they pass in.
 */
class ServerMigrationCrypto(
    private val crypto: VaultCrypto = VaultCrypto(),
) {
    private val json =
        Json {
            ignoreUnknownKeys = false
            explicitNulls = false
        }

    fun protect(
        plaintext: ByteArray,
        passphrase: CharArray,
        createdAtEpochSeconds: Long,
        expiresAtEpochSeconds: Long,
    ): String {
        requireStrongPassphrase(passphrase)
        require(plaintext.isNotEmpty() && plaintext.size <= MAX_PLAINTEXT_SIZE_BYTES) {
            "迁移数据大小无效"
        }
        require(createdAtEpochSeconds >= 0) { "迁移包创建时间无效" }
        require(expiresAtEpochSeconds > createdAtEpochSeconds) { "迁移包过期时间无效" }
        require(expiresAtEpochSeconds - createdAtEpochSeconds <= MAX_TTL_SECONDS) {
            "迁移包有效期不能超过 ${MAX_TTL_SECONDS / 3_600} 小时"
        }

        val random = crypto.generateVaultKey()
        val salt = random.copyOf(RecoveryKeyEnvelope.MIN_SALT_SIZE_BYTES)
        random.fill(0)
        val iterations = VaultCrypto.DEFAULT_PBKDF2_ITERATIONS
        val key = crypto.deriveRecoveryKey(passphrase, salt, iterations)
        return try {
            val encrypted =
                crypto.encrypt(
                    key = key,
                    plaintext = plaintext,
                    aad =
                        packageAad(
                            iterations = iterations,
                            createdAtEpochSeconds = createdAtEpochSeconds,
                            expiresAtEpochSeconds = expiresAtEpochSeconds,
                            salt = salt,
                        ),
                )
            json.encodeToString(
                ProtectedServerMigrationPackage.serializer(),
                ProtectedServerMigrationPackage(
                    type = PACKAGE_TYPE,
                    version = CURRENT_VERSION,
                    algorithm = ALGORITHM,
                    kdf = KDF,
                    iterations = iterations,
                    createdAtEpochSeconds = createdAtEpochSeconds,
                    expiresAtEpochSeconds = expiresAtEpochSeconds,
                    salt = salt.toBase64Url(),
                    nonce = encrypted.nonce.toBase64Url(),
                    ciphertext = encrypted.ciphertext.toBase64Url(),
                ),
            )
        } finally {
            key.fill(0)
            salt.fill(0)
        }
    }

    fun unprotect(
        encoded: String,
        passphrase: CharArray,
        nowEpochSeconds: Long,
    ): ByteArray {
        requireStrongPassphrase(passphrase)
        require(nowEpochSeconds >= 0) { "当前时间无效" }
        val trimmed = encoded.trim()
        require(trimmed.length in 1..MAX_ENCODED_SIZE_CHARS) { "迁移包大小无效" }
        val envelope =
            try {
                json.decodeFromString(ProtectedServerMigrationPackage.serializer(), trimmed)
            } catch (error: Exception) {
                throw IllegalArgumentException(
                    "不支持明文或旧版迁移包，请在原设备上重新生成受保护迁移包",
                    error,
                )
            }
        require(envelope.type == PACKAGE_TYPE && envelope.version == CURRENT_VERSION) {
            "不支持的受保护迁移包版本"
        }
        require(envelope.algorithm == ALGORITHM && envelope.kdf == KDF) {
            "不支持的迁移包加密算法"
        }
        require(envelope.iterations == VaultCrypto.DEFAULT_PBKDF2_ITERATIONS) {
            "迁移包密钥派生参数无效"
        }
        require(
            envelope.createdAtEpochSeconds >= 0 &&
                envelope.expiresAtEpochSeconds > envelope.createdAtEpochSeconds &&
                envelope.expiresAtEpochSeconds - envelope.createdAtEpochSeconds <= MAX_TTL_SECONDS,
        ) { "迁移包有效期无效" }
        require(envelope.createdAtEpochSeconds <= nowEpochSeconds + ALLOWED_CLOCK_SKEW_SECONDS) {
            "迁移包创建时间晚于当前设备时间"
        }
        require(nowEpochSeconds <= envelope.expiresAtEpochSeconds) {
            "迁移包已过期，请在原设备上重新生成"
        }

        val salt: ByteArray
        val nonce: ByteArray
        val ciphertext: ByteArray
        try {
            salt = envelope.salt.base64UrlToBytes()
            nonce = envelope.nonce.base64UrlToBytes()
            ciphertext = envelope.ciphertext.base64UrlToBytes()
        } catch (error: Exception) {
            throw IllegalArgumentException("迁移包编码已损坏", error)
        }
        require(salt.size == VaultCrypto.RECOVERY_SALT_SIZE_BYTES) {
            "迁移包盐值无效"
        }
        require(nonce.size == VaultCrypto.GCM_NONCE_SIZE_BYTES) { "迁移包随机数无效" }
        require(ciphertext.size in VaultCrypto.GCM_TAG_SIZE_BYTES..MAX_CIPHERTEXT_SIZE_BYTES) {
            "迁移包密文大小无效"
        }

        val key = crypto.deriveRecoveryKey(passphrase, salt, envelope.iterations)
        return try {
            crypto.decrypt(
                key = key,
                payload = AesGcmPayload(nonce, ciphertext),
                aad =
                    packageAad(
                        iterations = envelope.iterations,
                        createdAtEpochSeconds = envelope.createdAtEpochSeconds,
                        expiresAtEpochSeconds = envelope.expiresAtEpochSeconds,
                        salt = salt,
                    ),
            )
        } catch (error: Exception) {
            throw IllegalArgumentException("口令错误或迁移包已损坏", error)
        } finally {
            key.fill(0)
            salt.fill(0)
            nonce.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun packageAad(
        iterations: Int,
        createdAtEpochSeconds: Long,
        expiresAtEpochSeconds: Long,
        salt: ByteArray,
    ): ByteArray =
        buildString {
            append(PACKAGE_TYPE)
            append('\n')
            append(CURRENT_VERSION)
            append('\n')
            append(ALGORITHM)
            append('\n')
            append(KDF)
            append('\n')
            append(iterations)
            append('\n')
            append(createdAtEpochSeconds)
            append('\n')
            append(expiresAtEpochSeconds)
            append('\n')
            append(salt.toBase64Url())
        }.encodeToByteArray()

    companion object {
        const val CURRENT_VERSION = 2
        const val DEFAULT_TTL_SECONDS = 15 * 60L
        const val MAX_TTL_SECONDS = 24 * 60 * 60L
        const val MIN_PASSPHRASE_LENGTH = 12
        const val MAX_PASSPHRASE_LENGTH = 256

        private const val PACKAGE_TYPE = "yfuse-server-migration"
        private const val ALGORITHM = "A256GCM"
        private const val KDF = "PBKDF2-HMAC-SHA256"
        private const val ALLOWED_CLOCK_SKEW_SECONDS = 5 * 60L
        private const val MAX_PLAINTEXT_SIZE_BYTES = 256 * 1024
        private const val MAX_CIPHERTEXT_SIZE_BYTES = MAX_PLAINTEXT_SIZE_BYTES + VaultCrypto.GCM_TAG_SIZE_BYTES
        private const val MAX_ENCODED_SIZE_CHARS = 512 * 1024

        fun requireStrongPassphrase(passphrase: CharArray) {
            require(passphrase.size in MIN_PASSPHRASE_LENGTH..MAX_PASSPHRASE_LENGTH) {
                "迁移口令需包含 $MIN_PASSPHRASE_LENGTH–$MAX_PASSPHRASE_LENGTH 个字符"
            }
            require(passphrase.any { !it.isWhitespace() }) { "迁移口令不能只包含空白字符" }
        }
    }
}

@Serializable
private data class ProtectedServerMigrationPackage(
    @SerialName("type") val type: String,
    @SerialName("v") val version: Int,
    @SerialName("alg") val algorithm: String,
    @SerialName("kdf") val kdf: String,
    @SerialName("i") val iterations: Int,
    @SerialName("created") val createdAtEpochSeconds: Long,
    @SerialName("expires") val expiresAtEpochSeconds: Long,
    @SerialName("salt") val salt: String,
    @SerialName("nonce") val nonce: String,
    @SerialName("ciphertext") val ciphertext: String,
)
