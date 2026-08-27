package com.yfuse.feature.player

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.MAX_SMART_SOURCE_FALLBACKS
import com.yfuse.core.data.MediaVersionPreference
import com.yfuse.core.data.PlaybackFailoverRequest
import com.yfuse.core.data.ServerHealthMonitor
import com.yfuse.core.data.ServerHealthStatus
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.dto.toMediaVersion
import com.yfuse.core.data.preferredVersion
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.model.TrickplayInfo
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.network.EmbyStream
import com.yfuse.core.playback.PlaybackDrmConfiguration
import com.yfuse.core.sync.episodeWatchKey
import com.yfuse.core.sync.watchKey
import com.yfuse.core.sync.watchMatchKeys
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

private const val DISC_SOURCE_TRANSCODE_REASON =
    "ISO/DVD/Blu-ray 光盘源需要服务器解析主标题，已使用服务器转码"

/**
 * One selectable file behind a queue entry, with its stream URLs already built.
 *
 * URLs rather than a bare id so that switching version mid-playback needs nothing from the
 * network and no credentials in the player: every version's addresses were resolved when
 * the queue was built.
 */
@Serializable
data class PlayerMediaVersion(
    val id: String,
    /** The server's name for this file — "Bluray 2160p", or its container. */
    val label: String,
    /** `4K HDR10 · 42.3 GB · 68 Mbps · MKV` */
    val detail: String,
    val url: String,
    val transcodeUrl: String,
    val fallbackTranscodeUrl: String,
    /** `MKV`, for the player's readout line. */
    val container: String? = null,
    /** True for ISO/DVD/Blu-ray sources that cannot use an ordinary original-file URL. */
    val discSource: Boolean = false,
    /** Optional secure playback parameters; secrets are never copied into diagnostics. */
    val drmConfiguration: PlaybackDrmConfiguration? = null,
    /**
     * What the file carries, decided by [com.yfuse.core.model.MediaVersion] rather than
     * re-derived here — Emby hides Dolby Vision in four different fields and one place
     * knowing where is enough.
     */
    val dolbyVision: Boolean = false,
    val dolbyAtmos: Boolean = false,
    /** 5 / 7 / 8 / 9. See [com.yfuse.core.model.MediaVersion.dolbyProfile]. */
    val dolbyProfile: Int? = null,
    /** True when nothing but a Dolby-capable decoder will render this correctly. */
    val needsDolbyDecoder: Boolean = false,
    /** Source-layer facts retained for privacy-safe P7/FEL runtime diagnostics. */
    val sourceDolbyRpuPresent: Boolean? = null,
    val sourceDolbyEnhancementLayerPresent: Boolean? = null,
    val sourceDolbyBaseLayerPresent: Boolean? = null,
    val sourceDolbyBaseLayerCompatibility: Int? = null,
    /**
     * What the original file is, so a transcode can aim at it instead of a fixed 1080p.
     *
     * Transcoding happens because playback failed, not because the file was too big; a 4K
     * remux that falls back to 1080p/6 Mbps is a far larger loss than the one that made
     * the fallback necessary.
     */
    val sourceWidth: Int? = null,
    val sourceHeight: Int? = null,
    val sourceBitrateBps: Int? = null,
    val sourceVideoCodec: String? = null,
    val sourceFrameRate: Double? = null,
    val sourceVideoLevel: Double? = null,
    val sourceBitDepth: Int? = null,
    /** Original file size, retained so YCore can identify huge remote container edge cases. */
    val sourceSizeBytes: Long? = null,
    /** Source facts used until an engine reports the actual decoded output. */
    val sourceDynamicRange: String? = null,
    val sourceAudio: String? = null,
    /** Lets an engine distinguish a genuinely silent file from a missing audio track. */
    val audioTrackCount: Int = 0,
    /** Initial method approved by PlaybackInfo for [url]. */
    val playMethod: PlaybackMethod = PlaybackMethod.DirectPlay,
    /** True only when PlaybackInfo explicitly approved a server-transcoded representation. */
    val serverTranscodeSupported: Boolean = false,
    /**
     * The id already baked into the three URLs above, so the playback reports can name the
     * same session the server started an encoding for. See [EmbyStream.newPlaySessionId].
     *
     * Blank for entries built before this field existed (an older build's marshalled queue)
     * and for offline files, which have no server session at all.
     */
    val playSessionId: String = "",
) {
    /**
     * The same physical file addressed as a brand-new playback session.
     *
     * Version choices can return to a file used earlier in the same player. Reusing its old
     * id lets a delayed `Stopped`/DELETE from the previous binding kill the new encoder.
     */
    fun withFreshPlaySession(): PlayerMediaVersion {
        val sessionId = EmbyStream.newPlaySessionId()
        return copy(
            url = url.withPlaySessionId(sessionId),
            transcodeUrl = transcodeUrl.withPlaySessionId(sessionId),
            fallbackTranscodeUrl = fallbackTranscodeUrl.withPlaySessionId(sessionId),
            playSessionId = sessionId,
        )
    }
}

