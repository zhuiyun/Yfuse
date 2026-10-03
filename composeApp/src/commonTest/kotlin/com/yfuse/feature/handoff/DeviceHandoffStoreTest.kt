package com.yfuse.feature.handoff

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.yfuse.core.handoff.HandoffController
import com.yfuse.core.handoff.HandoffControllerTest
import com.yfuse.watch.protocol.HandoffDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceHandoffStoreTest {
    // One clock for the store, the controller's heartbeat and the test that advances both.
    private val scheduler = TestCoroutineScheduler()

    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher(scheduler))

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_page_shows_what_the_controller_knows_of_the_accounts_devices() =
        runTest(scheduler) {
            val api = HandoffControllerTest.FakeApi { testScheduler.currentTime }.apply { devices = listOf(TELEVISION) }
            val controller = controller(api)
            controller.start()
            runCurrent()

            val store = DeviceHandoffStoreFactory(DefaultStoreFactory(), controller).create()

            assertEquals(controller.state.value, store.state)
            assertEquals(listOf(TELEVISION), store.state.devices)
            assertEquals("已连接", store.state.connectionLabel)
            store.dispose()
        }

    @Test
    fun while_the_page_is_shown_the_devices_are_asked_for_sooner_than_the_heartbeat() =
        runTest(scheduler) {
            val api = HandoffControllerTest.FakeApi { testScheduler.currentTime }
            val controller = controller(api)
            controller.start()
            runCurrent()
            val store = DeviceHandoffStoreFactory(DefaultStoreFactory(), controller).create()

            api.devices = listOf(TELEVISION)
            store.accept(DeviceHandoffIntent.PageShown)
            runCurrent()
            assertEquals(listOf(TELEVISION), store.state.devices)

            // Halfway to the heartbeat's own ten seconds.
            api.devices = listOf(TELEVISION, TABLET)
            advanceTimeBy(PRESENCE_REFRESH_MS)
            runCurrent()
            assertEquals(listOf(TELEVISION, TABLET), store.state.devices)

            store.accept(DeviceHandoffIntent.PageHidden)
            api.devices = emptyList()
            advanceTimeBy(PRESENCE_REFRESH_MS)
            runCurrent()
            assertEquals(listOf(TELEVISION, TABLET), store.state.devices)

            // The heartbeat still comes at its own pace.
            advanceTimeBy(PRESENCE_REFRESH_MS)
            runCurrent()
            assertEquals(emptyList(), store.state.devices)
            store.dispose()
        }

    @Test
    fun a_closed_page_stops_asking() =
        runTest(scheduler) {
            val api = HandoffControllerTest.FakeApi { testScheduler.currentTime }
            val controller = controller(api)
            controller.start()
            runCurrent()
            val store = DeviceHandoffStoreFactory(DefaultStoreFactory(), controller).create()
            store.accept(DeviceHandoffIntent.PageShown)
            runCurrent()

            store.dispose()
            api.devices = listOf(TELEVISION)
            advanceTimeBy(PRESENCE_REFRESH_MS)
            runCurrent()

            assertEquals(emptyList(), controller.state.value.devices)
        }

    @Test
    fun a_transfer_is_sent_and_cancelled_through_the_controller() =
        runTest(scheduler) {
            val api = HandoffControllerTest.FakeApi { testScheduler.currentTime }.apply { devices = listOf(TELEVISION) }
            val controller = controller(api)
            controller.start()
            runCurrent()
            val store = DeviceHandoffStoreFactory(DefaultStoreFactory(), controller).create()

            store.accept(DeviceHandoffIntent.Send(TELEVISION.sessionId))
            runCurrent()
            assertTrue(store.state.busy)
            assertEquals("等待另一台设备确认", store.state.message)
            assertEquals(TELEVISION.sessionId, api.lastOffer?.targetSessionId)

            store.accept(DeviceHandoffIntent.CancelTransfer)
            runCurrent()
            assertFalse(store.state.busy)
            assertEquals("接力已取消或超时", store.state.error)
            store.dispose()
        }

    private fun TestScope.controller(api: HandoffControllerTest.FakeApi) =
        HandoffController(
            api,
            HandoffControllerTest.FakeCipher(),
            HandoffControllerTest.FakePlayback(),
            MutableStateFlow<String?>("user:adult"),
            backgroundScope,
            "Phone",
            "Android",
            { true },
            cryptoDispatcher = StandardTestDispatcher(testScheduler),
        ) { testScheduler.currentTime }

    private companion object {
        val TELEVISION = HandoffDevice("tv", "客厅电视", "Android TV", 0L, canReceive = true)
        val TABLET = HandoffDevice("tablet", "书房平板", "Android", 0L, canReceive = true)
    }
}
