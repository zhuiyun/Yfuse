package com.yfuse.watch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val ROOM_CLEANUP_INTERVAL_MS = 30_000L

/** Application-owned: expired rooms release quota even when no new requests arrive. */
internal fun CoroutineScope.launchRoomCleanup(
    roomStore: RoomStore,
    intervalMs: Long = ROOM_CLEANUP_INTERVAL_MS,
    clock: () -> Long = System::currentTimeMillis,
): Job {
    require(intervalMs > 0L)
    return launch {
        while (isActive) {
            delay(intervalMs)
            roomStore.sweepExpiredRooms(clock())
        }
    }
}

internal fun CoroutineScope.launchRoomMaintenance(
    roomStore: RoomStore,
    persister: RoomStatePersister?,
    sweepIntervalMs: Long,
): Job {
    require(sweepIntervalMs > 0L)
    return launch(Dispatchers.IO) {
        val intervalMs = if (persister != null) minOf(ROOM_PERSIST_INTERVAL_MS, sweepIntervalMs) else sweepIntervalMs
        var lastSweepAtMs = monotonicMs()
        while (isActive) {
            delay(intervalMs)
            if (monotonicMs() - lastSweepAtMs >= sweepIntervalMs) {
                roomStore.sweepExpiredRooms()
                lastSweepAtMs = monotonicMs()
            }
            if (persister != null) {
                try {
                    persister.sync(roomStore.allRooms())
                } catch (failure: Exception) {
                    ServerLog.warn("watch_rooms_save_failed", "reason" to failure::class.simpleName)
                }
            }
        }
    }
}
