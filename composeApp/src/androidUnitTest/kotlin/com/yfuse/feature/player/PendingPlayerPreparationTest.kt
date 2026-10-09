package com.yfuse.feature.player

import com.arkivanov.mvikotlin.core.rx.Disposable
import com.arkivanov.mvikotlin.core.rx.Observer
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PendingPlayerPreparationTest {
    @Test
    fun only_current_preparation_is_published_once_and_retained_for_enrichment() =
        runTest {
            val pending = pending()
            val model = PlayerLaunchViewModel().apply { this.pending = pending }
            val ready = request()

            assertTrue(model.completePreparation(pending, ready, stopping = false))
            assertSame(ready, model.request)
            assertNull(model.pending)
            assertSame(pending, model.enriching)
            assertFalse(pending.store.isDisposed)
            assertFalse(model.completePreparation(pending, request(), stopping = false))
            assertSame(ready, model.request)
        }

    @Test
    fun late_preparation_a_cannot_replace_pending_b() =
        runTest {
            val first = pending()
            val second = pending()
            val model = PlayerLaunchViewModel().apply { pending = first }
            model.disposePending()
            model.pending = second

            assertFalse(model.completePreparation(first, request(), stopping = false))
            assertTrue(first.store.isDisposed)
            assertSame(second, model.pending)
            assertNull(model.request)
            assertNull(model.enriching)
            assertFalse(second.store.isDisposed)
            assertTrue(model.completePreparation(second, request(), stopping = false))
        }

    @Test
    fun late_preparation_cannot_replace_an_already_ready_launch() =
        runTest {
            val pending = pending()
            val model = PlayerLaunchViewModel().apply { this.pending = pending }
            val replacement = request()
            model.disposePending()
            model.request = replacement

            assertTrue(pending.store.isDisposed)
            assertFalse(model.completePreparation(pending, request(), stopping = false))
            assertSame(replacement, model.request)
            assertNull(model.pending)
            assertNull(model.enriching)
        }

    @Test
    fun committing_exit_blocks_preparation_before_activity_destruction() =
        runTest {
            val pending = pending()
            val model = PlayerLaunchViewModel().apply { this.pending = pending }

            assertFalse(model.completePreparation(pending, request(), stopping = true))
            assertNull(model.request)
            assertNull(model.enriching)
            // The closing preparation UI can keep observing its store until ViewModel disposal.
            assertSame(pending, model.pending)
            assertFalse(pending.store.isDisposed)
        }

    @Test
    fun cancelled_preparation_cannot_publish_even_if_its_probe_returns_a_fallback() =
        runTest {
            val pending = pending()
            val model = PlayerLaunchViewModel().apply { this.pending = pending }
            val preparation =
                async {
                    currentCoroutineContext().job.cancel()
                    model.completePreparation(pending, request(), stopping = false)
                }

            assertFailsWith<CancellationException> { preparation.await() }
            assertNull(model.request)
            assertNull(model.enriching)
            assertSame(pending, model.pending)
        }

    @Test
    fun disposing_pending_invalidates_ownership_before_notifying_the_store() {
        val model = PlayerLaunchViewModel()
        val store = FakeStore { assertNull(model.pending) }
        model.pending = PendingPlayerLaunch(store, startPlaybackRequested = true)

        model.disposePending()

        assertTrue(store.isDisposed)
        assertNull(model.pending)
    }

    private fun pending() = PendingPlayerLaunch(FakeStore(), startPlaybackRequested = true)

    private fun request() =
        PlayerLaunchRequest.create(
            items = listOf(PlayerMediaItem("movie", "https://media.example/movie", "", "Movie")),
            startIndex = 0,
            startPositionMs = 0L,
            engine = PlayerEngine.Exo,
            decoder = DecoderMode.Hardware,
            autoNext = true,
        )

    private class FakeStore(
        private val onDispose: () -> Unit = {},
    ) : PreparedPlayerStore {
        override val state = PlayerState()
        override var isDisposed = false
            private set

        override fun states(observer: Observer<PlayerState>): Disposable {
            observer.onNext(state)
            return TestDisposable()
        }

        override fun labels(observer: Observer<Nothing>): Disposable = TestDisposable()

        override fun accept(intent: PlayerIntent) = Unit

        override fun init() = Unit

        override fun dispose() {
            onDispose()
            isDisposed = true
        }
    }

    private class TestDisposable : Disposable {
        override var isDisposed = false
            private set

        override fun dispose() {
            isDisposed = true
        }
    }
}
