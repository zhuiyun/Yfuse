package com.yfuse.core.sync.playback

import com.yfuse.watch.protocol.PlaybackMissingEntity
import kotlinx.serialization.Serializable

@Serializable
data class EncryptedPlaybackEntity(
    val entityKey: String,
    val mutationId: String,
    val schemaVersion: Int = 1,
    val algorithm: String = "AES-256-GCM",
    val keyVersion: Int = 1,
    val nonce: String,
    val ciphertext: String,
    val cursor: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
)

@Serializable
data class PlaybackPutItem(
    val baseCursor: Long,
    val entity: EncryptedPlaybackEntity,
)

@Serializable
data class PlaybackPushRequest(
    val items: List<PlaybackPutItem>,
)

@Serializable
data class PlaybackAcceptedEntity(
    val entityKey: String,
    val mutationId: String,
    val cursor: Long,
)

@Serializable
data class PlaybackPushResponse(
    /** Server high-water mark for diagnostics; only a pull response may advance the pull cursor. */
    val cursor: Long,
    val accepted: List<PlaybackAcceptedEntity> = emptyList(),
    val conflicts: List<EncryptedPlaybackEntity> = emptyList(),
    val missing: List<PlaybackMissingEntity> = emptyList(),
)

@Serializable
data class PlaybackDeltaResponse(
    val cursor: Long,
    val changes: List<EncryptedPlaybackEntity> = emptyList(),
    val hasMore: Boolean = false,
)
