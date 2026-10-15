package com.yfuse.feature.player

import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.model.SubtitleTrackInfo
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import com.yfuse.core.network.EmbyStream
import com.yfuse.core.network.originalNegotiatedPlaybackUrl
import io.ktor.http.parseQueryString

/** Builds version-specific addresses once, with the physical MediaSource id in every URL. */
internal fun List<MediaVersion>.toPlayerMediaVersions(
    baseUrl: String,
    itemId: String,
    token: String,
    negotiatedPlaySessionId: String? = null,
    localCleartextConfirmed: Boolean = false,
    userId: String? = null,
): List<PlayerMediaVersion> =
    map { version ->
        val preferredAudio =
            version.audioTracks.firstOrNull { it.default == true }
                ?: version.audioTracks.firstOrNull()
        val generated =
            EmbyStream.streamUrls(
                baseUrl = baseUrl,
                itemId = itemId,
                token = token,
                mediaSourceId = version.id,
                sourceWidth = version.video?.width,
                sourceBitrateBps = version.bitrateBps ?: version.video?.bitrateBps,
                userId = userId,
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
                    userId = userId,
                )
            }
        val negotiatedTranscode =
            version.transcodingUrl?.let { raw ->
                EmbyStream.negotiatedUrl(
                    baseUrl = baseUrl,
                    rawUrl = raw,
                    token = token,
                    playSessionId = sessionId,
                    addApiKey = !raw.contains("X-Plex-Token=", ignoreCase = true),
                    localCleartextConfirmed = localCleartextConfirmed,
                    userId = userId,
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
        val providerDirectStream =
            directStream != null &&
                version.directStreamUrl?.contains("X-Plex-Token=", ignoreCase = true) == true
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
                // Plex's negotiated `/library/parts/...` URL is the original file. It must win
                // over the Emby-shaped generated DirectPlay fallback, including for Dolby media.
                providerDirectStream -> PlaybackMethod.DirectStream
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
                PlaybackMethod.DirectPlay ->
                    // Keep provider paths/signatures/user identity for an original linear file.
                    // Never replace raw-disc navigation or a local Dolby route with a transcode.
                    directStream
                        ?.takeIf {
                            !requiresDiscStream &&
                                (
                                    version.supportsDirectPlay == true ||
                                        version.directStreamUrl.isStaticMediaStreamUrl()
                                ) &&
                                (!preserveDolbyLocally || version.directStreamUrl.isStaticMediaStreamUrl())
                        }?.let(::originalNegotiatedPlaybackUrl)
                        ?: generated.direct.withPlaySessionId(sessionId)
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
            preserveUrlOnSessionRefresh = EmbyStream.isCrossOriginStreamUrl(baseUrl, primaryUrl),
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
            sourceRotation = version.video?.rotation,
            sourceBitrateBps = version.bitrateBps ?: version.video?.bitrateBps,
            sourceVideoCodec = version.videoCodec ?: version.video?.codec,
            sourceFrameRate = version.video?.frameRate,
            sourceVideoLevel = version.video?.level,
            sourceBitDepth = version.video?.bitDepth,
            sourceSizeBytes = version.sizeBytes,
            sourceDynamicRange = version.rangeLabel,
            sourceAudio = preferredAudio?.label,
            sourceAudioChannelCount = preferredAudio?.channelCount?.coerceAtLeast(0) ?: 0,
            sourceAudioSampleRateHz = preferredAudio?.sampleRateHz?.coerceAtLeast(0) ?: 0,
            audioTrackCount = version.audioTracks.size,
            sourceAudioCodecs = version.audioTracks.mapNotNull { track -> track.codec?.takeIf(String::isNotBlank) },
            externalSubtitles =
                version.subtitleTracks
                    .mapNotNull { track ->
                        if (!track.external) return@mapNotNull null
                        val uri =
                            track.playbackSidecarUrl(
                                baseUrl = baseUrl,
                                itemId = itemId,
                                mediaSourceId = version.id,
                                token = token,
                                localCleartextConfirmed = localCleartextConfirmed,
                                userId = userId,
                            ) ?: return@mapNotNull null
                        PlayerExternalSubtitle(
                            uri = uri,
                            language = track.language,
                            codec = uri.subtitleEndpointFormat() ?: track.codec,
                            default = track.default,
                            forced = track.forced,
                        )
                    }.distinctBy(PlayerExternalSubtitle::uri),
        )
    }

/**
 * Emby and Jellyfin send a sidecar as a path relative to the server, which no player can open, so it
 * is completed and authenticated like the stream URLs above. A [SubtitleTrackInfo.uriIsSidecarPath]
 * value that is not a web URL names a file on the server's disk; the subtitle endpoint serves that one
 * by index. Absolute provider URLs (Plex, a CDN) are kept as they are.
 */
private fun SubtitleTrackInfo.playbackSidecarUrl(
    baseUrl: String,
    itemId: String,
    mediaSourceId: String,
    token: String,
    localCleartextConfirmed: Boolean,
    userId: String?,
): String? {
    val raw = uri?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val webUrl = raw.substringBefore("://", missingDelimiterValue = "").lowercase() in setOf("http", "https")
    if (uriIsSidecarPath && !webUrl) {
        return EmbyStream.subtitle(
            baseUrl = baseUrl,
            itemId = itemId,
            mediaSourceId = mediaSourceId,
            streamIndex = index ?: return null,
            token = token,
            format = codec?.lowercase()?.takeIf { it in setOf("ass", "ssa", "vtt") } ?: "srt",
        )
    }
    return EmbyStream.negotiatedUrl(
        baseUrl = baseUrl,
        rawUrl = raw,
        token = token,
        playSessionId = "",
        addApiKey = !raw.contains("X-Plex-Token=", ignoreCase = true),
        localCleartextConfirmed = localCleartextConfirmed,
        userId = userId,
    )
}

/**
 * The format the subtitle endpoint sends (`…/Subtitles/3/0/Stream.srt`). It differs from the track
 * codec when the server converts the sidecar, as it does for ASS under this device profile.
 */
private fun String.subtitleEndpointFormat(): String? {
    val path = substringBefore('#').substringBefore('?')
    val file = path.substringAfterLast('/')
    if (!path.contains("/Subtitles/", ignoreCase = true) || !file.startsWith("Stream.", ignoreCase = true)) {
        return null
    }
    return file.substringAfter('.').lowercase().takeIf { it.matches(Regex("[a-z0-9]{2,8}")) }
}

private fun String?.isLinearMediaStreamUrl(): Boolean {
    val path = this?.substringBefore('?')?.substringBefore('#')?.lowercase() ?: return false
    return LINEAR_MEDIA_STREAM_EXTENSIONS.any(path::endsWith)
}

private fun String?.isStaticMediaStreamUrl(): Boolean {
    val query = this?.substringBefore('#')?.substringAfter('?', missingDelimiterValue = "") ?: return false
    return runCatching {
        parseQueryString(query)
            .entries()
            .singleOrNull { it.key.equals("Static", ignoreCase = true) }
            ?.value
            ?.singleOrNull()
            ?.equals("true", ignoreCase = true) == true
    }.getOrDefault(false)
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

// watchKey now lives in com.yfuse.core.sync alongside the invite payload that carries it.

internal fun Throwable.isPlaybackFailoverEligible(): Boolean =
    when (val error = (this as? EmbyErrorException)?.error) {
        is EmbyError.Unreachable -> true
        is EmbyError.Server -> error.code in 500..599
        else -> false
    }
