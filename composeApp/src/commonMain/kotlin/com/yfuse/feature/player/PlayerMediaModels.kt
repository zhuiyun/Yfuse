package com.yfuse.feature.player

import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.PlaybackChapter
import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.network.EmbyStream
import com.yfuse.core.playback.PlaybackDrmConfiguration
import com.yfuse.core2.network.YTransportCredentials
import kotlinx.serialization.Serializable

internal const val DISC_SOURCE_TRANSCODE_REASON =
    "ISO/DVD/Blu-ray 光盘源需要服务器解析主标题，已使用服务器转码"

/** One authenticated sidecar exposed by the active media version. */
@Serializable
data class PlayerExternalSubtitle(
    val uri: String,
    val language: String? = null,
    val codec: String? = null,
    val default: Boolean = false,
    val forced: Boolean = false,
) {
    val label: String
        get() =
            listOfNotNull(language, codec?.uppercase(), "强制".takeIf { forced })
                .joinToString(" · ")
                .ifBlank { "外挂字幕" }
}

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
    /** Display rotation of the coded picture, from the server; decides an upright picture. */
    val sourceRotation: Int? = null,
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
    /** Preferred source track geometry used when MediaExtractor omits container metadata. */
    val sourceAudioChannelCount: Int = 0,
    val sourceAudioSampleRateHz: Int = 0,
    /** Lets an engine distinguish a genuinely silent file from a missing audio track. */
    val audioTrackCount: Int = 0,
    /** Server codec identifiers of every audio track, so diagnostics can name a hidden one. */
    val sourceAudioCodecs: List<String> = emptyList(),
    /** Sidecars belong to a physical version and change atomically with its media URL. */
    val externalSubtitles: List<PlayerExternalSubtitle> = emptyList(),
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
    /** Cross-origin provider URLs may be signed and do not accept our reporting session id. */
    val preserveUrlOnSessionRefresh: Boolean = false,
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
            url = if (preserveUrlOnSessionRefresh) url else url.withPlaySessionId(sessionId),
            transcodeUrl = transcodeUrl.withPlaySessionId(sessionId),
            fallbackTranscodeUrl = fallbackTranscodeUrl.withPlaySessionId(sessionId),
            playSessionId = sessionId,
        )
    }
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
    /** Named chapters of the file, which divide the progress bar; see `playbackChapters()`. */
    val chapters: List<PlaybackChapter> = emptyList(),
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    /**
     * The series this entry belongs to, or null for a film. Skip times are stored against
     * it — an opening is a property of the show, not of any one episode, so setting it once
     * has to hold for the rest of the queue and for every season after it.
     */
    val seriesId: String? = null,
    val seriesName: String? = null,
    /**
     * The folder a video outside any series was queued from with the videos beside it — a 短剧
     * kept as 01.mp4, 02.mp4 — or null. Its viewer treats them as one show; see [shortDramaKey].
     */
    val folderId: String? = null,
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
    /** Original raw optical URI retained in memory when a legacy native route replaces [url]. */
    val rawDiscUri: String? = null,
    val trickplay: TrickplayStoryboard? = null,
    /** Optional process-local offline sidecar; never contains an account token. */
    val externalSubtitleUri: String? = null,
    val externalSubtitleLanguage: String? = null,
    /** Online provider sidecars for the active version; legacy offline fields remain supported. */
    val externalSubtitles: List<PlayerExternalSubtitle> = emptyList(),
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
    /** In-memory credentials for direct SMB/WebDAV/HTTP sources; diagnostics remain redacted. */
    val transportCredentials: YTransportCredentials? = null,
    /** In-memory component headers, scoped to the original stream origin by the transport. */
    val customHeaders: PlayerCustomHeaders? = null,
    /** Stable persistence identity for per-series settings; external-provider based when known. */
    val seriesKey: String? = null,
    /** Item-owned provider ids. Episode reporting must not substitute the parent series' ids. */
    val providerIds: Map<String, String> = emptyMap(),
    val mediaType: String = "",
    val year: Int? = null,
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
        get() = playMethod != PlaybackMethod.Transcode && activeVersion?.discSource != true

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
            externalSubtitles = version.externalSubtitles,
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
        PlaybackFallbackLadder.nextServerCandidate(serverFallbacks, triedServerIds)
}
