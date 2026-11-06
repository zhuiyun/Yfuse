package com.yfuse.core2.android

import android.media.MediaFormat
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackFailureStage
import com.yfuse.core2.api.YTrack
import com.yfuse.core2.api.matchingPreference
import com.yfuse.core2.capability.YAudioOutputPath
import com.yfuse.core2.capability.YAudioRequirement

internal data class NativeDirectInitialAudio(
    val track: Pair<Int, MediaFormat>? = null,
    val discovered: List<YTrack> = emptyList(),
)

/** Disabled previews never discover/select audio; every prepare/retry uses this same policy. */
internal fun selectNativeDirectInitialAudio(
    audioDisabled: Boolean,
    item: YMediaItem,
    trackCount: () -> Int,
    trackFormat: (Int) -> MediaFormat,
    plannedAudioOutputPath: YAudioOutputPath?,
    devicePath: (YAudioRequirement) -> YAudioOutputPath,
): NativeDirectInitialAudio {
    if (audioDisabled) return NativeDirectInitialAudio()
    val count = trackCount()
    val indices =
        (0 until count).filter {
            trackFormat(
                it,
            ).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
        }
    val discovered = platformAudioTracks(count, trackFormat)
    val preferred =
        discovered
            .matchingPreference(item.initialTrackSelection?.audio)
            ?.id
            ?.substringAfter(':')
            ?.toIntOrNull()
            ?.takeUnless { trackFormat(it).aacLabelWithoutConfig() }
    val candidates = preferred?.let(::listOf) ?: indices
    val selected =
        candidates.firstNotNullOfOrNull { index ->
            val format = trackFormat(index)
            if (format.aacLabelWithoutConfig()) {
                logAacLabelWithoutConfig("NativeDirect", discovered, index, item.sourceHints)
                return@firstNotNullOfOrNull null
            }
            val coreFormat =
                runCatching { format.toCore2AudioTrackFormat(item.sourceHints) }.getOrNull()
                    ?: return@firstNotNullOfOrNull null
            val output = devicePath(YAudioRequirement(coreFormat.codec, coreFormat.channelCount, coreFormat.sampleRate))
            val playable =
                if (plannedAudioOutputPath == YAudioOutputPath.DecodePcm) {
                    output == YAudioOutputPath.DecodePcm
                } else {
                    output != YAudioOutputPath.None
                }
            index.takeIf { playable }?.let { it to format }
        }
    if ((item.sourceHints?.audioTrackCount ?: 0) > 0 && indices.isEmpty()) {
        throw YPlaybackException(
            category = YPlaybackFailureCategory.Container,
            stage = YPlaybackFailureStage.Demux,
            safeDetail = hiddenServerAudioTrackDetail(NATIVE_DIRECT_HIDDEN_AUDIO_DETAIL, item.sourceHints),
        )
    }
    if (indices.isNotEmpty() && selected == null) {
        val rejected =
            indices
                .mapNotNull { index ->
                    val format = trackFormat(index)
                    format.getString(MediaFormat.KEY_MIME)?.lowercase()?.let { mime ->
                        if (format.aacLabelWithoutConfig()) "$mime without AudioSpecificConfig" else mime
                    }
                }.distinct()
                .joinToString(",")
        throw YPlaybackException(
            category = YPlaybackFailureCategory.Container,
            stage = YPlaybackFailureStage.Demux,
            safeDetail = "NativeDirect has no playable platform audio path for $rejected",
        )
    }
    return NativeDirectInitialAudio(selected, discovered)
}
