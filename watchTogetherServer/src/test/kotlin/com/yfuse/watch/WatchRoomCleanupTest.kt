package com.yfuse.watch

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WatchRoomCleanupTest {
    @Test
    fun idleCleanupReleasesQuotaAndStopsWithItsJob() =
        runBlocking {
            val store = RoomStore(roomGraceMs = 100, maxActiveRoomsPerIp = 1)
            val expired = create(store)
            store.mutateIfCurrent(expired) { it.emptySinceMs = 0 }
            var sweeps = 0
            val job =
                launchRoomCleanup(store, intervalMs = 5) {
                    sweeps++
                    100
                }
            try {
                withTimeout(2_000) { while (store.find(expired.code) != null) delay(5) }
                assertEquals(0, store.activeRoomCount())
                assertFalse(store.mutateIfCurrent(expired) { error("stale room mutated") })
                assertIs<RoomCreationResult.Created>(store.createRoom("media", "host", "ip", "account", emptyList()))
            } finally {
                job.cancelAndJoin()
            }
            val stoppedAt = sweeps
            delay(20)
            assertEquals(stoppedAt, sweeps)
        }

    @Test
    fun cleanupCannotRemoveRoomWhileSuccessfulJoinClearsItsEmptyMarker() {
        val store = RoomStore(roomGraceMs = 100, maxActiveRoomsPerIp = 1)
        val room = create(store)
        store.mutateIfCurrent(room) { it.emptySinceMs = 0 }
        val joined = CountDownLatch(1)
        val finishJoin = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val mutation =
                pool.submit<Boolean> {
                    store.mutateIfCurrent(room) {
                        joined.countDown()
                        check(finishJoin.await(2, TimeUnit.SECONDS))
                        it.emptySinceMs = null
                    }
                }
            assertTrue(joined.await(2, TimeUnit.SECONDS))
            val cleanup = pool.submit { store.sweepExpiredRooms(100) }
            finishJoin.countDown()
            assertTrue(mutation.get(2, TimeUnit.SECONDS))
            cleanup.get(2, TimeUnit.SECONDS)
            assertSame(room, store.find(room.code))
            assertNull(room.emptySinceMs)
        } finally {
            finishJoin.countDown()
            pool.shutdownNow()
        }
    }

    private fun create(store: RoomStore): Room =
        assertIs<RoomCreationResult.Created>(store.createRoom("media", "host", "ip", "account", emptyList())).room
}
