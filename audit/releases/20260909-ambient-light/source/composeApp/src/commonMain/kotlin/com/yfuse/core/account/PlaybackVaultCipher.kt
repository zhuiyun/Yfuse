package com.yfuse.core.account

import com.yfuse.core.security.AesGcmPayload
import com.yfuse.core.security.SecureStore
import com.yfuse.core.security.VaultCrypto
import com.yfuse.core.security.base64UrlToBytes
import com.yfuse.core.security.toBase64Url
import com.yfuse.core.sync.playback.EncryptedPlaybackEntity
import com.yfuse.core.sync.playback.PlaybackSyncDocument
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Uses the account vault without exposing media metadata to the Yfuse account service. */
class PlaybackVaultCipher(
    private val account: AccountRepository,
    private val secureStore: SecureStore,
    private val crypto: VaultCrypto,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun currentUserId(): String? = (account.state.value as? AccountState.SignedIn)?.session?.user?.id

    fun encrypt(
        document: PlaybackSyncDocument,
        mutationId: String,
    ): EncryptedPlaybackEntity? {
        val signedIn = account.state.value as? AccountState.SignedIn ?: return null
        val key = requireVaultKey(signedIn.session.user.id) ?: return null
        return try {
            val entityKey = opaqueEntityKey(key, document.state.mediaKey)
            val plaintext = json.encodeToString(document).encodeToByteArray()
            try {
                val encrypted =
                    crypto.encrypt(
                        key = key,
                        plaintext = plaintext,
                        aad = playbackAad(signedIn.session.user.id, entityKey),
                    )
                EncryptedPlaybackEntity(
                    entityKey = entityKey,
                    mutationId = mutationId,
                    nonce = encrypted.nonce.toBase64Url(),
                    ciphertext = encrypted.ciphertext.toBase64Url(),
                )
            } finally {
                plaintext.fill(0)
            }
        } finally {
            key.fill(0)
        }
    }

    fun decrypt(entity: EncryptedPlaybackEntity): PlaybackSyncDocument? {
        val signedIn = account.state.value as? AccountState.SignedIn ?: return null
        val key = requireVaultKey(signedIn.session.user.id) ?: return null
        return try {
            require(entity.schemaVersion == 1 && entity.algorithm == "AES-256-GCM" && entity.keyVersion == 1)
            val plaintext =
                crypto.decrypt(
                    key = key,
                    payload =
                        AesGcmPayload(
                            nonce = entity.nonce.base64UrlToBytes(),
                            ciphertext = entity.ciphertext.base64UrlToBytes(),
                        ),
                    aad = playbackAad(signedIn.session.user.id, entity.entityKey),
                )
            try {
                require(plaintext.size <= MAX_PLAYBACK_PLAINTEXT_BYTES)
                val document = json.decodeFromString<PlaybackSyncDocument>(plaintext.decodeToString())
                require(document.schemaVersion == 1)
                val expectedKey = opaqueEntityKey(key, document.state.mediaKey)
                require(expectedKey == entity.entityKey) { "Playback entity identity mismatch" }
                document
            } finally {
                plaintext.fill(0)
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            null
        } finally {
            key.fill(0)
        }
    }

    /** HMAC-SHA256 implemented from the vault's SHA-256 primitive; the server sees only this tag. */
    private fun opaqueEntityKey(
        key: ByteArray,
        mediaKey: String,
    ): String {
        val block = ByteArray(HMAC_BLOCK_SIZE)
        key.copyInto(block)
        val innerPad = ByteArray(HMAC_BLOCK_SIZE) { index -> (block[index].toInt() xor 0x36).toByte() }
        val outerPad = ByteArray(HMAC_BLOCK_SIZE) { index -> (block[index].toInt() xor 0x5c).toByte() }
        val message = "yfuse-playback-entity-v1\u0000$mediaKey".encodeToByteArray()
        val inner = crypto.sha256(innerPad + message)
        val digest = crypto.sha256(outerPad + inner)
        block.fill(0)
        innerPad.fill(0)
        outerPad.fill(0)
        inner.fill(0)
        return try {
            digest.toBase64Url()
        } finally {
            digest.fill(0)
        }
    }

    private fun requireVaultKey(userId: String): ByteArray? {
        val owner = secureStore.get(KEY_VAULT_USER_ID)?.decodeToString()
        if (owner != null && owner != userId) return null
        return secureStore.get(KEY_VAULT_KEY)?.takeIf { it.size == VaultCrypto.AES_KEY_SIZE_BYTES }
    }

    private fun playbackAad(
        userId: String,
        entityKey: String,
    ): ByteArray = "yfuse-playback:v1:$userId:$entityKey".encodeToByteArray()

    private companion object {
        const val KEY_VAULT_KEY = "vault_key"
        const val KEY_VAULT_USER_ID = "vault_user_id"
        const val HMAC_BLOCK_SIZE = 64
        const val MAX_PLAYBACK_PLAINTEXT_BYTES = 24 * 1024
    }
}
