package com.yfuse.core.designsystem

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZoomBackNavigationTest {
    @Test
    fun aVisiblePageReceivesTheGestureAndCommitsOnce() =
        runTest {
            withHost(this) { fixture ->
                fixture.input.backStarted(event())
                fixture.input.backProgressed(event(0.6f))
                assertEquals(1, fixture.page.started)
                assertEquals(1, fixture.page.progressed)
                assertEquals(0, fixture.page.completed)

                fixture.input.backCompleted()
                assertEquals(1, fixture.page.completed)
                assertEquals(0, fixture.root.completed)
            }
        }

    @Test
    fun cancellationKeepsThePageAndTheNextGestureCanCommit() =
        runTest {
            withHost(this) { fixture ->
                fixture.input.backStarted(event())
                fixture.input.backProgressed(event(0.7f))
                fixture.input.backCancelled()
                assertEquals(1, fixture.page.cancelled)
                assertEquals(0, fixture.page.completed)

                fixture.input.backStarted(event())
                fixture.input.backCompleted()
                assertEquals(2, fixture.page.started)
                assertEquals(1, fixture.page.completed)
            }
        }

    @Test
    fun aBackWithoutPreviewUsesTheOrdinaryNavigationCallbackOnce() =
        runTest {
            withHost(this) { fixture ->
                fixture.input.backCompleted()
                assertEquals(1, fixture.ordinaryBacks)
                assertEquals(0, fixture.page.completed)
            }
        }

    @Test
    fun aRetainedHiddenStackLeavesBackToTheVisibleRoot() =
        runTest {
            withHost(this) { fixture ->
                fixture.host.onStack(listOf("root", "detail"), null, visible = false)
                fixture.input.backStarted(event())
                fixture.input.backProgressed(event(0.5f))
                fixture.input.backCompleted()

                assertEquals(0, fixture.page.started)
                assertEquals(0, fixture.page.completed)
                assertEquals(1, fixture.root.completed)
            }
        }

    @Test
    fun replacingThePageDuringTheGestureDoesNotPopItsReplacement() =
        runTest {
            withHost(this) { fixture ->
                fixture.input.backStarted(event())
                fixture.host.onStack(listOf("root", "replacement"), null)
                fixture.input.backCompleted()
                assertEquals(1, fixture.page.cancelled)
                assertEquals(0, fixture.page.completed)
                assertEquals(0, fixture.ordinaryBacks)

                fixture.input.backStarted(event())
                fixture.input.backCompleted()
                assertEquals(1, fixture.page.completed)
            }
        }

    @Test
    fun changingThePreviewDestinationAlsoInvalidatesTheOldGesture() =
        runTest {
            withHost(this) { fixture ->
                fixture.input.backStarted(event())
                fixture.host.onStack(listOf("other-root", "detail"), null)
                fixture.input.backCompleted()
                assertEquals(1, fixture.page.cancelled)
                assertEquals(0, fixture.page.completed)
            }
        }

    @Test
    fun hidingTheHostCancelsItsPreviewAndIgnoresTheOldCommit() =
        runTest {
            withHost(this) { fixture ->
                fixture.input.backStarted(event())
                fixture.host.onStack(listOf("root", "detail"), null, visible = false)
                fixture.input.backCompleted()
                assertEquals(1, fixture.page.cancelled)
                assertEquals(0, fixture.page.completed)
                assertEquals(0, fixture.ordinaryBacks)

                fixture.input.backStarted(event())
                fixture.input.backCompleted()
                assertEquals(1, fixture.root.completed)
            }
        }

    @Test
    fun disposingTheHostCancelsItsPreviewBeforeRemovingTheDispatcher() =
        runTest {
            withHost(this) { fixture ->
                fixture.input.backStarted(event())
                fixture.close()
                assertEquals(1, fixture.page.cancelled)
                assertEquals(0, fixture.page.completed)
            }
        }

    @Test
    fun aHiddenThenReopenedHandlerCannotCommitTheOldGesture() =
        runTest {
            withHost(this) { fixture ->
                var visible = true
                fixture.page.handler.acceptsBack = { visible }
                fixture.input.backStarted(event())
                visible = false
                fixture.page.handler.rejectGesture()
                fixture.page.handler.isBackEnabled = false
                visible = true
                fixture.page.handler.isBackEnabled = true
                fixture.input.backCompleted()
                assertEquals(1, fixture.page.cancelled)
                assertEquals(0, fixture.page.completed)

                fixture.input.backStarted(event())
                fixture.input.backCompleted()
                assertEquals(1, fixture.page.completed)
            }
        }

    @Test
    fun progressAfterThePageStopsAcceptingBackCancelsOnlyOnce() =
        runTest {
            withHost(this) { fixture ->
                var visible = true
                fixture.page.handler.acceptsBack = { visible }
                fixture.input.backStarted(event())
                visible = false
                fixture.input.backProgressed(event(0.5f))
                fixture.input.backCancelled()
                assertEquals(1, fixture.page.cancelled)
                assertEquals(0, fixture.page.progressed)
                assertEquals(0, fixture.page.completed)
            }
        }

    @Test
    fun completionWithoutMoreProgressCancelsAnInactivePage() =
        runTest {
            withHost(this) { fixture ->
                var visible = true
                fixture.page.handler.acceptsBack = { visible }
                fixture.input.backStarted(event())
                visible = false
                fixture.input.backCompleted()
                assertEquals(1, fixture.page.cancelled)
                assertEquals(0, fixture.page.completed)
            }
        }

    @Test
    fun aStaleEnabledHandlerRejectsBothPreviewAndOrdinaryBack() =
        runTest {
            withHost(this) { fixture ->
                fixture.page.handler.acceptsBack = { false }
                fixture.input.backStarted(event())
                fixture.input.backProgressed(event(0.5f))
                fixture.input.backCompleted()
                assertEquals(0, fixture.page.started)
                assertEquals(0, fixture.page.completed)
                assertEquals(0, fixture.page.cancelled)

                val ordinaryInput = DirectNavigationEventInput()
                val dispatcher = fixture.host.displayOwner.navigationEventDispatcher
                dispatcher.addInput(ordinaryInput)
                ordinaryInput.backCompleted()
                dispatcher.removeInput(ordinaryInput)
                assertEquals(0, fixture.page.completed)
            }
        }

    private fun event(progress: Float = 0f): NavigationEvent =
        NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = progress)

    private fun withHost(
        scope: CoroutineScope,
        block: (Fixture) -> Unit,
    ) {
        val fixture = Fixture(scope)
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private class Recorder {
        var started = 0
        var progressed = 0
        var completed = 0
        var cancelled = 0
        val handler =
            RelayBackHandler().apply {
                isBackEnabled = true
                onStarted = { started++ }
                onProgressed = { progressed++ }
                onCompleted = { completed++ }
                onCancelled = { cancelled++ }
            }
    }

    private class Fixture(
        scope: CoroutineScope,
    ) {
        private val dispatcher = NavigationEventDispatcher()
        val input = DirectNavigationEventInput()
        val root = Recorder()
        val page = Recorder()
        val host =
            ZoomBackNavHost(
                ZoomBackController(scope),
                object : NavigationEventDispatcherOwner {
                    override val navigationEventDispatcher = dispatcher
                },
                origins = mutableStateMapOf<String, ZoomOrigin>(),
            )
        var ordinaryBacks = 0
        private var closed = false

        init {
            dispatcher.addInput(input)
            dispatcher.addHandler(root.handler)
            host.attach()
            host.displayOwner.navigationEventDispatcher.addHandler(page.handler)
            host.onBack = { ordinaryBacks++ }
            host.onStack(listOf("root", "detail"), null)
        }

        fun close() {
            if (closed) return
            closed = true
            host.detach()
            dispatcher.dispose()
        }
    }

    /** What a tab's saved state hands the host made for its stack when the tab comes back. */
    private fun leaveAndReturn(origins: Map<String, ZoomOrigin>): SnapshotStateMap<String, ZoomOrigin> {
        val shown = mutableStateMapOf<String, ZoomOrigin>().apply { putAll(origins) }
        val saved = with(ZoomOriginsSaver) { SaverScope { true }.save(shown) }
        return requireNotNull(ZoomOriginsSaver.restore(requireNotNull(saved)))
    }

    @Test
    fun aDetailOpenedFromAPosterStillGoesBackIntoItAfterATabRoundTrip() {
        val poster = MediaSharedElementKey("server", "film")
        val related = MediaSharedElementKey("server", "sequel", kind = "poster")
        val page = Size(1080f, 2400f)
        val restored =
            leaveAndReturn(
                mapOf(
                    "home:Detail(film)" to ZoomOrigin(poster, "home:Home", page),
                    "home:Detail(sequel)" to ZoomOrigin(related, "home:Detail(film)", page),
                ),
            )

        assertEquals(setOf("home:Detail(film)", "home:Detail(sequel)"), restored.keys)
        val film = requireNotNull(restored["home:Detail(film)"])
        assertEquals(poster, film.key)
        assertEquals("home:Home", film.underlay)
        assertEquals(page, film.pageSize)
        val sequel = requireNotNull(restored["home:Detail(sequel)"])
        assertEquals(related, sequel.key)
        assertEquals("home:Detail(film)", sequel.underlay)
    }

    @Test
    fun aTitleWithNoServerAndAnUnmeasuredPageKeepsItsOrigin() {
        val poster = MediaSharedElementKey(null, "local")
        val restored = leaveAndReturn(mapOf("library:Detail(local)" to ZoomOrigin(poster, "library:Grid", Size.Zero)))

        val origin = requireNotNull(restored["library:Detail(local)"])
        assertEquals(poster, origin.key)
        assertEquals(Size.Zero, origin.pageSize)
    }

    @Test
    fun aPageLetGoPastThePointOfNoReturnIsLeaving() {
        val controller = ZoomBackController(TestScope())
        controller.page = Size(1000f, 2000f)
        assertFalse(controller.leaving)

        assertTrue(controller.startPull(Offset(500f, 100f)))
        controller.movePull(Offset(500f, 1000f))
        assertFalse(controller.leaving, "still under the finger")

        controller.releasePull(Offset.Zero)
        assertTrue(controller.leaving)
    }

    @Test
    fun aPageSpringingHomeIsNotLeaving() {
        val controller = ZoomBackController(TestScope())
        controller.page = Size(1000f, 2000f)

        assertTrue(controller.startPull(Offset(500f, 100f)))
        controller.movePull(Offset(500f, 130f))
        controller.releasePull(Offset.Zero)

        assertEquals(ZoomBackPhase.Returning, controller.phase)
        assertFalse(controller.leaving)
    }
}
