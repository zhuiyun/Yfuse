package com.yfuse.feature.player

import com.yfuse.core.data.PlaybackAudioPassthrough
import com.yfuse.core.data.PlaybackFrameRateMatch

internal fun PlaybackFrameRateMatch.toPlayerMode(): FrameRateMatchMode =
    when (this) {
        PlaybackFrameRateMatch.Disabled -> FrameRateMatchMode.Disabled
        PlaybackFrameRateMatch.SeamlessOnly -> FrameRateMatchMode.SeamlessOnly
        PlaybackFrameRateMatch.Always -> FrameRateMatchMode.Always
    }

internal fun FrameRateMatchMode.toPreference(): PlaybackFrameRateMatch =
    when (this) {
        FrameRateMatchMode.Disabled -> PlaybackFrameRateMatch.Disabled
        FrameRateMatchMode.SeamlessOnly -> PlaybackFrameRateMatch.SeamlessOnly
        FrameRateMatchMode.Always -> PlaybackFrameRateMatch.Always
    }

internal fun PlaybackAudioPassthrough.toPlayerMode(): AudioPassthroughMode =
    when (this) {
        PlaybackAudioPassthrough.Disabled -> AudioPassthroughMode.Disabled
        PlaybackAudioPassthrough.Compatible -> AudioPassthroughMode.Compatible
    }

internal fun AudioPassthroughMode.toPreference(): PlaybackAudioPassthrough =
    when (this) {
        AudioPassthroughMode.Disabled -> PlaybackAudioPassthrough.Disabled
        AudioPassthroughMode.Compatible -> PlaybackAudioPassthrough.Compatible
    }
