package com.yfuse.core.playback

/** Media3 load-control values selected from the same user intent as the engine route. */
data class PlaybackBufferProfile(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val playbackStartMs: Int,
    val rebufferStartMs: Int,
    val backBufferMs: Int,
    val targetBufferBytes: Int,
)

fun playbackBufferProfile(mode: PlaybackOptimizationMode): PlaybackBufferProfile =
    when (mode) {
        PlaybackOptimizationMode.Balanced ->
            PlaybackBufferProfile(
                minBufferMs = 20_000,
                maxBufferMs = 90_000,
                playbackStartMs = 1_500,
                rebufferStartMs = 3_500,
                backBufferMs = 10_000,
                targetBufferBytes = 96 * MEBIBYTE,
            )
        PlaybackOptimizationMode.PowerSaver ->
            PlaybackBufferProfile(
                minBufferMs = 12_000,
                maxBufferMs = 45_000,
                playbackStartMs = 1_200,
                rebufferStartMs = 3_000,
                backBufferMs = 0,
                targetBufferBytes = 64 * MEBIBYTE,
            )
        PlaybackOptimizationMode.Quality ->
            PlaybackBufferProfile(
                minBufferMs = 30_000,
                maxBufferMs = 120_000,
                playbackStartMs = 1_500,
                rebufferStartMs = 4_000,
                backBufferMs = 15_000,
                targetBufferBytes = 192 * MEBIBYTE,
            )
        PlaybackOptimizationMode.Compatibility ->
            PlaybackBufferProfile(
                minBufferMs = 20_000,
                maxBufferMs = 90_000,
                playbackStartMs = 2_000,
                rebufferStartMs = 4_000,
                backBufferMs = 10_000,
                targetBufferBytes = 128 * MEBIBYTE,
            )
    }

/** libmpv demuxer bounds selected from the same optimization intent. */
data class MpvBufferProfile(
    val forwardBytes: Int,
    val backBytes: Int,
    val readaheadSeconds: Int,
)

fun mpvBufferProfile(mode: PlaybackOptimizationMode): MpvBufferProfile =
    when (mode) {
        PlaybackOptimizationMode.PowerSaver ->
            MpvBufferProfile(
                forwardBytes = 32 * MEBIBYTE,
                backBytes = 0,
                readaheadSeconds = 12,
            )
        PlaybackOptimizationMode.Balanced ->
            MpvBufferProfile(
                forwardBytes = 64 * MEBIBYTE,
                backBytes = 16 * MEBIBYTE,
                readaheadSeconds = 20,
            )
        PlaybackOptimizationMode.Quality ->
            MpvBufferProfile(
                forwardBytes = 128 * MEBIBYTE,
                backBytes = 32 * MEBIBYTE,
                readaheadSeconds = 30,
            )
        PlaybackOptimizationMode.Compatibility ->
            MpvBufferProfile(
                forwardBytes = 96 * MEBIBYTE,
                backBytes = 16 * MEBIBYTE,
                readaheadSeconds = 24,
            )
    }

private const val MEBIBYTE = 1024 * 1024
