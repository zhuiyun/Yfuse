package com.yfuse.core.data

import com.yfuse.core.data.dto.DeviceProfileDto
import com.yfuse.core.data.dto.PlaybackInfoRequestDto
import com.yfuse.core.data.dto.PlaybackInfoResponseDto
import com.yfuse.core.data.dto.PlaybackReportDto
import com.yfuse.core.data.dto.YFUSE_MAX_STREAMING_BITRATE_BPS
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.normalizeBaseUrl
import com.yfuse.core.playback.PlaybackDeviceCapabilities
import com.yfuse.core.playback.PlaybackDeviceCapabilitiesProvider
import com.yfuse.deviceId
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val playbackRequestJson = Json { encodeDefaults = true }

/** Playback negotiation, session reporting, and transcoder lifecycle. */
internal class EmbyPlaybackService(
    private val client: HttpClient,
    private val capabilitiesProvider: PlaybackDeviceCapabilitiesProvider,
    private val audioPassthroughEnabled: () -> Boolean,
) {
    suspend fun reportStarted(
        server: SavedServer,
        itemId: String,
        playSessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String,
    ): Result<Unit> =
        reportPlayback(
            server = server,
            path = "/Sessions/Playing",
            itemId = itemId,
            playSessionId = playSessionId,
            positionTicks = positionTicks,
            isPaused = isPaused,
            playMethod = playMethod,
        )

    suspend fun reportProgress(
        server: SavedServer,
        itemId: String,
        playSessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String,
    ): Result<Unit> =
        reportPlayback(
            server = server,
            path = "/Sessions/Playing/Progress",
            itemId = itemId,
            playSessionId = playSessionId,
            positionTicks = positionTicks,
            isPaused = isPaused,
            playMethod = playMethod,
        )

    suspend fun reportStopped(
        server: SavedServer,
        itemId: String,
        playSessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String,
    ): Result<Unit> =
        reportPlayback(
            server = server,
            path = "/Sessions/Playing/Stopped",
            itemId = itemId,
            playSessionId = playSessionId,
            positionTicks = positionTicks,
            isPaused = isPaused,
            playMethod = playMethod,
        )

    suspend fun playbackInfo(
        server: SavedServer,
        itemId: String,
        mediaSourceId: String?,
        startPositionTicks: Long,
        playSessionId: String,
    ): Result<PlaybackInfoResponseDto> =
        embyApiCall("playback_info") {
            withContext(Dispatchers.Default) {
                val discoveredCapabilities =
                    runCatching(capabilitiesProvider::current)
                        .getOrElse { PlaybackDeviceCapabilities.conservative() }
                val capabilities =
                    if (runCatching(audioPassthroughEnabled).getOrDefault(false)) {
                        discoveredCapabilities
                    } else {
                        discoveredCapabilities.copy(directAudioFormats = emptySet())
                    }
                val profile = DeviceProfileDto.yfuseAndroid(capabilities)
                val request =
                    PlaybackInfoRequestDto(
                        Id = itemId,
                        UserId = server.userId,
                        DeviceProfile = profile,
                        StartTimeTicks = startPositionTicks.coerceAtLeast(0L),
                        MediaSourceId = mediaSourceId,
                        CurrentPlaySessionId = playSessionId,
                        MaxStreamingBitrate = YFUSE_MAX_STREAMING_BITRATE_BPS,
                        MaxAudioChannels = capabilities.maxAudioChannels.coerceIn(2, 8),
                    )
                // Eager serialization avoids a deferred request-body serializer deadlock on an
                // unconfined UI/test dispatcher while the engine consumes the same body.
                val requestJson = playbackRequestJson.encodeToString(request)

                // Record only capability facts — never URLs, tokens, paths, titles, or request JSON.
                // This makes a future diagnostics bundle able to explain why Emby offered a
                // transcode without exposing the user's media/server credentials.
                AppLog.info(
                    category = "player.negotiation",
                    event = "playback_info_request",
                    message = "Requesting server playback capabilities",
                    attributes =
                        mapOf(
                            "mediaSourcePinned" to (!mediaSourceId.isNullOrBlank()).toString(),
                            "maxStreamingBitrate" to request.MaxStreamingBitrate.toString(),
                            "maxAudioChannels" to request.MaxAudioChannels.toString(),
                            "directPlayContainers" to
                                profile.DirectPlayProfiles.joinToString(";") { it.Container },
                            "directPlayVideoCodecs" to
                                profile.DirectPlayProfiles.joinToString(";") { it.VideoCodec },
                            "directPlayAudioCodecs" to
                                profile.DirectPlayProfiles.joinToString(";") { it.AudioCodec },
                            "hevcRanges" to
                                profile.CodecProfiles
                                    .firstOrNull { it.Type == "Video" && it.Codec == "hevc" }
                                    ?.Conditions
                                    ?.firstOrNull { it.Property == "VideoRangeType" }
                                    ?.Value
                                    .orEmpty(),
                        ),
                )

                val response: PlaybackInfoResponseDto =
                    client
                        .post("${normalizeBaseUrl(server.baseUrl)}/Items/$itemId/PlaybackInfo") {
                            header("X-Emby-Token", server.accessToken)
                            setBody(TextContent(requestJson, ContentType.Application.Json))
                        }.body()

                AppLog.info(
                    category = "player.negotiation",
                    event = "playback_info_response",
                    message = "Server playback capability negotiation completed",
                    attributes =
                        mapOf(
                            "sourceCount" to response.MediaSources.size.toString(),
                            "errorCode" to response.ErrorCode.orEmpty(),
                        ),
                )
                response.MediaSources.forEachIndexed { index, source ->
                    val serverRoute =
                        when {
                            source.SupportsDirectPlay == true -> "direct-play"
                            source.SupportsDirectStream == true -> "direct-stream"
                            source.SupportsTranscoding == true -> "transcode"
                            else -> "unsupported"
                        }
                    AppLog.info(
                        category = "player.negotiation",
                        event = "media_source_capabilities",
                        message = "Server returned media-source playback capabilities",
                        attributes =
                            mapOf(
                                "sourceIndex" to index.toString(),
                                "requestedSourceMatch" to
                                    (
                                        !mediaSourceId.isNullOrBlank() &&
                                            source.Id == mediaSourceId
                                    ).toString(),
                                "container" to source.Container.orEmpty(),
                                "videoType" to source.VideoType.orEmpty(),
                                "bitrate" to (source.Bitrate ?: 0).toString(),
                                "supportsDirectPlay" to (source.SupportsDirectPlay == true).toString(),
                                "supportsDirectStream" to (source.SupportsDirectStream == true).toString(),
                                "supportsTranscoding" to (source.SupportsTranscoding == true).toString(),
                                "serverRoute" to serverRoute,
                                "hasDirectStreamUrl" to (!source.DirectStreamUrl.isNullOrBlank()).toString(),
                                "hasTranscodingUrl" to (!source.TranscodingUrl.isNullOrBlank()).toString(),
                                "transcodingContainer" to source.TranscodingContainer.orEmpty(),
                                "transcodingProtocol" to source.TranscodingSubProtocol.orEmpty(),
                            ),
                    )
                }
                response
            }
        }

    suspend fun stopTranscoding(
        server: SavedServer,
        playSessionId: String,
    ): Result<Unit> =
        embyApiCall("stop_transcoding") {
            try {
                client.delete("${server.baseUrl}/Videos/ActiveEncodings") {
                    header("X-Emby-Token", server.accessToken)
                    parameter("DeviceId", deviceId())
                    parameter("PlaySessionId", playSessionId)
                }
            } catch (error: ResponseException) {
                if (error.response.status.value !in setOf(404, 410)) throw error
            }
        }

    private suspend fun reportPlayback(
        server: SavedServer,
        path: String,
        itemId: String,
        playSessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String,
    ): Result<Unit> =
        embyApiCall("report_playback") {
            client.post("${normalizeBaseUrl(server.baseUrl)}$path") {
                header("X-Emby-Token", server.accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    PlaybackReportDto(
                        ItemId = itemId,
                        PlaySessionId = playSessionId,
                        PositionTicks = positionTicks.coerceAtLeast(0L),
                        IsPaused = isPaused,
                        PlayMethod = playMethod,
                    ),
                )
            }
        }
}
