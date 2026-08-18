package com.yfuse.core.sync.playback

import kotlinx.serialization.Serializable

@Serializable
enum class PlaybackMutationKind {
    AutoProgress,
    AutoFinished,
    ManualWatched,
    ManualUnwatched,
    ;

    val isManual: Boolean
        get() = this == ManualWatched || this == ManualUnwatched
}

@Serializable
enum class PlaybackSyncTrigger {
    Started,
    Periodic,
    Pause,
    Seek,
    Stop,
    Background,
    Completed,
    Manual,
}

@Serializable
data class PlaybackTrackPreference(
    val audioLanguage: String? = null,
    val audioCodec: String? = null,
    val audioTitle: String? = null,
    val subtitleLanguage: String? = null,
    val subtitleCodec: String? = null,
    val subtitleTitle: String? = null,
    val subtitleForced: Boolean? = null,
    val subtitleDefault: Boolean? = null,
    val subtitlesEnabled: Boolean? = null,
    val playbackSpeed: Float? = null,
    val updatedAtEpochMs: Long = 0L,
)

@Serializable
data class PlaybackHistoryEntry(
    val sessionId: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val startPositionMs: Long = 0L,
    val endPositionMs: Long = 0L,
    val deviceId: String,
    val serverId: String? = null,
)

@Serializable
data class PlaybackStateRecord(
    val mediaKey: String,
    val aliases: List<String> = emptyList(),
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val played: Boolean = false,
    val lastPlayedAtEpochMs: Long = 0L,
    val deviceId: String,
    val sessionId: String? = null,
    val serverId: String? = null,
    val serverItemId: String? = null,
    val revision: Long = 0L,
    val mutationKind: PlaybackMutationKind = PlaybackMutationKind.AutoProgress,
)

@Serializable
data class PlaybackSyncDocument(
    val schemaVersion: Int = 1,
    val state: PlaybackStateRecord,
    val preference: PlaybackTrackPreference? = null,
    val history: List<PlaybackHistoryEntry> = emptyList(),
)

/** Local-only metadata around an end-to-end encrypted cloud document. */
@Serializable
data class StoredPlaybackDocument(
    val document: PlaybackSyncDocument,
    /** Per opaque alias/entity CAS cursor. Different servers may publish different provider ids. */
    val remoteCursors: Map<String, Long> = emptyMap(),
    val dirty: Boolean = true,
    val mutationId: String,
)

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
    val cursor: Long,
    val accepted: List<PlaybackAcceptedEntity> = emptyList(),
    val conflicts: List<EncryptedPlaybackEntity> = emptyList(),
)

@Serializable
data class PlaybackDeltaResponse(
    val cursor: Long,
    val changes: List<EncryptedPlaybackEntity> = emptyList(),
    val hasMore: Boolean = false,
)