/** Builds version-specific addresses once, with the physical MediaSource id in every URL. */
internal fun List<MediaVersion>.toPlayerMediaVersions(
    baseUrl: String,
    itemId: String,
    token: String,
    negotiatedPlaySessionId: String? = null,
    localCleartextConfirmed: Boolean = false,
): List<PlayerMediaVersion> =
    map { version ->
        val generated =
            EmbyStream.streamUrls(
                baseUrl = baseUrl,
                itemId = itemId,
                token = token,
                mediaSourceId = version.id,
                sourceWidth = version.video?.width,
                sourceBitrateBps = version.bitrateBps ?: version.video?.bitrateBps,
            )
        val sessionId =
            negotiatedPlaySessionId?.takeIf { it.isNotBlank() }
                ?: generated.playSessionId
        val directStream =
            version.directStreamUrl?.let { raw ->
                EmbyStream.negotiatedUrl(
                    baseUrl = baseUrl,
                    rawUrl = raw,
                    token = token,
                    playSessionId = sessionId,
                    addApiKey = version.addApiKeyToDirectStreamUrl,
                    localCleartextConfirmed = localCleartextConfirmed,
                )
            }
        val negotiatedTranscode =
            version.transcodingUrl?.let { raw ->
                EmbyStream.negotiatedUrl(
                    baseUrl = baseUrl,
                    rawUrl = raw,
                    token = token,
                    playSessionId = sessionId,
                    localCleartextConfirmed = localCleartextConfirmed,
                )
            }
        val requiresDiscStream = version.requiresDiscNavigation
        // Dolby is implemented by the client pipeline. PlaybackInfo is still used for URLs and
        // source metadata, but a server-side codec/profile verdict must not replace the original
        // file with H.264/AAC before Exo/mpv/MDK get a chance to render or downmix it locally.
        val preserveDolbyLocally =
            !requiresDiscStream && (version.isDolbyVision || version.hasDolbyAtmos)
        val safeDiscDirectStream =
            requiresDiscStream &&
                version.supportsDirectStream == true &&
                directStream != null &&
                version.directStreamUrl.isLinearMediaStreamUrl()
        val hlsTranscode =
            when {
                negotiatedTranscode != null ->
                    EmbyStream.compatibleHlsTranscodeUrl(baseUrl, negotiatedTranscode)
                // A raw disc URL cannot be consumed by any Android backend. Even servers
                // that omit/deny the capability flag get one best-effort main-title request.
                requiresDiscStream -> generated.transcode.withPlaySessionId(sessionId)
                version.supportsTranscoding == false -> ""
                else -> generated.transcode.withPlaySessionId(sessionId)
            }
        val method =
            when {
                // A concrete .m2ts/.ts/etc address means the server already selected and
                // remuxed a title. A generic or static DirectStreamUrl can still be the raw
                // ISO bytes, which no Android backend can consume as a linear stream.
                safeDiscDirectStream -> PlaybackMethod.DirectStream
                requiresDiscStream && hlsTranscode.isNotBlank() -> PlaybackMethod.Transcode
                preserveDolbyLocally -> PlaybackMethod.DirectPlay
                version.supportsDirectPlay != false -> PlaybackMethod.DirectPlay
                version.supportsDirectStream == true && directStream != null -> PlaybackMethod.DirectStream
                version.supportsTranscoding == true && hlsTranscode.isNotBlank() -> PlaybackMethod.Transcode
                else -> PlaybackMethod.DirectPlay
            }
        val primaryUrl =
            when (method) {
                PlaybackMethod.DirectPlay -> generated.direct.withPlaySessionId(sessionId)
                PlaybackMethod.DirectStream -> requireNotNull(directStream)
                PlaybackMethod.Transcode -> hlsTranscode
            }
        val progressiveTranscode =
            if (version.supportsTranscoding == false && !requiresDiscStream) {
                ""
            } else {
                generated.progressiveTranscode.withPlaySessionId(sessionId)
            }
        PlayerMediaVersion(
            id = version.id,
            label = version.name,
            detail = version.summary,
            url = primaryUrl,
            transcodeUrl = hlsTranscode,
            fallbackTranscodeUrl = progressiveTranscode,
            playSessionId = sessionId,
            playMethod = method,
            serverTranscodeSupported =
                requiresDiscStream ||
                    negotiatedTranscode != null ||
                    version.supportsTranscoding == true,
            container = version.container?.uppercase(),
            discSource = requiresDiscStream,
            dolbyVision = version.isDolbyVision,
            dolbyAtmos = version.hasDolbyAtmos,
            dolbyProfile = version.dolbyProfile,
            needsDolbyDecoder = version.needsDolbyCapableDecoder,
            sourceDolbyRpuPresent = version.video?.dolbyRpuPresent,
            sourceDolbyEnhancementLayerPresent = version.video?.dolbyEnhancementLayerPresent,
            sourceDolbyBaseLayerPresent = version.video?.dolbyBaseLayerPresent,
            sourceDolbyBaseLayerCompatibility = version.video?.dolbyBaseLayerCompatibility,
            sourceWidth = version.video?.width,
            sourceHeight = version.videoHeight ?: version.video?.height,
            sourceBitrateBps = version.bitrateBps ?: version.video?.bitrateBps,
            sourceVideoCodec = version.videoCodec ?: version.video?.codec,
            sourceFrameRate = version.video?.frameRate,
            sourceVideoLevel = version.video?.level,
            sourceBitDepth = version.video?.bitDepth,
            sourceSizeBytes = version.sizeBytes,
            sourceDynamicRange = version.rangeLabel,
            sourceAudio =
                (
                    version.audioTracks.firstOrNull { it.default == true }
                        ?: version.audioTracks.firstOrNull()
                )?.label,
            audioTrackCount = version.audioTracks.size,
        )
    }

private fun String?.isLinearMediaStreamUrl(): Boolean {
    val path = this?.substringBefore('?')?.substringBefore('#')?.lowercase() ?: return false
    return LINEAR_MEDIA_STREAM_EXTENSIONS.any(path::endsWith)
}

private val LINEAR_MEDIA_STREAM_EXTENSIONS =
    setOf(".m2ts", ".mts", ".ts", ".mkv", ".mp4", ".m4v", ".mov", ".webm")

/**
 * PlaybackInfo is authoritative for URLs and capabilities, but some servers omit the fields
 * that identify ISO/DVD/Blu-ray sources from its MediaSources. Preserve those facts from the
 * item-detail response so a negotiated source cannot silently turn back into a plain file.
 */
internal fun List<MediaVersion>.preservingSourceMetadataFrom(detailVersions: List<MediaVersion>): List<MediaVersion> =
    mapIndexed { index, negotiated ->
        val detail =
            detailVersions.firstOrNull { it.id == negotiated.id }
                ?: detailVersions.getOrNull(index)?.takeIf {
                    size == detailVersions.size || (size == 1 && detailVersions.size == 1)
                }
                ?: return@mapIndexed negotiated
        negotiated.copy(
            container = negotiated.container ?: detail.container,
            sizeBytes = negotiated.sizeBytes ?: detail.sizeBytes,
            bitrateBps = negotiated.bitrateBps ?: detail.bitrateBps,
            videoCodec = negotiated.videoCodec ?: detail.videoCodec,
            videoHeight = negotiated.videoHeight ?: detail.videoHeight,
            videoRange = negotiated.videoRange ?: detail.videoRange,
            path = negotiated.path ?: detail.path,
            videoType = negotiated.videoType ?: detail.videoType,
            video = negotiated.video ?: detail.video,
            audioTracks = negotiated.audioTracks.ifEmpty { detail.audioTracks },
            subtitleTracks = negotiated.subtitleTracks.ifEmpty { detail.subtitleTracks },
        )
    }

