package com.yfuse.feature.player

import com.yfuse.core.data.MediaVersionPreference
import com.yfuse.core.data.SeriesPlaybackResolution
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.preferredVersion
import com.yfuse.core.logging.AppLog
import com.yfuse.core.logging.playbackDiagnosticTrace
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.PlaybackChapter
import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.network.EmbyStream
import com.yfuse.core.sync.episodeWatchKey
import com.yfuse.core.sync.watchKey
import com.yfuse.core.sync.watchMatchKeys
import kotlin.time.TimeSource

/*
 * The pieces PlayerStoreFactory's queue load is made of, apart from the store itself: where
 * preparation has got to, what PlaybackInfo negotiated, and how an entry becomes a queue item. The
 * load reads as its phases in order; these hold what each phase hands the next.
 */

/**
 * One load's way through preparation, stage by stage, for its launch timing and the log.
 *
 * A stage is recorded against the item and server playback settled on, which a failover or a series
 * launch can move away from the ones requested; the timing started under the requested pair is
 * carried over to the settled one the first time it is needed there.
 */
internal class PlaybackPreparationStages(
    private val requestedItemId: String,
    private val primaryServerId: String?,
    /** Read when a stage is recorded, as the default server may change while a load runs. */
    private val registry: ServerRegistry,
    private val requestedSessionId: String,
    private val startedAt: TimeSource.Monotonic.ValueTimeMark,
) {
    fun record(
        stage: String,
        currentItemId: String,
        currentServerId: String,
        sessionId: String? = null,
        outcome: String = "ready",
    ) {
        val launchTiming =
            PlaybackLaunchTimings.find(currentServerId, currentItemId)
                ?: PlaybackLaunchTimings.find(primaryServerId, requestedItemId)?.also {
                    PlaybackLaunchTimings.register(currentServerId, currentItemId, it)
                }
        if (stage == "current_item_ready") launchTiming?.bindSession(sessionId)
        launchTiming?.stage(stage)
        AppLog.info(
            category = "feature.player",
            event = "playback_preparation_stage",
            message =
                "Playback preparation reached $stage (${playbackDiagnosticTrace(requestedSessionId)})",
            attributes =
                playbackPreparationStageAttributes(
                    stage = stage,
                    itemId = currentItemId,
                    serverId = currentServerId,
                    defaultServerId = registry.defaultServer?.id,
                    requestSessionId = requestedSessionId,
                    sessionId = sessionId,
                    outcome = outcome,
                    elapsedMs = startedAt.elapsedNow().inWholeMilliseconds,
                ),
        )
    }
}

/** What a preparation stage's log line carries, in the order it has always carried it. */
internal fun playbackPreparationStageAttributes(
    stage: String,
    itemId: String,
    serverId: String,
    defaultServerId: String?,
    requestSessionId: String,
    sessionId: String?,
    outcome: String,
    elapsedMs: Long,
): Map<String, String> =
    mapOf(
        "stage" to stage,
        "itemId" to itemId,
        "serverId" to serverId,
        "usesDefaultServer" to (serverId == defaultServerId).toString(),
        "requestSessionId" to requestSessionId,
        "sessionId" to sessionId.orEmpty(),
        "requestTrace" to playbackDiagnosticTrace(requestSessionId),
        "playbackTrace" to playbackDiagnosticTrace(sessionId),
        "outcome" to outcome,
        "elapsedMs" to elapsedMs.toString(),
    )

/**
 * The detail a load starts from: the requested entry's own, or for a known series the episode the
 * series resolves to, with the target and directory that came with it.
 */
internal class PlaybackLaunchDetail(
    val detail: Result<MediaDetail>,
    /** The resolved episode of a series launch; null for any other launch, or when it failed. */
    val series: SeriesPlaybackResolution?,
)

/** What PlaybackInfo answered for the entry a load plays, or that it did not. */
internal class PlaybackNegotiation(
    /** The server-approved sources; empty when PlaybackInfo failed or timed out. */
    val versions: List<MediaVersion>,
    /** The session the approved URLs belong to; null when nothing was approved. */
    val sessionId: String?,
    /** Set when the server answered with a different physical file than the one asked for. */
    val sourceMismatch: PlaybackSourceMismatch?,
    /** How PlaybackInfo ended; see [playbackNegotiationOutcome]. */
    val outcome: String,
)

/** How PlaybackInfo ended, as the `playback_info_ready` stage records it: null is a timeout. */
internal fun playbackNegotiationOutcome(result: Result<*>?): String =
    when {
        result == null -> "timeout"
        result.isFailure -> "failed"
        else -> "ready"
    }

/**
 * Turns one load's entries into queue items: the entry being opened ([currentItemId]) with the
 * sources PlaybackInfo negotiated for it and the file the detail page picked, every other entry
 * with its own listed sources and the persisted version preference.
 */
