package com.yfuse.core.data.dto

import com.yfuse.core.playback.PlaybackAudioCodec
import com.yfuse.core.playback.PlaybackDeviceCapabilities
import com.yfuse.core.playback.PlaybackHdrFormat
import com.yfuse.core.playback.PlaybackVideoCodec

/**
 * PlaybackInfo is capability negotiation, not Yfuse's adaptive network limiter. Keep the server
 * ceiling above UHD Blu-ray/remux peak bitrates so an original/Auto request is not silently changed
 * into transcoding merely because file metadata exceeds the old 120 Mbps profile cap.
 */
internal const val YFUSE_MAX_STREAMING_BITRATE_BPS = 1_000_000_000L

/**
 * Builds the server contract for what Yfuse can ingest locally.
 *
 * Android hardware/display capabilities still refine native passthrough, but they are not the whole
 * client: the packaged mpv + FFmpeg + libplacebo path can decode HEVC/Dolby Vision and map it to the
 * current display. If the profile describes only the panel/MediaCodec surface, Emby sees a P7/FEL
 * source as unsupported and offers a server transcode even though Yfuse deliberately opens the
 * original URL and performs the Dolby pipeline locally.
 */
internal object EmbyDeviceProfileFactory {
    fun create(capabilities: PlaybackDeviceCapabilities): DeviceProfileDto {
        val videoCodecs =
            (capabilities.videoDecoders + LOCAL_VIDEO_DECODERS).flatMapTo(
                linkedSetOf(),
                PlaybackVideoCodec::embyNames,
            )
        if (videoCodecs.isEmpty()) videoCodecs += "h264"
        if (capabilities.supportsDolbyVisionOutput) {
            capabilities.dolbyVisionBaseCodecs.forEach { codec ->
                videoCodecs += codec.embyNames
            }
        }
        // The packaged native FFmpeg path can decode these formats to PCM even when Android's
        // current AudioTrack route cannot passthrough them. Advertising only the active route made
        // Emby start an unnecessary server audio/video transcode for Atmos and TrueHD sources.
        val audioCodecs =
            (capabilities.directPlayableAudio + LOCAL_AUDIO_DECODERS).flatMapTo(
                linkedSetOf(),
                PlaybackAudioCodec::embyNames,
            )
        if (audioCodecs.isEmpty()) audioCodecs += "aac"
        val maxAudioChannels = capabilities.maxAudioChannels.coerceIn(2, 8)
        return DeviceProfileDto(
            MaxStreamingBitrate = YFUSE_MAX_STREAMING_BITRATE_BPS,
            DirectPlayProfiles =
                listOf(
                    DirectPlayProfileDto(
                        Container = DIRECT_PLAY_VIDEO_CONTAINERS,
                        VideoCodec = videoCodecs.joinToString(","),
                        AudioCodec = audioCodecs.joinToString(","),
                    ),
                ),
            TranscodingProfiles =
                listOf(
                    TranscodingProfileDto(
                        Container = "ts",
                        VideoCodec = "h264",
                        AudioCodec = "aac",
                        Protocol = "hls",
                        MaxAudioChannels = maxAudioChannels.toString(),
                    ),
                    TranscodingProfileDto(
                        Container = "mp4",
                        VideoCodec = "h264",
                        AudioCodec = "aac",
                        Protocol = "http",
                        MaxAudioChannels = maxAudioChannels.toString(),
                    ),
                ),
            CodecProfiles = codecProfiles(capabilities, videoCodecs, LOCAL_DECODE_MAX_CHANNELS),
            SubtitleProfiles = subtitleProfiles(),
        )
    }

    private fun codecProfiles(
        capabilities: PlaybackDeviceCapabilities,
        videoCodecs: Set<String>,
        maxAudioChannels: Int,
    ): List<CodecProfileDto> =
        buildList {
            if ("h264" in videoCodecs) {
                val h264Ranges =
                    buildSet {
                        add("SDR")
                        if (
                            capabilities.supportsDolbyVisionOutput &&
                            PlaybackVideoCodec.H264 in capabilities.dolbyVisionBaseCodecs
                        ) {
                            add("DOVI")
                        }
                    }
                add(videoRangeProfile("h264", h264Ranges))
            }
            if (videoCodecs.any { it == "hevc" || it == "h265" }) {
                add(videoRangeProfile("hevc", hevcRangeTypes(capabilities)))
            }
            if ("vp9" in videoCodecs) {
                add(
                    videoRangeProfile(
                        "vp9",
                        openHdrRangeTypes(capabilities, PlaybackVideoCodec.Vp9),
                    ),
                )
            }
            if ("av1" in videoCodecs) {
                add(
                    videoRangeProfile(
                        "av1",
                        openHdrRangeTypes(capabilities, PlaybackVideoCodec.Av1),
                    ),
                )
            }
            listOf("vp8", "mpeg2video", "mpeg4", "vc1")
                .filter(videoCodecs::contains)
                .forEach { codec -> add(videoRangeProfile(codec, setOf("SDR"))) }
            add(
                CodecProfileDto(
                    Type = "VideoAudio",
                    Conditions =
                        listOf(
                            ProfileConditionDto(
                                Condition = "LessThanEqual",
                                Property = "AudioChannels",
                                Value = maxAudioChannels.toString(),
                            ),
                        ),
                ),
            )
        }

