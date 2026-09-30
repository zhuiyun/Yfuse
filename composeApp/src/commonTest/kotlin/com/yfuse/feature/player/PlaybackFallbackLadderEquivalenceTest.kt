package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlaybackRuntimeFaultKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every question today's engines and PlayerRoot answer, asked of [PlaybackFallbackLadder] too:
 * the tables pin the cases worth naming, this proves there is no other case in which they differ.
 */
class PlaybackFallbackLadderEquivalenceTest {
    private val today = TodayFallbackLadders
    private val ladder = ConvergedFallbackLadders

    private val urls = listOf("", " ", "u")

    private val items: List<PlayerMediaItem?> =
        buildList {
            add(null)
            for (hls in urls) {
                for (mp4 in urls) {
                    for (approved in listOf(false, true)) {
                        for (versionApproved in listOf(false, true)) {
                            for (method in listOf(PlaybackMethod.DirectPlay, PlaybackMethod.Transcode)) {
                                for (dolby in 0..3) {
                                    add(
                                        ladderItem(
                                            hls = hls,
                                            mp4 = mp4,
                                            approved = approved,
                                            versionApproved = versionApproved,
                                            playMethod = method,
                                            dolbyVision = dolby == 1 || dolby == 3,
                                            dolbyAtmos = dolby == 2,
                                            disc = dolby == 3,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

    private val reasons = listOf(null, "解码失败", "用户手动选择服务器转码")

    @Test
    fun the_stream_ladder_answers_every_engine_question_as_the_engines_do_today() {
        var questions = 0
        for (sets in StreamSets.reachable) {
            for (item in items) {
                val case = "$sets $item"
                assertEquals(today.exoProgressive(sets, item), ladder.exoProgressive(sets, item), case)
                assertEquals(
                    today.exoAfterTransportFailure(sets, item),
                    ladder.exoAfterTransportFailure(sets, item),
                    case,
                )
                for (reason in reasons) {
                    assertEquals(today.exoNext(sets, item, reason), ladder.exoNext(sets, item, reason), "$case $reason")
                    assertEquals(today.mpvNext(sets, item, reason), ladder.mpvNext(sets, item, reason), "$case $reason")
                    assertEquals(today.mdkNext(sets, item, reason), ladder.mdkNext(sets, item, reason), "$case $reason")
                    questions += 3
                }
                questions += 2
            }
        }
        assertTrue(questions > 10_000)
    }

    private data class FaultCase(
        val fault: PlaybackRuntimeFaultKind,
        val reopens: Int,
        val nativeOnly: Boolean,
        val restarts: Int,
        val adapter: Boolean,
        val disabled: Boolean,
        val order: List<PlayerEngine>,
        val tried: Set<PlayerEngine>,
        val kind: PlayerEngine,
        val serverStream: Boolean,
        val transcoding: Boolean,
    ) {
        fun askedOf(ladder: FallbackLadders) =
            ladder.runtimeFault(
                fault,
                reopens,
                nativeOnly,
                restarts,
                adapter,
                disabled,
                order,
                tried,
                kind,
                serverStream,
                transcoding,
            )
    }

    private fun faultCases(): List<FaultCase> {
        val flags = listOf(false, true)
        val orders =
            listOf(
                emptyList(),
                listOf(PlayerEngine.Exo),
                listOf(PlayerEngine.Exo, PlayerEngine.Mpv, PlayerEngine.Mdk),
                listOf(PlayerEngine.Mpv, PlayerEngine.Exo),
                listOf(PlayerEngine.Mdk),
            )
        val triedSets = listOf(emptySet(), setOf(PlayerEngine.Mpv), PlayerEngine.entries.toSet())
        var cases =
            PlaybackRuntimeFaultKind.entries.map {
                FaultCase(it, 0, false, 0, false, false, emptyList(), emptySet(), PlayerEngine.Exo, false, false)
            }

        fun <T> vary(
            values: Iterable<T>,
            apply: FaultCase.(T) -> FaultCase,
        ) {
            cases = cases.flatMap { case -> values.map { case.apply(it) } }
        }
        vary(0..3) { copy(reopens = it) }
        vary(flags) { copy(nativeOnly = it) }
        vary(0..3) { copy(restarts = it) }
        vary(flags) { copy(adapter = it) }
        vary(flags) { copy(disabled = it) }
        vary(orders) { copy(order = it) }
        vary(triedSets) { copy(tried = it) }
        vary(PlayerEngine.entries) { copy(kind = it) }
        vary(flags) { copy(serverStream = it) }
        vary(flags) { copy(transcoding = it) }
        return cases
    }

    @Test
    fun the_runtime_fault_ladder_answers_every_fault_as_player_root_does_today() {
        val cases = faultCases()
        for (case in cases) {
            val expected = case.askedOf(today)
            val actual = case.askedOf(ladder)
            if (expected != actual) fail("$case: today $expected, ladder $actual")
        }
        assertTrue(cases.size > 100_000)
    }

    @Test
    fun the_budget_reset_and_retry_limits_agree_with_today() {
        for (position in listOf(0L, 29_999L, 30_000L, 45_000L, 60_000L)) {
            for (last in listOf(0L, 15_000L, 30_000L)) {
                assertEquals(
                    today.restoresRecoveryBudget(position, last),
                    ladder.restoresRecoveryBudget(position, last),
                )
            }
        }
        assertEquals(today.transientRetryLimit, ladder.transientRetryLimit)
        for (attempt in 1..today.transientRetryLimit) {
            assertEquals(today.transientRetryDelayMs(attempt), ladder.transientRetryDelayMs(attempt))
        }
    }
}