internal data class PlaybackSourceMismatch(
    val expectedSizeBytes: Long?,
    val returnedSizeBytes: Long?,
    val expectedContainer: String?,
    val returnedContainer: String?,
)

/** Prevents a requested physical edition from silently turning into the server's first source. */
internal fun playbackSourceMismatch(
    requestedMediaSourceId: String?,
    detailVersions: List<MediaVersion>,
    negotiatedVersions: List<MediaVersion>,
): PlaybackSourceMismatch? {
    val requestedId = requestedMediaSourceId?.takeIf(String::isNotBlank) ?: return null
    if (negotiatedVersions.isEmpty()) return null
    val expected = detailVersions.firstOrNull { it.id == requestedId }
    val returned =
        negotiatedVersions.firstOrNull { it.id == requestedId }
            ?: return PlaybackSourceMismatch(
                expectedSizeBytes = expected?.sizeBytes,
                returnedSizeBytes = negotiatedVersions.firstOrNull()?.sizeBytes,
                expectedContainer = expected?.container,
                returnedContainer = negotiatedVersions.firstOrNull()?.container,
            )
    val expectedSize = expected?.sizeBytes?.takeIf { it > 0L }
    val returnedSize = returned.sizeBytes?.takeIf { it > 0L }
    if (expectedSize != null && returnedSize != null && expectedSize != returnedSize) {
        return PlaybackSourceMismatch(
            expectedSizeBytes = expectedSize,
            returnedSizeBytes = returnedSize,
            expectedContainer = expected.container,
            returnedContainer = returned.container,
        )
    }
    return null
}

/** One entry in the player's playlist, with a transcode fallback URL. */
data class PlayerMediaItem(
    val id: String,
    val url: String,
    val transcodeUrl: String,
    val title: String,
    val fallbackTranscodeUrl: String = transcodeUrl,
    val serverId: String? = null,
    val playbackSegments: List<PlaybackSegment> = emptyList(),
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    /**
     * The series this entry belongs to, or null for a film. Skip times are stored against
     * it — an opening is a property of the show, not of any one episode, so setting it once
     * has to hold for the rest of the queue and for every season after it.
     */
    val seriesId: String? = null,
    val seriesName: String? = null,
    /** Cross-server identity used by watch-together rooms — the one this device publishes. */
    val watchKey: String = id,
    /**
     * Every name this entry answers to when a room says what it is playing. A superset of
     * [watchKey]: the other device picked its key from its own metadata, which is rarely
     * the same subset as this one's. See `watchMatchKeys`.
     */
    val matchKeys: List<String> = listOf(watchKey),
    /**
     * Every file the server holds for this entry. Empty for entries whose sources were
     * never fetched — the sibling episodes of a queue, which are listed rather than
     * detailed — and for the ordinary case of a library holding exactly one file.
     */
    val versions: List<PlayerMediaVersion> = emptyList(),
    /** Which of [versions] the URLs above were built from. */
    val versionId: String? = null,
    /**
     * The episode still, for the strip along the bottom of the player.
     *
     * Carried on the queue rather than fetched by the strip: the queue is built from a
     * list query that already returns the image tag, and a picker that fires a request per
     * thumbnail the moment it opens is the reason the old drawer had grey tiles instead.
     */
    val stillUrl: String? = null,
    /** Series poster shown when [stillUrl] is missing or the image request fails. */
    val posterUrl: String? = null,
    /** 0f..1f, how far through this entry the viewer already is. Null for untouched. */
    val progress: Float? = null,
    /** `第 4 集` — the coordinate alone, under the episode's own name. */
    val caption: String? = null,
    /** See [PlayerMediaVersion.playSessionId]; this is the active version's. */
    val playSessionId: String = "",
    val playMethod: PlaybackMethod = PlaybackMethod.DirectPlay,
    /** Prevents generated best-effort URLs from masquerading as negotiated transcode support. */
    val serverTranscodeSupported: Boolean = false,
    /** Secure configuration for [url], updated atomically when a media version changes. */
    val drmConfiguration: PlaybackDrmConfiguration? = null,
    /** Local preflight reason when the device forces the prepared server stream before rendering. */
    val forcedTranscodeReason: String? = null,
    val trickplay: TrickplayStoryboard? = null,
    /** Optional process-local offline sidecar; never contains an account token. */
    val externalSubtitleUri: String? = null,
    val externalSubtitleLanguage: String? = null,
    /**
     * Exact copies on other servers, resolved before the player starts.
     *
     * Each entry is a complete, credential-free playback item and deliberately has an empty
     * [serverFallbacks] list of its own. The player can therefore switch without another network
     * call, while one bad title can never create a recursive or unbounded failover graph.
     */
    val serverFallbacks: List<PlayerMediaItem> = emptyList(),
    /** Exact server runtime used until the active engine reports an authoritative duration. */
    val durationMsHint: Long = 0L,
) {
    /**
     * The file currently playing, when the entry's sources were fetched at all.
     *
     * Null for the sibling episodes of a queue: they are listed from `/Shows/…/Episodes`,
     * which does not carry `MediaSources`, so nothing here knows what those files hold. The
     * readout and the 杜比 badge simply say less on them rather than guessing from the
     * episode that was opened — a different episode is a different file.
     */
    val activeVersion: PlayerMediaVersion?
        get() = versions.firstOrNull { it.id == versionId } ?: versions.firstOrNull()

    /** Preloading must never start server ffmpeg or read the prefix of a raw disc image. */
    val canPreloadSource: Boolean
        get() =
            playMethod != PlaybackMethod.Transcode &&
                activeVersion?.playMethod != PlaybackMethod.Transcode &&
                activeVersion?.discSource != true &&
                drmConfiguration == null &&
                activeVersion?.drmConfiguration == null

    /** The same entry playing a different file, or unchanged when there is no such file. */
    fun withVersion(id: String?): PlayerMediaItem {
        val version = versions.firstOrNull { it.id == id } ?: return this
        return withVersion(version)
    }

    /** Applies a resolved version instance, including a freshly rotated playback session. */
    fun withVersion(version: PlayerMediaVersion): PlayerMediaItem {
        if (versions.none { it.id == version.id }) return this
        val previousMediaSourceId = activeVersion?.id ?: versionId ?: id
        return copy(
            url = version.url,
            transcodeUrl = version.transcodeUrl,
            fallbackTranscodeUrl = version.fallbackTranscodeUrl,
            versionId = version.id,
            // Moves with the URLs: each version's addresses were built with their own id,
            // and reporting one session's id against another's stream ends the wrong job.
            playSessionId = version.playSessionId,
            playMethod = version.playMethod,
            serverTranscodeSupported = version.serverTranscodeSupported,
            drmConfiguration = version.drmConfiguration,
            forcedTranscodeReason =
                when {
                    version.discSource && version.playMethod == PlaybackMethod.Transcode ->
                        DISC_SOURCE_TRANSCODE_REASON
                    forcedTranscodeReason == DISC_SOURCE_TRANSCODE_REASON -> null
                    else -> forcedTranscodeReason
                },
            // A storyboard tile URL is qualified by MediaSourceId. Carrying it to another
            // physical file shows wrong/missing thumbnails; PlayerActivity lazily reloads it.
            trickplay = trickplay.takeIf { version.id == previousMediaSourceId },
        )
    }

    /** The next untried cross-server copy, or null when the bounded plan is exhausted. */
    fun nextServerFallback(triedServerIds: Set<String>): PlayerMediaItem? =
        serverFallbacks.firstOrNull { fallback ->
            fallback.serverId != null && fallback.serverId !in triedServerIds
        }
}

