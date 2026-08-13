package com.yfuse.feature.player

import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackQuality
import com.yfuse.core.model.PlayerEngine
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlayerLaunchRegistryTest {
    @Test
    fun queue_of_235_items_stays_out_of_the_bounded_intent_payload() {
        val secret = "private-token-that-must-stay-in-process"
        val largeRequest =
            request(
                count = 235,
                urlSuffix = "?api_key=$secret&padding=${"x".repeat(4_000)}",
            )
        val singleRequest = request(count = 1, urlSuffix = "?api_key=$secret")
        val largePayload =
            PlayerLaunchIntentPayload.create(
                request = largeRequest,
                launchId = "00000000-0000-0000-0000-000000000001",
            )
        val singlePayload =
            PlayerLaunchIntentPayload.create(
                request = singleRequest,
                launchId = "00000000-0000-0000-0000-000000000002",
            )

        assertEquals(singlePayload.estimatedParcelBytes, largePayload.estimatedParcelBytes)
        assertTrue(
            largePayload.estimatedParcelBytes < PlayerLaunchIntentPayload.MAX_ESTIMATED_PARCEL_BYTES,
        )
        assertTrue(largePayload.stringExtras.size <= 4)
        assertFalse(largePayload.stringExtras.values.any { secret in it || "https://" in it })
        assertFalse(largePayload.stringExtras.keys.any { "urls" in it.lowercase() })
    }

    @Test
    fun registered_queue_is_consumed_exactly_once_and_kept_complete() {
        var token = 0
        val store =
            PlayerLaunchRegistryStore(
                elapsedRealtimeMs = { 10L },
                tokenFactory = { "launch-${++token}" },
            )
        val expected = request(count = 235)
        val launchId = store.register(expected)

        val consumed = store.consume(launchId)

        assertSame(expected, consumed)
        assertEquals(235, consumed?.items?.size)
        assertNull(store.consume(launchId))
        assertEquals(0, store.entryCount())
    }

    @Test
    fun simultaneous_consumers_cannot_both_take_the_same_queue() {
        val store =
            PlayerLaunchRegistryStore(
                elapsedRealtimeMs = { 10L },
                tokenFactory = { "launch-concurrent" },
            )
        val launchId = store.register(request(count = 235))
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val consumed = arrayOfNulls<PlayerLaunchRequest>(2)
        val workers =
            List(2) { index ->
                thread(name = "launch-consumer-$index") {
                    ready.countDown()
                    start.await()
                    consumed[index] = store.consume(launchId)
                }
            }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        workers.forEach(Thread::join)

        assertEquals(1, consumed.count { it != null })
        assertEquals(0, store.entryCount())
    }

    @Test
    fun registry_expires_old_entries_and_evicts_the_oldest_at_capacity() {
        var now = 0L
        var token = 0
        val store =
            PlayerLaunchRegistryStore(
                maxEntries = 2,
                ttlMs = 100L,
                elapsedRealtimeMs = { now },
                tokenFactory = { "launch-${++token}" },
            )
        val first = store.register(request())
        now = 1L
        val second = store.register(request())
        val third = store.register(request())

        assertNull(store.consume(first))
        assertTrue(store.consume(second) != null)
        assertTrue(store.consume(third) != null)

        val expiring = store.register(request())
        now += 100L
        assertNull(store.consume(expiring))
        assertEquals(0, store.entryCount())
    }

    @Test
    fun missing_or_expired_registry_entry_fails_closed_with_only_bounded_identity() {
        val payload =
            PlayerLaunchIntentPayload.create(
                request = request(),
                launchId = "00000000-0000-0000-0000-000000000003",
            )

        val resolution =
            resolvePlayerLaunch(
                retained = null,
                payload = payload,
                consume = { null },
            )

        val expired = assertIs<PlayerLaunchResolution.Expired>(resolution)
        assertEquals("item-0", expired.fallback?.itemId)
        assertEquals("server-a", expired.fallback?.serverId)
        assertFalse(
            expired.fallback
                ?.title
                .orEmpty()
                .contains("https://"),
        )
        assertIs<PlayerLaunchResolution.Expired>(
            resolvePlayerLaunch(retained = null, payload = null, consume = { error("must not run") }),
        )
    }

    @Test
    fun retained_activity_request_survives_after_the_one_shot_token_is_gone() {
        val retained = request(count = 3)
        val viewModel =
            PlayerLaunchViewModel().apply {
                request = retained
                resume = 2 to 98_765L
            }

        val resolution =
            resolvePlayerLaunch(
                retained = viewModel.request,
                payload = null,
                consume = { error("retained launches must not consume another token") },
            )

        assertSame(retained, assertIs<PlayerLaunchResolution.Ready>(resolution).request)
        assertEquals(2 to 98_765L, viewModel.resume)
    }

    @Test
    fun fresh_replacement_bypasses_the_retained_request_and_consumes_its_token_once() {
        val store =
            PlayerLaunchRegistryStore(
                elapsedRealtimeMs = { 10L },
                tokenFactory = { "launch-replacement" },
            )
        val replacement = request(count = 4, urlSuffix = "-replacement")
        val launchId = store.register(replacement)
        val payload = PlayerLaunchIntentPayload.create(replacement, launchId)

        val resolution = resolveFreshPlayerLaunch(payload, store::consume)

        assertSame(replacement, assertIs<PlayerLaunchResolution.Ready>(resolution).request)
        assertNull(store.consume(launchId))
        assertEquals(0, store.entryCount())
    }

    @Test
    fun scheduled_token_only_expiry_removes_the_last_orphan_without_another_store_call() {
        val store =
            PlayerLaunchRegistryStore(
                ttlMs = 100L,
                elapsedRealtimeMs = { 0L },
                tokenFactory = { "launch-orphan" },
            )
        var scheduledToken: String? = null
        var scheduledDelayMs = -1L
        var scheduledDiscard: ((String?) -> Unit)? = null
        val controller =
            PlayerLaunchRegistryController(
                store = store,
                ttlMs = 100L,
                scheduleDiscard = { token, delayMs, discard ->
                    scheduledToken = token
                    scheduledDelayMs = delayMs
                    scheduledDiscard = discard
                },
            )

        val token = controller.register(request(count = 235))

        assertEquals(token, scheduledToken)
        assertEquals(100L, scheduledDelayMs)
        assertEquals(1, store.entryCount())
        assertNotNull(scheduledDiscard).invoke(scheduledToken)
        assertEquals(0, store.entryCount())
    }

    @Test
    fun discarded_launch_is_unavailable_and_launcher_cleans_up_start_failures() {
        val store =
            PlayerLaunchRegistryStore(
                elapsedRealtimeMs = { 10L },
                tokenFactory = { "launch-discarded" },
            )
        val launchId = store.register(request())

        store.discard(launchId)

        assertNull(store.consume(launchId))
        assertEquals(0, store.entryCount())
        val launcherSource =
            projectFile(
                "src/androidMain/kotlin/com/yfuse/feature/player/VideoPlayer.android.kt",
            ).readText()
        assertTrue("pendingLaunch?.let(PlayerActivity::discardLaunch)" in launcherSource)
    }

    @Test
    fun activity_factory_and_notification_never_copy_the_queue_or_consumed_token() {
        val source =
            projectFile(
                "src/androidMain/kotlin/com/yfuse/feature/player/PlayerActivity.kt",
            ).readText()
        assertTrue("return Intent(context, PlayerActivity::class.java)" in source)
        assertTrue(".apply(payload::writeTo)" in source)
        assertFalse("getStringArrayExtra" in source)
        assertFalse("putStringArrayListExtra" in source)
        assertFalse("Intent(intent).setClass" in source)

        val openIntentBlock =
            source
                .substringAfter("internal fun openIntent")
                .substringBefore("internal fun discardLaunch")
        assertTrue("Intent(context, PlayerActivity::class.java)" in openIntentBlock)
        assertTrue(".setAction(ACTION_OPEN)" in openIntentBlock)
        assertTrue("Intent.FLAG_ACTIVITY_REORDER_TO_FRONT" in openIntentBlock)
        assertTrue("Intent.FLAG_ACTIVITY_SINGLE_TOP" in openIntentBlock)
        assertFalse("PlayerLaunchIntentPayload" in openIntentBlock)
        assertFalse("yfuse.player.launchId" in openIntentBlock)

        val notificationBlock =
            source
                .substringAfter("private fun updatePlaybackNotification")
                .substringBefore("private fun mediaPendingIntent")
        assertTrue("openIntent(this)" in notificationBlock)
        assertFalse("Intent.FLAG_ACTIVITY_CLEAR_TASK" in notificationBlock)
        assertFalse("Intent.FLAG_ACTIVITY_NEW_TASK" in notificationBlock)
        assertFalse("yfuse.player.launchId" in notificationBlock)

        val destroyBlock =
            source
                .substringAfter("override fun onDestroy()")
                .substringBefore("private fun closePlayerAndReturn")
        assertTrue("if (::notificationManager.isInitialized)" in destroyBlock)
        assertTrue("if (::mediaSession.isInitialized)" in destroyBlock)

        val expiredBlock =
            source
                .substringAfter("if (launchResolution is PlayerLaunchResolution.Expired)")
                .substringBefore("val launchRequest")
        val staleCleanupBlock =
            source
                .substringAfter("private fun clearStalePlaybackArtifacts()")
                .substringBefore("private fun closePlayerAndReturn")
        assertTrue("clearStalePlaybackArtifacts()" in expiredBlock)
        assertTrue("stopService(Intent(this, PlaybackKeepAliveService::class.java))" in staleCleanupBlock)
        assertTrue("cancel(NOTIFICATION_ID)" in staleCleanupBlock)
    }

    private fun request(
        count: Int = 1,
        urlSuffix: String = "",
    ): PlayerLaunchRequest =
        PlayerLaunchRequest.create(
            items =
                List(count) { index ->
                    PlayerMediaItem(
                        id = "item-$index",
                        url = "https://media.example/direct/$index$urlSuffix",
                        transcodeUrl = "https://media.example/transcode/$index$urlSuffix",
                        fallbackTranscodeUrl = "https://media.example/fallback/$index$urlSuffix",
                        title = "Episode $index",
                        serverId = "server-a",
                        playSessionId = "session-$index",
                    )
                },
            startIndex = 0,
            startPositionMs = 12_345L,
            engine = PlayerEngine.Exo,
            decoder = DecoderMode.Hardware,
            autoNext = true,
            quality = PlaybackQuality.Auto,
        )

    private fun projectFile(moduleRelativePath: String): File =
        sequenceOf(
            File(moduleRelativePath),
            File("composeApp", moduleRelativePath),
        ).firstOrNull(File::isFile)
            ?: error("Cannot locate $moduleRelativePath from ${File(".").absolutePath}")
}
