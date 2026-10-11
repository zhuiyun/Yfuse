package com.yfuse.feature.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackEngineSlotTest {
    private fun item(id: String) = PlayerMediaItem(id, "https://example.test/$id", "", id)

    private fun input() = PlaybackEngineInput(listOf(item("first")), PlaybackHandoverSnapshot(0, 25L, true, 1f))

    @Test
    fun replacement_waits_for_real_release_and_uses_controls_and_queue_changed_while_waiting() =
        runTest {
            val released = CompletableDeferred<Unit>()
            val old = SlotTestEngine(input())
            val retirements = PlaybackEngineRetirements(backgroundScope, { if (it === old) released.await() })
            val slot = PlaybackEngineSlot(input(), this, retirements)
            slot.request(PlaybackEngineRequest(input()) { _, _ -> old })
            runCurrent()
            var actual: PlaybackEngineInput? = null
            val fresh = SlotTestEngine(input())
            slot.request(
                PlaybackEngineRequest(input()) { value, _ ->
                    actual = value
                    fresh
                },
            )
            runCurrent()
            assertNull(actual)
            val preparing = assertIs<PreparingVideoEngine>(slot.binding.value.engine)
            assertNull(slot.binding.value.crashOwner)
            preparing.appendItems(listOf(item("second")))
            preparing.selectItem(1)
            preparing.seekTo(123_456L)
            preparing.setSpeed(1.75f)
            preparing.pause()
            assertFalse(preparing.playbackRequested)
            preparing.play()
            assertTrue(preparing.playbackRequested)
            preparing.pause()
            released.complete(Unit)
            runCurrent()
            assertEquals(listOf("first", "second"), actual!!.items.map { it.id })
            assertEquals(1, actual!!.handover.itemIndex)
            assertEquals(123_456L, actual!!.handover.positionMs)
            assertEquals(1.75f, actual!!.handover.speed)
            assertFalse(actual!!.handover.playbackRequested)
            assertSame(fresh, slot.binding.value.engine)
            assertNotNull(slot.binding.value.crashOwner)
            slot.close()
        }

    @Test
    fun superseding_a_waiting_request_only_builds_the_latest_request() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val retirements = PlaybackEngineRetirements(backgroundScope, { release.await() })
            retirements.retire(SlotTestEngine(input()))
            val slot = PlaybackEngineSlot(input(), this, retirements)
            val built = mutableListOf<String>()
            slot.request(
                PlaybackEngineRequest(input()) { _, _ ->
                    built += "old"
                    SlotTestEngine(input())
                },
            )
            runCurrent()
            slot.request(
                PlaybackEngineRequest(input()) { _, _ ->
                    built += "new"
                    SlotTestEngine(input())
                },
            )
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(listOf("new"), built)
            slot.close()
        }

    @Test
    fun timeout_and_close_keep_the_release_barrier_for_a_new_player_root() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val retired = SlotTestEngine(input())
            val retirements = PlaybackEngineRetirements(backgroundScope, { if (it === retired) release.await() })
            retirements.retire(retired)
            val oldSlot = PlaybackEngineSlot(input(), this, retirements, waitTimeoutMs = 100L)
            var built = 0
            val request =
                PlaybackEngineRequest(input()) { _, _ ->
                    built++
                    SlotTestEngine(input())
                }
            oldSlot.request(request)
            runCurrent()
            advanceTimeBy(101L)
            runCurrent()
            val waiting = assertIs<PreparingVideoEngine>(oldSlot.binding.value.engine)
            assertNotNull(waiting.state.value.error)
            assertTrue(waiting.state.value.automaticFallbackBlocked)
            assertFalse(waiting.state.value.fallbacksExhausted)
            oldSlot.close()
            val newSlot = PlaybackEngineSlot(input(), this, retirements)
            newSlot.request(request)
            runCurrent()
            assertEquals(0, built)
            release.complete(Unit)
            runCurrent()
            assertEquals(1, built)
            newSlot.close()
        }

    @Test
    fun a_failed_release_never_allows_manual_retry_to_build_another_engine() =
        runTest {
            val retirements = PlaybackEngineRetirements(backgroundScope, { error("native destroy failed") })
            retirements.retire(SlotTestEngine(input()))
            val slot = PlaybackEngineSlot(input(), this, retirements)
            var built = false
            slot.request(
                PlaybackEngineRequest(input()) { _, _ ->
                    built = true
                    SlotTestEngine(input())
                },
            )
            runCurrent()
            assertNotNull(slot.binding.value.engine.state.value.error)
            slot.binding.value.engine
                .retry()
            runCurrent()
            assertFalse(built)
            assertNotNull(slot.binding.value.engine.state.value.error)
            slot.close()
        }

    @Test
    fun a_failed_serialized_release_is_resumed_before_a_new_root_can_play() =
        runTest {
            var cleaned = false
            var attempts = 0
            var releasedCallbacks = 0
            val retirements =
                PlaybackEngineRetirements(
                    backgroundScope,
                    release = {
                        attempts++
                        if (attempts == 1) error("decoder join timed out")
                        cleaned = true
                    },
                    completed = { cleaned },
                )
            val oldSlot =
                PlaybackEngineSlot(input(), this, retirements, onReleased = { _, _ -> releasedCallbacks++ })
            oldSlot.request(PlaybackEngineRequest(input()) { _, _ -> SlotTestEngine(input()) })
            runCurrent()
            oldSlot.close()
            runCurrent()
            assertEquals(1, attempts)
            assertEquals(0, releasedCallbacks)

            val otherInput = PlaybackEngineInput(listOf(item("previously-playable")), input().handover)
            val newSlot = PlaybackEngineSlot(otherInput, this, retirements)
            val fresh = SlotTestEngine(otherInput)
            newSlot.request(PlaybackEngineRequest(otherInput) { _, _ -> fresh })
            runCurrent()
            assertSame(fresh, newSlot.binding.value.engine)
            assertTrue(cleaned)
            assertEquals(2, attempts)
            assertEquals(1, releasedCallbacks)
            newSlot.close()
        }

    @Test
    fun retrying_cleanup_never_constructs_a_decoder_while_release_is_still_incomplete() =
        runTest {
            var attempts = 0
            val retirements =
                PlaybackEngineRetirements(backgroundScope, { attempts++ }, completed = { false })
            retirements.retire(SlotTestEngine(input()))
            runCurrent()
            val slot = PlaybackEngineSlot(input(), this, retirements)
            var built = 0
            slot.request(
                PlaybackEngineRequest(input()) { _, _ ->
                    built++
                    SlotTestEngine(input())
                },
            )
            runCurrent()
            assertEquals(2, attempts)
            assertNotNull(slot.binding.value.engine.state.value.error)
            slot.binding.value.engine
                .retry()
            runCurrent()
            assertEquals(3, attempts)
            assertEquals(0, built)
            assertNotNull(slot.binding.value.engine.state.value.error)
            slot.close()
        }

    @Test
    fun concurrent_roots_share_one_cleanup_retry_and_one_construction_lease() =
        runTest {
            val finish = CompletableDeferred<Unit>()
            var cleaned = false
            var attempts = 0
            val retired = SlotTestEngine(input())
            val retirements =
                PlaybackEngineRetirements(
                    backgroundScope,
                    release = {
                        if (it === retired) {
                            attempts++
                            if (attempts == 1) error("decoder join timed out")
                            finish.await()
                            cleaned = true
                        }
                    },
                    completed = { if (it === retired) cleaned else null },
                )
            retirements.retire(retired)
            runCurrent()
            val first = PlaybackEngineSlot(input(), this, retirements)
            val second = PlaybackEngineSlot(input(), this, retirements)
            val factoryFinish = CompletableDeferred<Unit>()
            var built = 0
            first.request(
                PlaybackEngineRequest(input()) { _, _ ->
                    built++
                    factoryFinish.await()
                    SlotTestEngine(input())
                },
            )
            second.request(
                PlaybackEngineRequest(input()) { _, _ ->
                    built++
                    SlotTestEngine(input())
                },
            )
            runCurrent()
            assertEquals(2, attempts)
            assertEquals(0, built)
            finish.complete(Unit)
            runCurrent()
            assertEquals(1, built)
            factoryFinish.complete(Unit)
            runCurrent()
            assertEquals(2, built)
            assertEquals(2, attempts)
            first.close()
            second.close()
        }

    @Test
    fun a_late_completed_release_clears_the_failed_attempt_without_releasing_twice() =
        runTest {
            var cleaned = false
            var attempts = 0
            var callbacks = 0
            val retired = SlotTestEngine(input())
            val retirements =
                PlaybackEngineRetirements(
                    backgroundScope,
                    release = {
                        attempts++
                        error("decoder join timed out")
                    },
                    completed = { cleaned },
                )
            retirements.retire(retired) { callbacks++ }
            runCurrent()
            cleaned = true
            val slot = PlaybackEngineSlot(input(), this, retirements)
            val fresh = SlotTestEngine(input())
            slot.request(PlaybackEngineRequest(input()) { _, _ -> fresh })
            runCurrent()
            assertSame(fresh, slot.binding.value.engine)
            assertEquals(1, attempts)
            assertEquals(1, callbacks)
            slot.close()
        }

    @Test
    fun a_timed_out_wait_does_not_cancel_cleanup_and_manual_retry_can_resume_playback() =
        runTest {
            val finish = CompletableDeferred<Unit>()
            var cleaned = false
            var attempts = 0
            val retired = SlotTestEngine(input())
            val retirements =
                PlaybackEngineRetirements(
                    backgroundScope,
                    release = {
                        if (it === retired) {
                            attempts++
                            if (attempts == 1) error("decoder join timed out")
                            finish.await()
                            cleaned = true
                        }
                    },
                    completed = { if (it === retired) cleaned else null },
                )
            retirements.retire(retired)
            runCurrent()
            val slot = PlaybackEngineSlot(input(), this, retirements, waitTimeoutMs = 100L)
            val fresh = SlotTestEngine(input())
            var built = 0
            slot.request(
                PlaybackEngineRequest(input()) { _, _ ->
                    built++
                    fresh
                },
            )
            runCurrent()
            advanceTimeBy(101L)
            runCurrent()
            assertNotNull(slot.binding.value.engine.state.value.error)
            assertEquals(0, built)
            assertEquals(2, attempts)
            finish.complete(Unit)
            runCurrent()
            assertTrue(cleaned)
            slot.binding.value.engine
                .retry()
            runCurrent()
            assertSame(fresh, slot.binding.value.engine)
            assertEquals(1, built)
            assertEquals(2, attempts)
            slot.close()
        }

    @Test
    fun a_noncooperative_factory_result_is_retired_when_the_slot_has_closed() =
        runTest {
            val finish = CompletableDeferred<Unit>()
            val retired = mutableListOf<VideoEngine>()
            val retirements = PlaybackEngineRetirements(backgroundScope, { retired += it })
            val candidate = SlotTestEngine(input())
            val slot = PlaybackEngineSlot(input(), this, retirements)
            slot.request(
                PlaybackEngineRequest(input()) { _, _ ->
                    withContext(NonCancellable) { finish.await() }
                    candidate
                },
            )
            runCurrent()
            slot.close()
            finish.complete(Unit)
            runCurrent()
            assertIs<PreparingVideoEngine>(slot.binding.value.engine)
            assertEquals<List<VideoEngine>>(listOf(candidate), retired)
        }

    @Test
    fun a_new_request_waits_for_a_noncooperative_factory_and_its_retirement() =
        runTest {
            val factoryFinished = CompletableDeferred<Unit>()
            val destroyFinished = CompletableDeferred<Unit>()
            val candidate = SlotTestEngine(input())
            val retirements =
                PlaybackEngineRetirements(backgroundScope, {
                    if (it ===
                        candidate
                    ) {
                        destroyFinished.await()
                    }
                })
            val slot = PlaybackEngineSlot(input(), this, retirements)
            slot.request(
                PlaybackEngineRequest(input()) { _, _ ->
                    withContext(NonCancellable) { factoryFinished.await() }
                    candidate
                },
            )
            runCurrent()
            var built = false
            slot.request(
                PlaybackEngineRequest(input()) { _, _ ->
                    built = true
                    SlotTestEngine(input())
                },
            )
            runCurrent()
            assertFalse(built)
            factoryFinished.complete(Unit)
            runCurrent()
            assertFalse(built)
            destroyFinished.complete(Unit)
            runCurrent()
            assertTrue(built)
            slot.close()
        }

    @Test
    fun a_new_root_also_waits_for_the_closed_roots_noncooperative_factory() =
        runTest {
            val factoryFinished = CompletableDeferred<Unit>()
            val destroyFinished = CompletableDeferred<Unit>()
            val candidate = SlotTestEngine(input())
            val retirements =
                PlaybackEngineRetirements(backgroundScope, {
                    if (it ===
                        candidate
                    ) {
                        destroyFinished.await()
                    }
                })
            val oldSlot = PlaybackEngineSlot(input(), this, retirements)
            oldSlot.request(
                PlaybackEngineRequest(input()) { _, _ ->
                    withContext(NonCancellable) { factoryFinished.await() }
                    candidate
                },
            )
            runCurrent()
            oldSlot.close()
            val newSlot = PlaybackEngineSlot(input(), this, retirements)
            var built = false
            newSlot.request(
                PlaybackEngineRequest(input()) { _, _ ->
                    built = true
                    SlotTestEngine(input())
                },
            )
            runCurrent()
            assertFalse(built)
            factoryFinished.complete(Unit)
            runCurrent()
            assertFalse(built)
            destroyFinished.complete(Unit)
            runCurrent()
            assertTrue(built)
            newSlot.close()
        }

    @Test
    fun crash_owner_is_not_disarmed_until_its_resources_are_released() =
        runTest {
            val finish = CompletableDeferred<Unit>()
            val retirements = PlaybackEngineRetirements(backgroundScope, { finish.await() })
            val disarmed = mutableListOf<String?>()
            val slot =
                PlaybackEngineSlot(
                    input(),
                    this,
                    retirements,
                    newOwner = { "first-owner" },
                    onReleased = { binding, _ -> disarmed += binding.crashOwner },
                )
            slot.request(PlaybackEngineRequest(input()) { _, _ -> SlotTestEngine(input()) })
            runCurrent()
            slot.close()
            runCurrent()
            assertTrue(disarmed.isEmpty())
            finish.complete(Unit)
            runCurrent()
            assertEquals<List<String?>>(listOf("first-owner"), disarmed)
        }

    @Test
    fun successful_rendering_evidence_is_frozen_before_release_clears_the_state() =
        runTest {
            val finish = CompletableDeferred<Unit>()
            val state =
                MutableStateFlow(
                    PlaybackState(
                        diagnostics = PlaybackDiagnostics(videoReadiness = PlaybackOutputReadiness.Rendering),
                    ),
                )
            val actual =
                object : VideoEngine by PreparingVideoEngine(input()) {
                    override val state = state
                }
            val retirements =
                PlaybackEngineRetirements(backgroundScope, {
                    state.value = PlaybackState()
                    finish.await()
                })
            val results = mutableListOf<Boolean>()
            val slot =
                PlaybackEngineSlot(input(), this, retirements, onReleased = {
                        _,
                        successful,
                    ->
                    results += successful
                })
            slot.request(PlaybackEngineRequest(input()) { _, _ -> actual })
            runCurrent()
            slot.close()
            runCurrent()
            assertTrue(results.isEmpty())
            finish.complete(Unit)
            runCurrent()
            assertEquals(listOf(true), results)
        }

    @Test
    fun a_failed_owner_diagnostic_cleanup_does_not_reserve_resources_that_were_never_created() =
        runTest {
            val retirements = PlaybackEngineRetirements(backgroundScope, {})
            val slot =
                PlaybackEngineSlot(
                    input(),
                    this,
                    retirements,
                    onAbandonedOwner = { error("diagnostic storage unavailable") },
                )
            slot.request(
                PlaybackEngineRequest(input()) { _, _ -> error("construction failed before returning an engine") },
            )
            runCurrent()
            assertNotNull(slot.binding.value.engine.state.value.error)
            var built = false
            slot.request(
                PlaybackEngineRequest(input()) { _, _ ->
                    built = true
                    SlotTestEngine(input())
                },
            )
            runCurrent()
            assertTrue(built)
            slot.close()
        }
}

private class SlotTestEngine(
    input: PlaybackEngineInput,
) : VideoEngine by PreparingVideoEngine(input)