@Serializable
data class TrickplayStoryboard(
    val urlPattern: String,
    val width: Int,
    val height: Int,
    val tileColumns: Int,
    val tileRows: Int,
    val intervalMs: Long,
    val thumbnailCount: Int,
) {
    fun frameAt(positionMs: Long): TrickplayFrame {
        val frame =
            (positionMs.coerceAtLeast(0L) / intervalMs.coerceAtLeast(1L))
                .coerceAtMost((thumbnailCount - 1).coerceAtLeast(0).toLong())
                .toInt()
        val perSheet = (tileColumns * tileRows).coerceAtLeast(1)
        val local = frame % perSheet
        return TrickplayFrame(
            url = urlPattern.replace("{index}", (frame / perSheet).toString()),
            column = local % tileColumns.coerceAtLeast(1),
            row = local / tileColumns.coerceAtLeast(1),
        )
    }
}

data class TrickplayFrame(
    val url: String,
    val column: Int,
    val row: Int,
)

internal data class TrickplayCacheKey(
    val serverId: String,
    val itemId: String,
    val mediaSourceId: String,
)

/** Keeps viewed-episode storyboards (including misses) without prefetching an entire season. */
internal fun Map<TrickplayCacheKey, TrickplayStoryboard?>.withTrickplayResult(
    key: TrickplayCacheKey,
    storyboard: TrickplayStoryboard?,
    maxEntries: Int = MAX_TRICKPLAY_CACHE_ENTRIES,
): Map<TrickplayCacheKey, TrickplayStoryboard?> {
    require(maxEntries > 0)
    return (filterKeys { it != key } + (key to storyboard))
        .entries
        .toList()
        .takeLast(maxEntries)
        .associate { it.toPair() }
}

internal const val MAX_TRICKPLAY_CACHE_ENTRIES = 8

private fun String.withPlaySessionId(sessionId: String): String {
    if (isBlank()) return this
    val parameter = Regex("([?&])PlaySessionId=[^&]*")
    return if (parameter.containsMatchIn(this)) {
        replace(parameter, "$1PlaySessionId=$sessionId")
    } else {
        "$this${if ('?' in this) '&' else '?'}PlaySessionId=$sessionId"
    }
}

/**
 * Whether two queue snapshots describe the same media sources in the same order.
 *
 * Episode polling also refreshes display-only metadata such as title, artwork, skip segments and
 * watched progress. Those fields are deliberately excluded: changing them can recompose the
 * controls without tearing down a healthy playback engine. The engine only needs rebuilding when
 * an entry is added, removed, reordered, or one of the URLs it may load changes.
 */
internal fun List<PlayerMediaItem>.hasSamePlaybackSourcesAs(other: List<PlayerMediaItem>): Boolean {
    if (size != other.size) return false
    return indices.all { index ->
        val current = this[index]
        val refreshed = other[index]
        current.id == refreshed.id &&
            current.url.playbackSourceKey() == refreshed.url.playbackSourceKey() &&
            current.transcodeUrl.playbackSourceKey() ==
            refreshed.transcodeUrl.playbackSourceKey() &&
            current.fallbackTranscodeUrl.playbackSourceKey() ==
            refreshed.fallbackTranscodeUrl.playbackSourceKey() &&
            (current.drmConfiguration ?: current.activeVersion?.drmConfiguration) ==
            (refreshed.drmConfiguration ?: refreshed.activeVersion?.drmConfiguration)
    }
}

/**
 * The entries [other] adds after this queue, or null when it is not a pure extension of it.
 *
 * Appending is the one shape of change an engine can absorb while playing. Anything else — a
 * reorder, a removal, a file swapped underneath an entry — needs the engine rebuilt, because
 * its playlist is addressed by position.
 */
internal fun List<PlayerMediaItem>.appendedBy(other: List<PlayerMediaItem>): List<PlayerMediaItem>? {
    if (other.size <= size) return null
    if (!hasSamePlaybackSourcesAs(other.subList(0, size))) return null
    return other.subList(size, other.size).toList()
}

/**
 * The address with the id that identifies *this* playback of it removed.
 *
 * A play session is minted per queue build, so two entries can name the same file through
 * different session ids. Comparing the raw URLs would then read "the sources changed" and
 * tear down a healthy engine — which is the question this key exists to answer correctly.
 * For comparison only; the result is not a fetchable URL.
 */
private fun String.playbackSourceKey(): String = replace(Regex("[?&]PlaySessionId=[^&]*"), "")

data class PlayerState(
    val loading: Boolean = true,
    val items: List<PlayerMediaItem> = emptyList(),
    val startIndex: Int = 0,
    val startPositionMs: Long = 0L,
    val error: String? = null,
)

sealed interface PlayerIntent {
    /** Rebuild the queue after an initial load failure without recreating the route. */
    data object Retry : PlayerIntent
}

private sealed interface PlayerAction {
    data object Load : PlayerAction
}

private sealed interface PlayerMsg {
    data object Loading : PlayerMsg

    data class Ready(
        val items: List<PlayerMediaItem>,
        val startIndex: Int,
        val startMs: Long,
    ) : PlayerMsg

    data class Failed(
        val message: String,
    ) : PlayerMsg
}

/**
 * Builds the playback queue. For an episode the whole series is queued so the
 * player's next/previous controls move between episodes.
 */
