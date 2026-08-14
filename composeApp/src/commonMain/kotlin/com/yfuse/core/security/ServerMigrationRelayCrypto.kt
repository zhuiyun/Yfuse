package com.yfuse.core.security

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A v3 envelope containing an ordinary v2 package protected by a random 256-bit secret. */
data class RelayMigrationPackage(
    val envelope: String,
    val relayId: String,
    val transferSecret: ByteArray,
    val payloadSha256: String,
    val expiresAtEpochSeconds: Long,
) {
    fun clearSecret() = transferSecret.fill(0)
}

data class RelayMigrationDescriptor(
    val relayId: String,
    val payloadSha256: String,
    val expiresAtEpochSeconds: Long,
)

class ServerMigrationRelayCrypto(
    private val crypto: VaultCrypto = VaultCrypto(),
) {
    private val legacyCrypto = ServerMigrationCrypto(crypto)
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }

    fun protect(
        plaintext: ByteArray,
        createdAtEpochSeconds: Long,
        expiresAtEpochSeconds: Long,
    ): RelayMigrationPackage {
        val relayId = crypto.generateVaultKey()
        val transferSecret = crypto.generateVaultKey()
        val passphrase = transferSecret.toBase64Url().toCharArray()
        return try {
            val protectedV2 = legacyCrypto.protect(
                plaintext = plaintext,
                passphrase = passphrase,
                createdAtEpochSeconds = createdAtEpochSeconds,
                expiresAtEpochSeconds = expiresAtEpochSeconds,
            )
            val payloadHash = crypto.sha256(protectedV2.encodeToByteArray())
            try {
                RelayMigrationPackage(
                    envelope = json.encodeToString(
                        RelayEnvelope.serializer(),
                        RelayEnvelope(
                            relayId = relayId.toBase64Url(),
                            expiresAtEpochSeconds = expiresAtEpochSeconds,
                            payloadSha256 = payloadHash.toBase64Url(),
                            protectedV2 = protectedV2,
                        ),
                    ),
                    relayId = relayId.toBase64Url(),
                    transferSecret = transferSecret,
                    payloadSha256 = payloadHash.toBase64Url(),
                    expiresAtEpochSeconds = expiresAtEpochSeconds,
                )
            } catch (error: Throwable) {
                transferSecret.fill(0)
                throw error
            } finally {
                payloadHash.fill(0)
            }
        } finally {
            relayId.fill(0)
            passphrase.fill('\u0000')
        }
    }

    fun inspect(encoded: String): RelayMigrationDescriptor {
        val envelope = decode(encoded)
        envelope.relayId.decodeFixed(32, "迁移包标识").fill(0)
        envelope.payloadSha256.decodeFixed(32, "迁移包摘要").fill(0)
        verifyPayloadHash(envelope)
        return RelayMigrationDescriptor(
            relayId = envelope.relayId,
            payloadSha256 = envelope.payloadSha256,
            expiresAtEpochSeconds = envelope.expiresAtEpochSeconds,
        )
    }

    fun unprotect(
        encoded: String,
        transferSecret: ByteArray,
        nowEpochSeconds: Long,
    ): ByteArray {
        require(transferSecret.size == 32) { "迁移密钥无效" }
        val envelope = decode(encoded)
        require(nowEpochSeconds <= envelope.expiresAtEpochSeconds) { "迁移包已过期，请重新生成" }
        verifyPayloadHash(envelope)
        val passphrase = transferSecret.toBase64Url().toCharArray()
        return try {
            legacyCrypto.unprotect(envelope.protectedV2, passphrase, nowEpochSeconds)
        } finally {
            passphrase.fill('\u0000')
        }
    }

    fun isRelayEnvelope(encoded: String): Boolean =
        runCatching {
            val envelope = json.decodeFromString(RelayEnvelope.serializer(), encoded.trim())
            envelope.type == PACKAGE_TYPE && envelope.version == CURRENT_VERSION
        }.getOrDefault(false)

    private fun verifyPayloadHash(envelope: RelayEnvelope) {
        val expected = envelope.payloadSha256.decodeFixed(32, "迁移包摘要")
        val actual = crypto.sha256(envelope.protectedV2.encodeToByteArray())
        try {
            require(actual.contentEquals(expected)) { "迁移包已损坏" }
        } finally {
            expected.fill(0)
            actual.fill(0)
        }
    }

    private fun decode(encoded: String): RelayEnvelope {
        val trimmed = encoded.trim()
        require(trimmed.length in 1..MAX_ENCODED_CHARS) { "迁移包大小无效" }
        val envelope = runCatching { json.decodeFromString(RelayEnvelope.serializer(), trimmed) }
            .getOrElse { throw IllegalArgumentException("不是有效的六位码迁移包", it) }
        require(envelope.type == PACKAGE_TYPE && envelope.version == CURRENT_VERSION) {
            "不支持的迁移包版本"
        }
        require(envelope.expiresAtEpochSeconds >= 0) { "迁移包有效期无效" }
        require(envelope.protectedV2.length in 1..MAX_V2_CHARS) { "迁移包大小无效" }
        return envelope
    }

    private fun String.decodeFixed(size: Int, label: String): ByteArray {
        val value = runCatching { base64UrlToBytes() }.getOrElse {
            throw IllegalArgumentException("$label 编码无效", it)
        }
        require(value.size == size) { "$label 长度无效" }
        return value
    }

    companion object {
        const val CURRENT_VERSION = 3
        private const val PACKAGE_TYPE = "yfuse-server-migration"
        private const val MAX_V2_CHARS = 512 * 1024
        private const val MAX_ENCODED_CHARS = 768 * 1024
    }
}

@Serializable
private data class RelayEnvelope(
    @SerialName("type") val type: String = "yfuse-server-migration",
    @SerialName("v") val version: Int = ServerMigrationRelayCrypto.CURRENT_VERSION,
    @SerialName("relayId") val relayId: String,
    @SerialName("expires") val expiresAtEpochSeconds: Long,
    @SerialName("payloadSha256") val payloadSha256: String,
    @SerialName("protectedV2") val protectedV2: String,
)