internal class PlayerQueueItemBuilder(
    private val server: SavedServer,
    private val currentItemId: String,
    /** The file the detail page picked for the opened entry, when it has more than one. */
    private val currentMediaSourceId: String?,
    private val negotiatedVersions: List<MediaVersion>,
    private val negotiatedSessionId: String?,
    private val mediaVersionPreference: MediaVersionPreference,
) {
    fun itemOf(
        id: String,
        title: String,
        playbackSegments: List<PlaybackSegment> = emptyList(),
        providerIds: Map<String, String> = emptyMap(),
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        seriesId: String? = null,
        seriesName: String? = null,
        seriesProviderIds: Map<String, String>? = null,
        versions: List<MediaVersion> = emptyList(),
        stillTag: String? = null,
        posterUrl: String? = null,
        progress: Float? = null,
        caption: String? = null,
        runtimeTicks: Long? = null,
        chapters: List<PlaybackChapter> = emptyList(),
    ): PlayerMediaItem {
        val effectiveVersions =
            if (id == currentItemId && negotiatedVersions.isNotEmpty()) {
                negotiatedVersions.preservingSourceMetadataFrom(versions)
            } else {
                versions
            }
        val playerVersions =
            effectiveVersions.toPlayerMediaVersions(
                baseUrl = server.baseUrl,
                itemId = id,
                token = server.accessToken,
                negotiatedPlaySessionId = negotiatedSessionId.takeIf { id == currentItemId },
                localCleartextConfirmed = server.localCleartextConfirmed,
                userId = server.userId,
            )
        // Preserve an explicit choice for the opened episode. Every other queue
        // entry is selected by persisted preference, never server/ingest order.
        val requestedVersionId = currentMediaSourceId.takeIf { id == currentItemId }
        val preferredVersionId =
            effectiveVersions
                .preferredVersion(mediaVersionPreference, requestedVersionId)
                ?.id
        val chosen =
            playerVersions.firstOrNull { it.id == preferredVersionId }
                ?: playerVersions.firstOrNull()
        if (id == currentItemId && chosen != null) {
            val selectedMetadata =
                effectiveVersions.firstOrNull { it.id == chosen.id }
                    ?: effectiveVersions.firstOrNull()
            AppLog.info(
                category = "feature.player",
                event = "playback_route_selected",
                message = "Playback source route selected",
                attributes =
                    mapOf(
                        "discSource" to chosen.discSource.toString(),
                        "container" to (chosen.container ?: "unknown"),
                        "method" to chosen.playMethod.name,
                        "hasNegotiatedDirectStream" to
                            (selectedMetadata?.directStreamUrl != null).toString(),
                        "hasNegotiatedTranscode" to
                            (selectedMetadata?.transcodingUrl != null).toString(),
                        "sourceSizeBytes" to
                            (selectedMetadata?.sizeBytes?.toString() ?: "unknown"),
                    ),
            )
        }
        // Entries whose sources were never fetched still need addresses; they get
        // the unqualified ones, which is the file the server would have picked.
        val unqualified =
            chosen ?: EmbyStream
                .streamUrls(server.baseUrl, id, server.accessToken, userId = server.userId)
                .let {
                    PlayerMediaVersion(
                        id = id,
                        label = "",
                        detail = "",
                        url = it.direct,
                        transcodeUrl = it.transcode,
                        fallbackTranscodeUrl = it.progressiveTranscode,
                        playSessionId = it.playSessionId,
                    )
                }
        return PlayerMediaItem(
            id = id,
            url = unqualified.url,
            transcodeUrl = unqualified.transcodeUrl,
            title = title,
            providerIds = providerIds,
            mediaType = if (seriesId != null || episodeNumber != null) "Episode" else "Movie",
            fallbackTranscodeUrl = unqualified.fallbackTranscodeUrl,
            playSessionId = unqualified.playSessionId,
            playMethod = unqualified.playMethod,
            serverTranscodeSupported = unqualified.serverTranscodeSupported,
            forcedTranscodeReason =
                DISC_SOURCE_TRANSCODE_REASON.takeIf {
                    unqualified.discSource &&
                        unqualified.playMethod == PlaybackMethod.Transcode
                },
            serverId = server.id,
            playbackSegments = playbackSegments,
            chapters = chapters,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            seriesId = seriesId,
            seriesName = seriesName,
            seriesKey =
                skipSeriesStorageKey(
                    serverId = server.id,
                    seriesId = seriesId,
                    providerSeriesKey =
                        seriesId?.let { id -> seriesProviderIds?.watchKey(id) },
                ),
            watchKey =
                if (seriesProviderIds == null) {
                    providerIds.watchKey(id)
                } else {
                    episodeWatchKey(
                        ownProviderIds = providerIds,
                        seriesProviderIds = seriesProviderIds,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        fallbackId = id,
                    )
                },
            matchKeys =
                watchMatchKeys(
                    ownProviderIds = providerIds,
                    seriesProviderIds = seriesProviderIds.orEmpty(),
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    fallbackId = id,
                ),
            versions = playerVersions,
            versionId = chosen?.id,
            stillUrl =
                stillTag?.let {
                    EmbyImages.primary(
                        server.baseUrl,
                        id,
                        it,
                        maxHeight = 240,
                        accessToken = server.accessToken,
                    )
                },
            posterUrl = posterUrl,
            progress = progress,
            caption = caption,
            durationMsHint = runtimeTicks?.takeIf { it > 0L }?.div(10_000L) ?: 0L,
            externalSubtitles = unqualified.externalSubtitles,
        )
    }
}