    private fun videoRangeProfile(
        codec: String,
        rangeTypes: Set<String>,
    ): CodecProfileDto =
        CodecProfileDto(
            Type = "Video",
            Codec = codec,
            Conditions =
                listOf(
                    ProfileConditionDto(
                        Condition = "EqualsAny",
                        Property = "VideoRangeType",
                        Value = rangeTypes.joinToString("|"),
                    ),
                ),
        )

    private fun hevcRangeTypes(capabilities: PlaybackDeviceCapabilities): Set<String> =
        buildSet {
            // These are input formats the local mpv/libplacebo path can consume. Native panel HDR
            // support decides whether the final frame is passthrough or tone-mapped; it must not
            // decide whether Emby is allowed to send the original HEVC/Dolby bitstream at all.
            addAll(LOCAL_HEVC_INPUT_RANGE_TYPES)
            addAll(openHdrRangeTypes(capabilities, PlaybackVideoCodec.Hevc))
            if (
                capabilities.supportsHdrOutput(
                    PlaybackHdrFormat.Hdr10Plus,
                    PlaybackVideoCodec.Hevc,
                ) || PlaybackHdrFormat.Hdr10Plus in capabilities.hdrFormats
            ) {
                add("DOVIWithELHDR10Plus")
            }
        }

    private fun openHdrRangeTypes(
        capabilities: PlaybackDeviceCapabilities,
        codec: PlaybackVideoCodec,
    ): Set<String> =
        buildSet {
            add("SDR")
            if (capabilities.supportsHdrOutput(PlaybackHdrFormat.Hdr10, codec)) add("HDR10")
            if (capabilities.supportsHdrOutput(PlaybackHdrFormat.Hdr10Plus, codec)) {
                add("HDR10Plus")
            }
            if (capabilities.supportsHdrOutput(PlaybackHdrFormat.Hlg, codec)) add("HLG")
        }

    private fun subtitleProfiles(): List<SubtitleProfileDto> =
        listOf(
            SubtitleProfileDto("srt", "External"),
            SubtitleProfileDto("vtt", "External"),
            SubtitleProfileDto("subrip", "External"),
            SubtitleProfileDto("ass", "Embed"),
            SubtitleProfileDto("ssa", "Embed"),
            SubtitleProfileDto("pgs", "Embed"),
            SubtitleProfileDto("pgssub", "Embed"),
            SubtitleProfileDto("dvdsub", "Embed"),
            SubtitleProfileDto("dvbsub", "Embed"),
        )
}

private const val DIRECT_PLAY_VIDEO_CONTAINERS = "mkv,mp4,m4v,mov,ts,m2ts,webm"
private const val LOCAL_DECODE_MAX_CHANNELS = 8
private val LOCAL_VIDEO_DECODERS =
    setOf(
        PlaybackVideoCodec.H264,
        PlaybackVideoCodec.Hevc,
    )
private val LOCAL_HEVC_INPUT_RANGE_TYPES =
    setOf(
        "SDR",
        "HDR10",
        "HDR10Plus",
        "HLG",
        "DOVI",
        "DOVIWithHDR10",
        "DOVIWithHDR10Plus",
        "DOVIWithEL",
    )
private val LOCAL_AUDIO_DECODERS =
    setOf(
        PlaybackAudioCodec.Aac,
        PlaybackAudioCodec.Mp3,
        PlaybackAudioCodec.Ac3,
        PlaybackAudioCodec.Eac3,
        PlaybackAudioCodec.Eac3Joc,
        PlaybackAudioCodec.TrueHd,
        PlaybackAudioCodec.Dts,
        PlaybackAudioCodec.DtsHd,
        PlaybackAudioCodec.Flac,
        PlaybackAudioCodec.Opus,
        PlaybackAudioCodec.Vorbis,
        PlaybackAudioCodec.Pcm,
    )
