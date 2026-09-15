package com.yfuse.core.handoff

import com.yfuse.core.account.AccountRepository
import com.yfuse.core.account.AccountState
import com.yfuse.core.security.AesGcmPayload
import com.yfuse.core.security.SecureStore
import com.yfuse.core.security.VaultCrypto
import com.yfuse.core.security.base64UrlToBytes
import com.yfuse.core.security.toBase64Url
import com.yfuse.core.sync.playback.PlaybackTrackPreference
import com.yfuse.watch.protocol.HandoffEnvelope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class HandoffMedia(
    val mediaKey: String,
    val title: String,
    val serverId: String,
    val itemId: String,
    val positionMs: Long,
    val durationMs: Long,
    val aliases: List<String> = emptyList(),
    val preference: PlaybackTrackPreference? = null,
    val profileId: String? = null,
    val mediaSourceId: String? = null,
    val versionName: String? = null,
    val audioTrackIndex: Int? = null,
    val subtitleTrackIndex: Int? = null,
    val secondarySubtitleTrackIndex: Int? = null,
    val audioOffsetMs: Long? = null,
    val subtitleOffsetMs: Long? = null,
    val secondarySubtitleOffsetMs: Long? = null,
    val secondarySubtitleLanguage: String? = null,
    val secondarySubtitleTitle: String? = null,
    val secondarySubtitleCodec: String? = null,
    val secondarySubtitlesEnabled: Boolean? = null,
) {
    fun valid(): Boolean {
        if (mediaKey.length !in 1..512 || title.length > 512) return false
        if (serverId.length !in 1..256 || itemId.length !in 1..256) return false
        if (positionMs < 0 || durationMs < 0 || durationMs > 0 && positionMs > durationMs) return false
        if (aliases.size > 16 || aliases.any { it.length > 512 }) return false
        if (listOf(mediaSourceId, versionName, profileId).any { (it?.length ?: 0) > 256 }) return false
        val offsets = listOf(audioOffsetMs, subtitleOffsetMs, secondarySubtitleOffsetMs)
        if (offsets.any { it != null && it !in -3_600_000..3_600_000 }) return false
        val indices = listOf(audioTrackIndex, subtitleTrackIndex, secondarySubtitleTrackIndex)
        if (indices.any { it != null && it !in -1..10_000 }) return false
        val secondary = listOf(secondarySubtitleLanguage, secondarySubtitleTitle, secondarySubtitleCodec)
        if (secondary.any { (it?.length ?: 0) > 512 }) return false
        val tracks = preference ?: return true
        val text =
            listOf(
                tracks.audioLanguage,
                tracks.audioCodec,
                tracks.audioTitle,
                tracks.subtitleLanguage,
                tracks.subtitleCodec,
                tracks.subtitleTitle,
            )
        if (text.any { (it?.length ?: 0) > 512 }) return false
        val speed = tracks.playbackSpeed ?: return true
        return speed.isFinite() && speed in 0.25f..4f
    }
}

interface HandoffPayloadCipher {
    fun encrypt(
        requestId: String,
        media: HandoffMedia,
    ): HandoffEnvelope

    fun decrypt(
        requestId: String,
        envelope: HandoffEnvelope,
    ): HandoffMedia
}

/** Reuses the account vault, with a distinct purpose and request id in authenticated data. */
class HandoffVaultCipher(
    private val account: AccountRepository,
    private val secureStore: SecureStore,
    private val crypto: VaultCrypto,
) : HandoffPayloadCipher {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    override fun encrypt(
        requestId: String,
        media: HandoffMedia,
    ): HandoffEnvelope {
        require(media.valid())
        return withKey { userId, key ->
            val plain = json.encodeToString(media).encodeToByteArray()
            try {
                require(plain.size <= 24_000)
                val result = crypto.encrypt(key, plain, aad(userId, requestId))
                HandoffEnvelope(result.nonce.toBase64Url(), result.ciphertext.toBase64Url())
            } finally {
                plain.fill(0)
            }
        }
    }

    override fun decrypt(
        requestId: String,
        envelope: HandoffEnvelope,
    ): HandoffMedia =
        withKey { userId, key ->
            require(envelope.nonce.length == 16 && envelope.ciphertext.length <= 32_768)
            val plain =
                crypto.decrypt(
                    key,
                    AesGcmPayload(envelope.nonce.base64UrlToBytes(), envelope.ciphertext.base64UrlToBytes()),
                    aad(userId, requestId),
                )
            try {
                require(plain.size <= 24_000)
                json.decodeFromString<HandoffMedia>(plain.decodeToString()).also { require(it.valid()) }
            } finally {
                plain.fill(0)
            }
        }

    private fun <T> withKey(block: (String, ByteArray) -> T): T {
        val owner = (account.state.value as? AccountState.SignedIn)?.session?.user?.id ?: error("请先登录鱼服账号")
        require(secureStore.get("vault_user_id")?.decodeToString() == owner) { "账号加密资料尚未就绪" }
        val key = secureStore.get("vault_key") ?: error("账号加密资料尚未就绪")
        return try {
            require(key.size == VaultCrypto.AES_KEY_SIZE_BYTES)
            block(owner, key)
        } finally {
            key.fill(0)
        }
    }

    private fun aad(
        userId: String,
        id: String,
    ) = "yfuse-handoff:v1:$userId:$id".encodeToByteArray()
}

/** Preparation MUST keep auto-play disabled. All methods are called on the supplied UI scope. */
interface HandoffPlaybackBridge {
    fun snapshot(): HandoffMedia?

    suspend fun prepare(media: HandoffMedia): Boolean

    suspend fun pauseAndSnapshot(): HandoffMedia?

    suspend fun startPrepared(media: HandoffMedia): Boolean

    suspend fun releasePrepared()

    suspend fun resumeSource()

    fun finishTransfer() {}
}