class PlayerStoreFactory(
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
    private val itemId: String,
    private val startPositionTicks: Long,
    private val serverId: String? = null,
    /** The file the detail page picked, when the item has more than one. */
    private val mediaSourceId: String? = null,
    private val mediaVersionPreference: MediaVersionPreference = MediaVersionPreference.HdrFirst,
    private val failoverRequest: PlaybackFailoverRequest = PlaybackFailoverRequest(),
    private val healthMonitor: ServerHealthMonitor? = null,
) {
    fun create(): Store<PlayerIntent, PlayerState, Nothing> =
        storeFactory.create(
            name = "PlayerStore",
            initialState = PlayerState(),
            bootstrapper = coroutineBootstrapper<PlayerAction> { dispatch(PlayerAction.Load) },
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl :
        CoroutineExecutor<PlayerIntent, PlayerAction, PlayerState, PlayerMsg, Nothing>() {
        override fun executeAction(action: PlayerAction) {
            load()
        }

        override fun executeIntent(intent: PlayerIntent) {
            when (intent) {
                PlayerIntent.Retry -> {
                    if (state().loading) return
                    dispatch(PlayerMsg.Loading)
                    load()
                }
            }
        }

        private fun load() {
            val primaryServer = serverId?.let(registry::serverById) ?: registry.defaultServer
            scope.launch {
                if (primaryServer == null) {
                    AppLog.error(
                        category = "feature.player",
                        event = "server_missing",
                        message = "Playback queue could not be built because no server is available",
                    )
                    dispatch(PlayerMsg.Failed("没有可用的服务器"))
                    return@launch
                }

                var server = requireNotNull(primaryServer)
                var effectiveItemId = itemId
                var effectiveMediaSourceId = mediaSourceId
                var effectiveStartPositionTicks = startPositionTicks
                var detailResult = repo.itemDetail(server, effectiveItemId)
                val failoverPlan = failoverRequest.consume(itemId)
                val primaryFailure = detailResult.exceptionOrNull()
                if (primaryFailure == null) {
                    healthMonitor?.recordSuccess(server.id)
                } else {
                    healthMonitor?.recordFailure(server.id, primaryFailure)
                }
                if (primaryFailure?.isPlaybackFailoverEligible() == true && failoverPlan != null) {
                    for (fallbackId in failoverPlan.fallbackServerIds.take(MAX_SMART_SOURCE_FALLBACKS)) {
                        if (fallbackId == server.id) continue
                        val fallback = registry.serverById(fallbackId) ?: continue
                        if (healthMonitor
                                ?.health
                                ?.value
                                ?.get(fallback.id)
                                ?.status ==
                            ServerHealthStatus.AuthRequired
                        ) {
                            continue
                        }
                        val hitResult = repo.findByMediaKey(fallback, failoverPlan.mediaKey)
                        val hit = hitResult.getOrNull()
                        if (hit == null) {
                            hitResult.exceptionOrNull()?.let { healthMonitor?.recordFailure(fallback.id, it) }
                            continue
                        }
                        val fallbackDetail = repo.itemDetail(fallback, hit.id)
                        val resolved = fallbackDetail.getOrNull()
                        if (resolved != null) {
                            AppLog.warning(
                                category = "feature.player",
                                event = "playback_server_failover",
                                message = "Primary server failed before playback; switched to an exact-media fallback",
                                attributes =
                                    mapOf(
                                        "fromServerId" to server.id,
                                        "toServerId" to fallback.id,
                                        "mediaKey" to failoverPlan.mediaKey,
                                    ),
                            )
                            server = fallback
                            effectiveItemId = hit.id
                            effectiveMediaSourceId = null
                            detailResult = fallbackDetail
                            healthMonitor?.recordSuccess(fallback.id)
                            break
                        } else {
                            fallbackDetail.exceptionOrNull()?.let { healthMonitor?.recordFailure(fallback.id, it) }
                        }
                    }
                }

                val launchDetail = detailResult.getOrNull()
                if (launchDetail?.type == "Series") {
                    val targetResult = repo.resolvePlayTarget(server, launchDetail)
                    val target = targetResult.getOrNull()
                    if (target == null) {
                        targetResult.exceptionOrNull()?.let { healthMonitor?.recordFailure(server.id, it) }
                        AppLog.warning(
                            category = "feature.player",
                            event = "series_play_target_failed",
                            message = "Series launch could not resolve a playable episode",
                            throwable = targetResult.exceptionOrNull(),
                            attributes = mapOf("serverId" to server.id, "seriesId" to launchDetail.id),
                        )
                        dispatch(PlayerMsg.Failed("没有可播放的剧集"))
                        return@launch
                    }
                    effectiveItemId = target.itemId
                    effectiveMediaSourceId = null
                    effectiveStartPositionTicks = target.startPositionTicks
                    detailResult = repo.itemDetail(server, effectiveItemId)
                    if (detailResult.isFailure) {
                        val failure = detailResult.exceptionOrNull()
                        failure?.let { healthMonitor?.recordFailure(server.id, it) }
                        AppLog.warning(
                            category = "feature.player",
                            event = "series_episode_detail_failed",
                            message = "Resolved series episode detail could not be loaded",
                            throwable = failure,
                            attributes =
                                mapOf(
                                    "serverId" to server.id,
                                    "seriesId" to launchDetail.id,
                                    "episodeId" to effectiveItemId,
                                ),
                        )
                        dispatch(PlayerMsg.Failed("无法加载可播放剧集"))
                        return@launch
                    }
                    healthMonitor?.recordSuccess(server.id)
                    AppLog.info(
                        category = "feature.player",
                        event = "series_play_target_resolved",
                        message = "Series launch resolved to a playable episode before PlaybackInfo",
                        attributes =
                            mapOf(
                                "serverId" to server.id,
                                "seriesId" to launchDetail.id,
                                "episodeId" to effectiveItemId,
                            ),
                    )
                }
                val startMs = effectiveStartPositionTicks / 10_000L
                val remainingFallbackServerIds =
                    failoverPlan
                        ?.fallbackServerIds
                        .orEmpty()
                        .asSequence()
                        .filter(String::isNotBlank)
                        .filter { it != server.id }
                        .distinct()
                        .take(MAX_SMART_SOURCE_FALLBACKS)
                        .toList()

                var negotiatedVersions: List<MediaVersion> = emptyList()
                var negotiatedSessionId: String? = null
                var negotiatedTrickplay: TrickplayInfo? = null

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
                ): PlayerMediaItem {
                    val effectiveVersions =
                        if (id == effectiveItemId && negotiatedVersions.isNotEmpty()) {
                            negotiatedVersions.preservingSourceMetadataFrom(versions)
                        } else {
                            versions
                        }
                    val playerVersions =
                        effectiveVersions.toPlayerMediaVersions(
                            baseUrl = server.baseUrl,
                            itemId = id,
                            token = server.accessToken,
                            negotiatedPlaySessionId = negotiatedSessionId.takeIf { id == effectiveItemId },
                            localCleartextConfirmed = server.localCleartextConfirmed,
                        )
                    // Preserve an explicit choice for the opened episode. Every other queue
                    // entry is selected by persisted preference, never server/ingest order.
                    val requestedVersionId = effectiveMediaSourceId.takeIf { id == effectiveItemId }
                    val preferredVersionId =
                        effectiveVersions
                            .preferredVersion(mediaVersionPreference, requestedVersionId)
                            ?.id
                    val chosen =
                        playerVersions.firstOrNull { it.id == preferredVersionId }
                            ?: playerVersions.firstOrNull()
                    if (id == effectiveItemId && chosen != null) {
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
                            .streamUrls(server.baseUrl, id, server.accessToken)
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
                        fallbackTranscodeUrl = unqualified.fallbackTranscodeUrl,
                        playSessionId = unqualified.playSessionId,
                        playMethod = unqualified.playMethod,
                        serverTranscodeSupported = unqualified.serverTranscodeSupported,
                        forcedTranscodeReason =
                            DISC_SOURCE_TRANSCODE_REASON.takeIf {
                                unqualified.discSource &&
                                    unqualified.playMethod == PlaybackMethod.Transcode
                            },
                        trickplay =
                            (if (id == effectiveItemId) negotiatedTrickplay else null)?.let { info ->
                                TrickplayStoryboard(
                                    urlPattern =
                                        EmbyStream.trickplayTilePattern(
                                            baseUrl = server.baseUrl,
                                            itemId = id,
                                            mediaSourceId = unqualified.id,
                                            width = info.width,
                                            token = server.accessToken,
                                        ),
                                    width = info.width,
                                    height = info.height,
                                    tileColumns = info.tileColumns,
                                    tileRows = info.tileRows,
                                    intervalMs = info.intervalMs,
                                    thumbnailCount = info.thumbnailCount,
                                )
                            },
                        serverId = server.id,
                        playbackSegments = playbackSegments,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        seriesId = seriesId,
                        seriesName = seriesName,
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
                    )
                }

                detailResult.onFailure {
                    AppLog.warning(
                        category = "feature.player",
                        event = "item_detail_failed",
                        message = "Playback queue item detail failed to load",
                        throwable = it,
                        attributes = mapOf("serverId" to server.id),
                    )
                }
                val detail = detailResult.getOrNull()
                val requestedSessionId = EmbyStream.newPlaySessionId()
                var selectedSourceMismatch: PlaybackSourceMismatch? = null
                val playbackInfoResult =
                    withTimeoutOrNull(PLAYBACK_NEGOTIATION_TIMEOUT_MS) {
                        repo.playbackInfo(
                            server = server,
                            itemId = effectiveItemId,
                            mediaSourceId = effectiveMediaSourceId,
                            startPositionTicks = effectiveStartPositionTicks,
                            playSessionId = requestedSessionId,
                        )
                    }
                if (playbackInfoResult == null) {
                    AppLog.warning(
                        category = "feature.player",
                        event = "playback_negotiation_timeout",
                        message = "PlaybackInfo timed out; using compatibility URL ladder",
                        attributes = mapOf("serverId" to server.id),
                    )
                } else {
                    playbackInfoResult
                        .onSuccess { playbackInfo ->
                            negotiatedVersions =
                                playbackInfo.MediaSources.mapIndexed { index, source ->
                                    source.toMediaVersion(
                                        fallbackId = effectiveMediaSourceId ?: effectiveItemId,
                                        ordinal = index,
                                    )
                                }
                            selectedSourceMismatch =
                                playbackSourceMismatch(
                                    requestedMediaSourceId = effectiveMediaSourceId,
                                    detailVersions = detail?.versions.orEmpty(),
                                    negotiatedVersions = negotiatedVersions,
                                )
                            negotiatedSessionId = playbackInfo.PlaySessionId
                                ?.takeIf { it.isNotBlank() }
                                ?: requestedSessionId
                            AppLog.info(
                                category = "feature.player",
                                event = "playback_negotiated",
                                message = "PlaybackInfo selected server-approved playback capabilities",
                                attributes =
                                    mapOf(
                                        "serverId" to server.id,
                                        "sourceCount" to negotiatedVersions.size.toString(),
                                        "discSource" to
                                            negotiatedVersions
                                                .any(MediaVersion::requiresDiscNavigation)
                                                .toString(),
                                        "hasDirectStream" to
                                            negotiatedVersions
                                                .any { it.directStreamUrl != null }
                                                .toString(),
                                        "hasTranscode" to
                                            negotiatedVersions
                                                .any { it.transcodingUrl != null }
                                                .toString(),
                                        "selectedSourceVerified" to
                                            (selectedSourceMismatch == null).toString(),
                                    ),
                            )
                        }.onFailure { error ->
                            AppLog.warning(
                                category = "feature.player",
                                event = "playback_negotiation_failed",
                                message = "PlaybackInfo failed; using compatibility URL ladder",
                                throwable = error,
                                attributes = mapOf("serverId" to server.id),
                            )
                        }
                }
                selectedSourceMismatch?.let { mismatch ->
                    AppLog.error(
                        category = "feature.player",
                        event = "selected_source_mismatch",
                        message = "PlaybackInfo returned a different physical media source",
                        attributes =
                            mapOf(
                                "expectedSizeBytes" to (mismatch.expectedSizeBytes?.toString() ?: "unknown"),
                                "returnedSizeBytes" to (mismatch.returnedSizeBytes?.toString() ?: "unknown"),
                                "expectedContainer" to (mismatch.expectedContainer ?: "unknown"),
                                "returnedContainer" to (mismatch.returnedContainer ?: "unknown"),
                            ),
                    )
                    dispatch(PlayerMsg.Failed("所选资源与服务器返回不一致，请刷新详情后重试"))
                    return@launch
                }
                // The shared Ktor client already enforces a request timeout. Wrapping a second
                // request in a coroutine timeout here can strand MockEngine/UI continuations
                // on the caller dispatcher, so the optional metadata relies on that budget.
                negotiatedTrickplay = repo.trickplayInfo(server, effectiveItemId).getOrNull()
                val seriesId = detail?.seriesId
                val serverFallbacks =
                    failoverPlan
                        ?.let { plan ->
                            resolveServerFallbacks(
                                serverIds = remainingFallbackServerIds,
                                mediaKey = plan.mediaKey,
                                startPositionTicks = effectiveStartPositionTicks,
                                titleFallback = detail?.title.orEmpty(),
                            )
                        }.orEmpty()
                if (serverFallbacks.isNotEmpty()) {
                    AppLog.info(
                        category = "feature.player",
                        event = "playback_server_fallbacks_ready",
                        message = "Exact-media server fallbacks were resolved before playback",
                        attributes =
                            mapOf(
                                "serverId" to server.id,
                                "fallbackCount" to serverFallbacks.size.toString(),
                            ),
                    )
                }

                if (detail?.type == "Episode" && seriesId != null) {
                    // The show's provider ids, not this episode's: they are what makes an
                    // episode recognisable on someone else's server (see episodeWatchKey).
                    // One extra request per queue, and a miss only costs the cross-server
                    // half of watch-together.
                    val seriesDetail = repo.itemDetail(server, seriesId).getOrNull()
                    val seriesProviderIds = seriesDetail?.providerIds.orEmpty()
                    val seriesPosterUrl =
                        EmbyImages.primary(
                            baseUrl = server.baseUrl,
                            itemId = seriesDetail?.posterItemId ?: seriesId,
                            tag = seriesDetail?.posterTag,
                            maxHeight = 360,
                            accessToken = server.accessToken,
                        )
                    val episodesResult =
                        repo.episodes(
                            server,
                            seriesId,
                            null,
                            includeMediaSources = true,
                        )
                    episodesResult.onFailure {
                        AppLog.warning(
                            category = "feature.player",
                            event = "episode_queue_failed",
                            message = "Episode playback queue failed to load",
                            throwable = it,
                            attributes = mapOf("serverId" to server.id),
                        )
                    }
                    val episodes = episodesResult.getOrDefault(emptyList())
                    if (episodes.isNotEmpty()) {
                        val items =
                            episodes.map { ep ->
                                itemOf(
                                    ep.id,
                                    listOfNotNull(ep.indexNumber?.let { "第 $it 集" }, ep.name).joinToString("  "),
                                    ep.playbackSegments,
                                    ep.providerIds,
                                    ep.seasonNumber,
                                    ep.indexNumber,
                                    seriesId,
                                    detail.seriesName,
                                    seriesProviderIds,
                                    // The opened detail is the freshest copy; every sibling now
                                    // carries MediaSources from the single episode-list request.
                                    // Without this, their transcode URL used item id as
                                    // MediaSourceId and Emby rejected it with HTTP 400.
                                    versions = if (ep.id == effectiveItemId) detail.versions else ep.versions,
                                    stillTag = ep.primaryTag,
                                    posterUrl = seriesPosterUrl,
                                    // A finished episode reads as full rather than as untouched:
                                    // Emby clears the resume percentage on completion, so the
                                    // two are indistinguishable without the played flag.
                                    progress =
                                        when {
                                            ep.played -> 1f
                                            else -> ep.playedPercentage?.let { (it / 100.0).toFloat() }
                                        },
                                    caption = ep.indexNumber?.let { "第 $it 集" },
                                    runtimeTicks =
                                        if (ep.id == effectiveItemId) {
                                            detail.runtimeTicks ?: ep.runtimeTicks
                                        } else {
                                            ep.runtimeTicks
                                        },
                                )
                            }
                        val effectiveIndex = items.indexOfFirst { it.id == effectiveItemId }
                        val itemsWithFailover =
                            items.mapIndexed { itemIndex, item ->
                                if (itemIndex == effectiveIndex) {
                                    item.copy(serverFallbacks = serverFallbacks)
                                } else {
                                    item
                                }
                            }
                        val index = itemsWithFailover.indexOfFirst { it.id == effectiveItemId }.coerceAtLeast(0)
                        AppLog.info(
                            category = "feature.player",
                            event = "queue_ready",
                            message = "Episode playback queue prepared",
                            attributes = mapOf("itemCount" to itemsWithFailover.size.toString()),
                        )
                        dispatch(PlayerMsg.Ready(itemsWithFailover, index, startMs))
                        return@launch
                    }
                }

                dispatch(
                    PlayerMsg.Ready(
                        listOf(
                            itemOf(
                                id = effectiveItemId,
                                title = detail?.title ?: "",
                                playbackSegments = detail?.playbackSegments.orEmpty(),
                                providerIds = detail?.providerIds.orEmpty(),
                                versions = detail?.versions.orEmpty(),
                                runtimeTicks = detail?.runtimeTicks,
                            ).copy(serverFallbacks = serverFallbacks),
                        ),
                        0,
                        startMs,
                    ),
                )
                AppLog.info(
                    category = "feature.player",
                    event = "queue_ready",
                    message = "Single-item playback queue prepared",
                    attributes = mapOf("detailAvailable" to (detail != null).toString()),
                )
            }
        }

        private suspend fun resolveServerFallbacks(
            serverIds: List<String>,
            mediaKey: String,
            startPositionTicks: Long,
            titleFallback: String,
        ): List<PlayerMediaItem> {
            if (serverIds.isEmpty() || mediaKey.isBlank()) return emptyList()
            val candidates =
                serverIds
                    .asSequence()
                    .filter(String::isNotBlank)
                    .distinct()
                    .take(MAX_SMART_SOURCE_FALLBACKS)
                    .toList()
            val resolved = mutableListOf<Pair<Int, PlayerMediaItem?>>()
            withTimeoutOrNull(SERVER_FALLBACK_TOTAL_TIMEOUT_MS) {
                coroutineScope {
                    candidates
                        .mapIndexed { index, fallbackServerId ->
                            async {
                                index to
                                    withTimeoutOrNull(SERVER_FALLBACK_CANDIDATE_TIMEOUT_MS) {
                                        resolveServerFallback(
                                            serverId = fallbackServerId,
                                            mediaKey = mediaKey,
                                            startPositionTicks = startPositionTicks,
                                            titleFallback = titleFallback,
                                        )
                                    }
                            }
                        }.forEach { deferred ->
                            resolved += deferred.await()
                        }
                }
            }
            return resolved
                .sortedBy(Pair<Int, PlayerMediaItem?>::first)
                .mapNotNull(Pair<Int, PlayerMediaItem?>::second)
                .distinctBy { it.serverId }
                .take(MAX_SMART_SOURCE_FALLBACKS)
        }

        private suspend fun resolveServerFallback(
            serverId: String,
            mediaKey: String,
            startPositionTicks: Long,
            titleFallback: String,
        ): PlayerMediaItem? {
            val fallback = registry.serverById(serverId) ?: return null
            if (
                healthMonitor
                    ?.health
                    ?.value
                    ?.get(serverId)
                    ?.status in setOf(ServerHealthStatus.AuthRequired, ServerHealthStatus.Offline)
            ) {
                return null
            }
            val hitResult = repo.findByMediaKey(fallback, mediaKey)
            val hit = hitResult.getOrNull()
            if (hit == null) {
                hitResult.exceptionOrNull()?.let { healthMonitor?.recordFailure(serverId, it) }
                return null
            }
            var detailResult = repo.itemDetail(fallback, hit.id)
            var detail = detailResult.getOrNull()
            if (detail == null) {
                detailResult.exceptionOrNull()?.let { healthMonitor?.recordFailure(serverId, it) }
                return null
            }
            var fallbackStartPositionTicks = startPositionTicks
            if (detail.type == "Series") {
                val targetResult = repo.resolvePlayTarget(fallback, detail)
                val target = targetResult.getOrNull()
                if (target == null) {
                    targetResult.exceptionOrNull()?.let { healthMonitor?.recordFailure(serverId, it) }
                    return null
                }
                fallbackStartPositionTicks = target.startPositionTicks
                detailResult = repo.itemDetail(fallback, target.itemId)
                detail = detailResult.getOrNull()
                if (detail == null) {
                    detailResult.exceptionOrNull()?.let { healthMonitor?.recordFailure(serverId, it) }
                    return null
                }
            }
            val requestedSessionId = EmbyStream.newPlaySessionId()
            val playbackInfo =
                repo
                    .playbackInfo(
                        server = fallback,
                        itemId = detail.id,
                        startPositionTicks = fallbackStartPositionTicks,
                        playSessionId = requestedSessionId,
                    ).getOrNull()
            val versions =
                playbackInfo
                    ?.MediaSources
                    ?.mapIndexed { index, source ->
                        source.toMediaVersion(fallbackId = detail.id, ordinal = index)
                    }?.takeIf(List<MediaVersion>::isNotEmpty)
                    ?: detail.versions
            val playerVersions =
                versions.toPlayerMediaVersions(
                    baseUrl = fallback.baseUrl,
                    itemId = detail.id,
                    token = fallback.accessToken,
                    negotiatedPlaySessionId =
                        playbackInfo
                            ?.PlaySessionId
                            ?.takeIf(String::isNotBlank)
                            ?: requestedSessionId,
                    localCleartextConfirmed = fallback.localCleartextConfirmed,
                )
            val preferredVersionId = versions.preferredVersion(mediaVersionPreference)?.id
            val selected =
                playerVersions.firstOrNull { it.id == preferredVersionId }
                    ?: playerVersions.firstOrNull()
            val playable =
                selected ?: EmbyStream
                    .streamUrls(fallback.baseUrl, detail.id, fallback.accessToken)
                    .let { urls ->
                        PlayerMediaVersion(
                            id = detail.id,
                            label = "",
                            detail = "",
                            url = urls.direct,
                            transcodeUrl = urls.transcode,
                            fallbackTranscodeUrl = urls.progressiveTranscode,
                            playSessionId = urls.playSessionId,
                        )
                    }
            healthMonitor?.recordSuccess(serverId)
            return PlayerMediaItem(
                id = detail.id,
                url = playable.url,
                transcodeUrl = playable.transcodeUrl,
                title = detail.title.ifBlank { titleFallback },
                fallbackTranscodeUrl = playable.fallbackTranscodeUrl,
                serverId = serverId,
                playbackSegments = detail.playbackSegments,
                seasonNumber = detail.seasonNumber,
                episodeNumber = detail.episodeNumber,
                seriesId = detail.seriesId,
                seriesName = detail.seriesName,
                watchKey = mediaKey,
                matchKeys =
                    watchMatchKeys(
                        ownProviderIds = detail.providerIds,
                        seasonNumber = detail.seasonNumber,
                        episodeNumber = detail.episodeNumber,
                        fallbackId = detail.id,
                    ),
                versions = playerVersions,
                versionId = selected?.id,
                playSessionId = playable.playSessionId,
                playMethod = playable.playMethod,
                serverTranscodeSupported = playable.serverTranscodeSupported,
                forcedTranscodeReason =
                    DISC_SOURCE_TRANSCODE_REASON.takeIf {
                        playable.discSource && playable.playMethod == PlaybackMethod.Transcode
                    },
                serverFallbacks = emptyList(),
                durationMsHint = detail.runtimeTicks?.takeIf { it > 0L }?.div(10_000L) ?: 0L,
            )
        }
    }

    private object ReducerImpl : Reducer<PlayerState, PlayerMsg> {
        override fun PlayerState.reduce(msg: PlayerMsg): PlayerState =
            when (msg) {
                PlayerMsg.Loading ->
                    copy(
                        loading = true,
                        items = emptyList(),
                        error = null,
                    )
                is PlayerMsg.Ready ->
                    copy(
                        loading = false,
                        items = msg.items,
                        startIndex = msg.startIndex,
                        startPositionMs = msg.startMs,
                        error = null,
                    )
                is PlayerMsg.Failed ->
                    copy(
                        loading = false,
                        items = emptyList(),
                        error = msg.message,
                    )
            }
    }
}

private const val SERVER_FALLBACK_CANDIDATE_TIMEOUT_MS = 6_000L
private const val SERVER_FALLBACK_TOTAL_TIMEOUT_MS = 9_000L
internal const val PLAYBACK_NEGOTIATION_TIMEOUT_MS = 15_000L

// watchKey now lives in com.yfuse.core.sync alongside the invite payload that carries it.

internal fun Throwable.isPlaybackFailoverEligible(): Boolean =
    when (val error = (this as? EmbyErrorException)?.error) {
        EmbyError.Network -> true
        is EmbyError.Server -> error.code in 500..599
        else -> false
    }
