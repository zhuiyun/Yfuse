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
                maxBufferMs = 300_000,
                playbackStartMs = 1_500,
                rebufferStartMs = 3_500,
                backBufferMs = 10_000,
                targetBufferBytes = 512 * MEBIBYTE,
            )
        PlaybackOptimizationMode.PowerSaver ->
            PlaybackBufferProfile(
                minBufferMs = 12_000,
                maxBufferMs = 120_000,
                playbackStartMs = 1_200,
                rebufferStartMs = 3_000,
                backBufferMs = 0,
                targetBufferBytes = 256 * MEBIBYTE,
            )
        PlaybackOptimizationMode.Quality ->
            PlaybackBufferProfile(
                minBufferMs = 30_000,
                maxBufferMs = 300_000,
                playbackStartMs = 1_500,
                rebufferStartMs = 4_000,
                backBufferMs = 15_000,
                targetBufferBytes = 1024 * MEBIBYTE,
            )
        PlaybackOptimizationMode.Compatibility ->
            PlaybackBufferProfile(
                minBufferMs = 20_000,
                maxBufferMs = 300_000,
                playbackStartMs = 2_000,
                rebufferStartMs = 4_000,
                backBufferMs = 10_000,
                targetBufferBytes = 512 * MEBIBYTE,
            )
    }

/** libmpv demuxer bounds selected from the same optimization intent. */
data class MpvBufferProfile(
    val forwardBytes: Int,
    val backBytes: Int,
    val readaheadSeconds: Int,
)

fun mpvBufferProfile(
    mode: PlaybackOptimizationMode,
    memoryBudgetBytes: Long = Long.MAX_VALUE,
): MpvBufferProfile {
    require(memoryBudgetBytes > 0L)
    val profile =
        when (mode) {
            PlaybackOptimizationMode.PowerSaver ->
                MpvBufferProfile(
                    forwardBytes = 256 * MEBIBYTE,
                    backBytes = 0,
                    readaheadSeconds = 120,
                )
            PlaybackOptimizationMode.Balanced ->
                MpvBufferProfile(
                    forwardBytes = 512 * MEBIBYTE,
                    backBytes = 16 * MEBIBYTE,
                    readaheadSeconds = 300,
                )
            PlaybackOptimizationMode.Quality ->
                MpvBufferProfile(
                    forwardBytes = 1024 * MEBIBYTE,
                    backBytes = 32 * MEBIBYTE,
                    readaheadSeconds = 300,
                )
            PlaybackOptimizationMode.Compatibility ->
                MpvBufferProfile(
                    forwardBytes = 512 * MEBIBYTE,
                    backBytes = 16 * MEBIBYTE,
                    readaheadSeconds = 300,
                )
        }
    // Reserve only the requested history, then give the remainder to forward buffering.
    // A fixed 75% forward split wasted the other 25% when back buffering was off or small.
    val backBytes = minOf(profile.backBytes.toLong(), memoryBudgetBytes / 4L)
    val forwardBytes = minOf(profile.forwardBytes.toLong(), memoryBudgetBytes - backBytes)
    return profile.copy(forwardBytes = forwardBytes.toInt(), backBytes = backBytes.toInt())
}

private const val MEBIBYTE = 1024 * 1024
