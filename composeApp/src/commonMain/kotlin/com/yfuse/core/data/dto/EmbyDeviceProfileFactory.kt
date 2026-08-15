package com.yfuse.core.data.dto

import com.yfuse.core.playback.PlaybackAudioCodec
import com.yfuse.core.playback.PlaybackDeviceCapabilities
import com.yfuse.core.playback.PlaybackHdrFormat
import com.yfuse.core.playback.PlaybackVideoCodec

/** Builds the server contract from observed capabilities instead of a model-wide allow-list. */
internal object EmbyDeviceProfileFactory {
    fun create(capabilities: PlaybackDeviceCapabilities): DeviceProfileDto {
        val videoCodecs =
            capabilities.videoDecoders.flatMapTo(
                linkedSetOf(),
                PlaybackVideoCodec::embyNames,
            )
        if (videoCodecs.isEmpty()) videoCodecs += "h264"
        if (capabilities.supportsDolbyVisionOutput) {
            capabilities.dolbyVisionBaseCodecs.forEach { codec ->
                videoCodecs += codec.embyNames
            }
        }
        val audioCodecs =
            capabilities.directPlayableAudio.flatMapTo(
                linkedSetOf(),
                PlaybackAudioCodec::embyNames,
            )
        if (audioCodecs.isEmpty()) audioCodecs += "aac"
        val maxAudioChannels = capabilities.maxAudioChannels.coerceIn(2, 8)
        return DeviceProfileDto(
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
            CodecProfiles = codecProfiles(capabilities, videoCodecs, maxAudioChannels),
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
            addAll(openHdrRangeTypes(capabilities, PlaybackVideoCodec.Hevc))
            if (
                capabilities.supportsHdrOutput(
                    PlaybackHdrFormat.Hdr10,
                    PlaybackVideoCodec.Hevc,
                )
            ) {
                add("DOVIWithHDR10")
            }
            if (
                capabilities.supportsHdrOutput(
                    PlaybackHdrFormat.Hdr10Plus,
                    PlaybackVideoCodec.Hevc,
                )
            ) {
                add("DOVIWithHDR10Plus")
            }
            if (
                capabilities.supportsDolbyVisionOutput &&
                PlaybackVideoCodec.Hevc in capabilities.dolbyVisionBaseCodecs
            ) {
                add("DOVI")
                add("DOVIWithEL")
                if (PlaybackHdrFormat.Hdr10Plus in capabilities.hdrFormats) {
                    add("DOVIWithELHDR10Plus")
                }
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
