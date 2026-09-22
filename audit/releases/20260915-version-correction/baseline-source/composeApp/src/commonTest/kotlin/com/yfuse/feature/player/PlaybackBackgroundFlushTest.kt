package com.yfuse.feature.player

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackBackgroundFlushTest {
    @Test
    fun backgroundFlushesLatestSampleBeforePeriodicThreshold() =
        runTest {
            val progressTicks = mutableListOf<Long>()
            val sink =
                object : PlaybackEventSink {
                    override suspend fun started(
                        itemId: String,
                        sessionId: String,
                        positionTicks: Long,
                        isPaused: Boolean,
                    ) = Unit

                    override suspend fun progress(
                        itemId: String,
                        sessionId: String,
                        positionTicks: Long,
                        isPaused: Boolean,
                    ) {
                        progressTicks += positionTicks
                    }

                    override suspend fun stopped(
                        itemId: String,
                        sessionId: String,
                        positionTicks: Long,
                        isPaused: Boolean,
                    ) = Unit
                }
            val reporter =
                PlaybackProgressReporter(
                    items = listOf(PlayerMediaItem("movie", "direct", "hls", "电影")),
                    sink = sink,
                    scope = this,
                    sourcePreloader = null,
                    playbackSync = null,
                )

            reporter.update(
                PlaybackState(
                    playing = true,
                    positionMs = 1_000L,
                    durationMs = 100_000L,
                ),
            )
            runCurrent()

            // Only one second moved: neither the 5-second seek threshold nor the 10-second
            // periodic threshold should enqueue an ordinary progress report.
            reporter.update(
                PlaybackState(
                    playing = true,
                    positionMs = 2_000L,
                    durationMs = 100_000L,
                ),
            )
            runCurrent()
            assertEquals(emptyList(), progressTicks)

            notifyPlaybackAppBackground()
            runCurrent()
            assertEquals(listOf(20_000_000L), progressTicks)

            reporter.close(
                PlaybackState(
                    playing = false,
                    positionMs = 2_000L,
                    durationMs = 100_000L,
                ),
            )
            runCurrent()
        }
}
