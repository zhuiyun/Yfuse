package com.yfuse.core.handoff

import com.yfuse.watch.protocol.HandoffDevice
import com.yfuse.watch.protocol.HandoffEnvelope
import com.yfuse.watch.protocol.HandoffHeartbeat
import com.yfuse.watch.protocol.HandoffInbox
import com.yfuse.watch.protocol.HandoffOffer
import com.yfuse.watch.protocol.HandoffPull
import com.yfuse.watch.protocol.HandoffRequest
import com.yfuse.watch.protocol.HandoffStatus
import com.yfuse.watch.protocol.HandoffTransition
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandoffControllerTest {
    @Test
    fun missing_service_keeps_the_account_signed_in_and_recovery_clears_the_connection_error() =
        runTest {
            val api =
                FakeApi { testScheduler.currentTime }.apply {
                    heartbeatFailure = HandoffApiException(HttpStatusCode.NotFound)
                }
            val controller = controller(api, FakePlayback())
            controller.start()
            runCurrent()
            assertTrue(controller.state.value.signedIn)
            assertFalse(controller.state.value.online)
            assertEquals("账号已登录，接力服务未连接", controller.state.value.connectionLabel)
            assertTrue(assertNotNull(controller.state.value.connectionError).contains("服务端更新"))
            api.heartbeatFailure = null
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue(controller.state.value.online)
            assertNull(controller.state.value.connectionError)
            assertEquals("已连接", controller.state.value.connectionLabel)
            controller.close()
        }

    @Test
    fun login_is_visible_while_heartbeat_waits_and_sign_out_cancels_the_pending_connection() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val api = FakeApi { testScheduler.currentTime }.apply { heartbeatGate = gate }
            val owner = MutableStateFlow<String?>("user:adult")
            val controller = controller(api, FakePlayback(), owner = owner)
            controller.start()
            runCurrent()
            assertTrue(controller.state.value.signedIn)
            assertEquals("账号已登录，正在连接接力服务", controller.state.value.connectionLabel)
            owner.value = null
            runCurrent()
            gate.complete(Unit)
            advanceTimeBy(20_000)
            runCurrent()
            assertFalse(controller.state.value.signedIn)
            assertFalse(controller.state.value.online)
            assertNull(controller.state.value.connectionError)
            assertEquals("请先登录鱼服账号", controller.state.value.connectionLabel)
            controller.close()
        }

    @Test
    fun sourcePausesOnlyAfterReadyAndCommitsLatestPosition() =
        runTest {
            val api = FakeApi { testScheduler.currentTime }
            val bridge = FakePlayback()
            val cipher = FakeCipher()
            val controller = controller(api, bridge, cipher)
            controller.start()
            runCurrent()
            controller.send("target")
            runCurrent()
            assertEquals(0, bridge.pauses)
            api.request = api.request!!.copy(status = HandoffStatus.Ready)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(1, bridge.pauses)
            assertEquals(9_000L, cipher.lastEncrypted!!.positionMs)
            assertEquals(HandoffStatus.Completed, api.request!!.status)
            advanceTimeBy(2_000)
            runCurrent()
            assertFalse(controller.state.value.busy)
            assertEquals(0, bridge.resumes)
            controller.close()
        }

    @Test
    fun receiverFailureResumesPausedSource() =
        runTest {
            val api = FakeApi { testScheduler.currentTime }.apply { completion = HandoffStatus.Failed }
            val bridge = FakePlayback()
            val controller = controller(api, bridge)
            controller.start()
            runCurrent()
            controller.send("target")
            runCurrent()
            api.request = api.request!!.copy(status = HandoffStatus.Ready)
            advanceTimeBy(12_000)
            runCurrent()
            assertEquals(1, bridge.pauses)
            assertEquals(1, bridge.resumes)
            assertFalse(controller.state.value.busy)
            controller.close()
        }

    @Test
    fun rejectedRequestNeverPausesAndProfileChangeCancels() =
        runTest {
            val api = FakeApi { testScheduler.currentTime }
            val bridge = FakePlayback()
            val owner = MutableStateFlow<String?>("user:adult")
            val controller = controller(api, bridge, owner = owner)
            controller.start()
            runCurrent()
            controller.send("target")
            runCurrent()
            api.request = api.request!!.copy(status = HandoffStatus.Rejected)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(0, bridge.pauses)
            controller.send("target")
            runCurrent()
            owner.value = "user:child"
            runCurrent()
            assertEquals(HandoffStatus.Cancelled, api.request!!.status)
            assertEquals(0, bridge.pauses)
            controller.close()
        }

    @Test
    fun failedReceiverPreparationReleasesResourcesWithoutStarting() =
        runTest {
            val api =
                FakeApi { testScheduler.currentTime }.apply {
                    currentSession = "target"
                    request =
                        HandoffRequest(
                            "request-0000000001",
                            "source",
                            "target",
                            "Phone",
                            60_000,
                            HandoffEnvelope("nonce", "payload"),
                        )
                }
            val bridge = FakePlayback().apply { preparationSucceeds = false }
            val controller = controller(api, bridge)
            controller.start()
            runCurrent()
            controller.accept(api.request!!)
            runCurrent()
            assertEquals(1, bridge.releases)
            assertEquals(0, bridge.starts)
            assertEquals(HandoffStatus.Failed, api.request!!.status)
            assertFalse(controller.state.value.busy)
            controller.close()
        }

    @Test
    fun theReceiverSaysWhyItCouldNotPlayAndOffersLastLongEnoughToLoad() =
        runTest {
            val api =
                FakeApi { testScheduler.currentTime }.apply {
                    currentSession = "target"
                    request =
                        HandoffRequest(
                            "request-0000000002",
                            "source",
                            "target",
                            "Phone",
                            120_000,
                            HandoffEnvelope("nonce", "payload"),
                        )
                }
            val bridge =
                FakePlayback().apply {
                    startSucceeds = false
                    failureReason = "本机网速约 2.1 Mbps，低于片源 13.3 Mbps"
                }
            val controller = controller(api, bridge)
            controller.start()
            runCurrent()
            controller.accept(api.request!!)
            runCurrent()
            api.request = api.request!!.copy(status = HandoffStatus.Committed)
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(1, bridge.starts)
            assertEquals(HandoffStatus.Failed, api.request!!.status)
            assertEquals("本机网速约 2.1 Mbps，低于片源 13.3 Mbps，来源设备会继续播放", controller.state.value.error)
            // The receiving player has closed; the reason has to reach the viewer on its own.
            assertEquals(controller.state.value.error, controller.state.value.receiveFailure)
            controller.dismissReceiveFailure()
            assertNull(controller.state.value.receiveFailure)
            controller.close()

            val sender = FakeApi { testScheduler.currentTime }
            val source = controller(sender, FakePlayback())
            source.start()
            runCurrent()
            source.send("target")
            runCurrent()
            assertEquals(HANDOFF_OFFER_LIFETIME_SECONDS, sender.lastOffer!!.lifetimeSeconds)
            source.close()
        }

    @Test
    fun nullSnapshotAfterPausingStillResumesSource() =
        runTest {
            val api = FakeApi { testScheduler.currentTime }
            val bridge = FakePlayback().apply { snapshotAfterPause = null }
            val controller = controller(api, bridge)
            controller.start()
            runCurrent()
            controller.send("target")
            runCurrent()
            api.request = api.request!!.copy(status = HandoffStatus.Ready)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(1, bridge.resumes)
            assertTrue(api.request!!.status.terminal)
            controller.close()
        }

    @Test
    fun reconnectClearsConnectionErrorWithoutErasingTransferFailure() =
        runTest {
            val api = FakeApi { testScheduler.currentTime }
            val controller = controller(api, FakePlayback())
            controller.start()
            runCurrent()
            controller.send("target")
            runCurrent()
            api.request = api.request!!.copy(status = HandoffStatus.Rejected)
            advanceTimeBy(10_000)
            runCurrent()
            val transferError = assertNotNull(controller.state.value.error)
            api.heartbeatFails = true
            advanceTimeBy(10_000)
            runCurrent()
            assertNotNull(controller.state.value.connectionError)
            api.heartbeatFails = false
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue(controller.state.value.online)
            assertNull(controller.state.value.connectionError)
            assertEquals(transferError, controller.state.value.error)
            controller.close()
        }

    @Test
    fun aPlayingDeviceSealsWhatItPlaysIntoItsHeartbeat() =
        runTest {
            val api = FakeApi { testScheduler.currentTime }
            val cipher = FakeCipher()
            val bridge = FakePlayback().apply { playing = media }
            val controller = controller(api, bridge, cipher)
            controller.start()
            runCurrent()
            // The card is bound to this device's session, which the first heartbeat learns.
            assertNull(api.lastHeartbeat!!.nowPlaying)
            advanceTimeBy(10_000)
            runCurrent()
            assertNotNull(api.lastHeartbeat!!.nowPlaying)
            assertEquals("now-playing:source", cipher.lastRequestId)
            assertEquals("Movie", cipher.lastEncrypted!!.title)
            assertTrue(cipher.lastEncrypted!!.aliases.isEmpty())
            bridge.playing = null
            advanceTimeBy(10_000)
            runCurrent()
            assertNull(api.lastHeartbeat!!.nowPlaying)
            controller.close()
        }

    @Test
    fun anotherDevicePlayingIsOfferedToContinueHere() =
        runTest {
            val api =
                FakeApi { testScheduler.currentTime }.apply {
                    currentSession = "phone"
                    devices = listOf(television)
                }
            val cipher = FakeCipher()
            val controller = controller(api, FakePlayback(), cipher)
            controller.start()
            runCurrent()
            val elsewhere =
                controller.state.value.playingElsewhere
                    .single()
            assertEquals("客厅电视", elsewhere.deviceName)
            assertEquals("Movie", elsewhere.title)
            assertEquals(1_000L, elsewhere.positionMs)
            assertEquals("now-playing:tv", cipher.lastOpenedId)
            assertTrue(controller.state.value.canReceive)
            advanceTimeBy(10_000)
            runCurrent()
            // Still playing: ten seconds further on, and the same card is not opened twice.
            assertEquals(
                11_000L,
                controller.state.value.playingElsewhere
                    .single()
                    .positionMs,
            )
            assertEquals(1, cipher.openings)
            api.devices = emptyList()
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue(
                controller.state.value.playingElsewhere
                    .isEmpty(),
            )
            controller.close()
        }

    @Test
    fun continueHereAsksTheSourceAndTakesItsTransferWithoutAskingAgain() =
        runTest {
            val api =
                FakeApi { testScheduler.currentTime }.apply {
                    currentSession = "phone"
                    devices = listOf(television)
                }
            val bridge = FakePlayback()
            val controller = controller(api, bridge)
            controller.start()
            runCurrent()
            controller.continueHere(
                controller.state.value.playingElsewhere
                    .single(),
            )
            runCurrent()
            // Sent at once rather than at the next ten-second heartbeat.
            assertEquals("tv", assertNotNull(api.lastHeartbeat!!.pull).sourceSessionId)
            assertEquals(
                "客厅电视",
                controller.state.value.continuing
                    ?.deviceName,
            )
            api.request =
                HandoffRequest(
                    "request-0000000009",
                    "tv",
                    "phone",
                    "客厅电视",
                    120_000,
                    HandoffEnvelope("nonce", "payload"),
                )
            advanceTimeBy(2_000)
            runCurrent()
            assertNull(controller.state.value.incoming)
            assertEquals(HandoffStatus.Ready, api.request!!.status)
            advanceTimeBy(2_000)
            runCurrent()
            assertNull(api.lastHeartbeat!!.pull)
            api.request = api.request!!.copy(status = HandoffStatus.Committed)
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(1, bridge.starts)
            assertEquals(HandoffStatus.Completed, api.request!!.status)
            assertNull(controller.state.value.continuing)
            assertFalse(controller.state.value.busy)
            controller.close()
        }

    @Test
    fun aPulledSourceOffersOnceToTheAskingDevice() =
        runTest {
            val api =
                FakeApi { testScheduler.currentTime }.apply {
                    devices =
                        listOf(
                            HandoffDevice(
                                "phone",
                                "Phone",
                                "Android",
                                0L,
                                true,
                                pull = HandoffPull("source", "pull-0000000000001"),
                            ),
                        )
                }
            val controller = controller(api, FakePlayback())
            controller.start()
            runCurrent()
            assertEquals("phone", api.lastOffer!!.targetSessionId)
            assertTrue(controller.state.value.busy)
            api.request = api.request!!.copy(status = HandoffStatus.Rejected)
            advanceTimeBy(2_000)
            runCurrent()
            assertFalse(controller.state.value.busy)
            advanceTimeBy(10_000)
            runCurrent()
            // The same pull, still on the phone's heartbeat, is not answered a second time.
            assertEquals(1, api.offers)
            controller.close()
        }

    @Test
    fun aPullNobodyAnswersGivesUp() =
        runTest {
            val api =
                FakeApi { testScheduler.currentTime }.apply {
                    currentSession = "phone"
                    devices = listOf(television)
                }
            val controller = controller(api, FakePlayback())
            controller.start()
            runCurrent()
            controller.continueHere(
                controller.state.value.playingElsewhere
                    .single(),
            )
            runCurrent()
            advanceTimeBy(HANDOFF_PULL_TIMEOUT_MS)
            runCurrent()
            assertNull(controller.state.value.continuing)
            assertTrue(assertNotNull(controller.state.value.error).contains("没有回应"))
            advanceTimeBy(10_000)
            runCurrent()
            assertNull(api.lastHeartbeat!!.pull)
            controller.close()
        }

    private fun TestScope.controller(
        api: FakeApi,
        bridge: FakePlayback,
        cipher: FakeCipher = FakeCipher(),
        owner: MutableStateFlow<String?> = MutableStateFlow("user:adult"),
    ) = HandoffController(
        api,
        cipher,
        bridge,
        owner,
        backgroundScope,
        "Phone",
        "Android",
        { true },
        cryptoDispatcher = StandardTestDispatcher(testScheduler),
    ) { testScheduler.currentTime }

    private class FakeApi(
        private val now: () -> Long,
    ) : HandoffApi {
        var currentSession = "source"
        var request: HandoffRequest? = null
        var completion = HandoffStatus.Completed
        var heartbeatFails = false
        var heartbeatFailure: Exception? = null
        var heartbeatGate: CompletableDeferred<Unit>? = null
        var lastOffer: HandoffOffer? = null
        var offers = 0
        var devices: List<HandoffDevice> = emptyList()
        var lastHeartbeat: HandoffHeartbeat? = null

        override suspend fun heartbeat(value: HandoffHeartbeat): HandoffInbox {
            heartbeatGate?.await()
            heartbeatFailure?.let { throw it }
            if (heartbeatFails) error("offline")
            lastHeartbeat = value
            return inbox()
        }

        override suspend fun inbox() = HandoffInbox(currentSession, devices, listOfNotNull(request), now())

        override suspend fun offer(value: HandoffOffer): HandoffRequest =
            HandoffRequest(
                value.id,
                "source",
                value.targetSessionId,
                "Phone",
                now() + 60_000,
                value.payload,
            ).also {
                request = it
                lastOffer = value
                offers++
            }

        override suspend fun transition(
            id: String,
            value: HandoffTransition,
        ): HandoffRequest {
            val current = request!!
            check(!current.status.terminal || current.status == value.status)
            return current
                .copy(
                    status =
                        if (value.status ==
                            HandoffStatus.Committed
                        ) {
                            completion
                        } else {
                            value.status
                        },
                    payload = value.payload ?: current.payload,
                ).also {
                    request =
                        it
                }
        }
    }

    private class FakeCipher : HandoffPayloadCipher {
        var lastEncrypted: HandoffMedia? = null
        var lastRequestId: String? = null
        var lastOpenedId: String? = null
        var openings = 0

        override fun encrypt(
            requestId: String,
            media: HandoffMedia,
        ) = HandoffEnvelope("nonce", "payload").also {
            lastEncrypted = media
            lastRequestId = requestId
        }

        override fun decrypt(
            requestId: String,
            envelope: HandoffEnvelope,
        ): HandoffMedia {
            if (requestId.startsWith("now-playing:")) {
                lastOpenedId = requestId
                openings++
            }
            return media
        }
    }

    private class FakePlayback : HandoffPlaybackBridge {
        var pauses = 0
        var resumes = 0
        var starts = 0
        var releases = 0
        var preparationSucceeds = true
        var startSucceeds = true
        var failureReason: String? = null
        var snapshotAfterPause: HandoffMedia? = media.copy(positionMs = 9_000)
        var playing: HandoffMedia? = null

        override fun snapshot() = media

        override fun nowPlaying() = playing

        override suspend fun prepare(media: HandoffMedia) = preparationSucceeds

        override suspend fun pauseAndSnapshot(): HandoffMedia? {
            pauses++
            return snapshotAfterPause
        }

        override suspend fun startPrepared(media: HandoffMedia): Boolean {
            starts++
            return startSucceeds
        }

        override fun receiveFailureReason() = failureReason

        override suspend fun releasePrepared() {
            releases++
        }

        override suspend fun resumeSource() {
            resumes++
        }
    }

    companion object {
        private val media =
            HandoffMedia("tmdb:603", "Movie", "server", "item", 1_000, 60_000, mediaSourceId = "version-4k")
        private val television =
            HandoffDevice("tv", "客厅电视", "Android TV", 0L, false, nowPlaying = HandoffEnvelope("nonce", "card"))
    }
}
