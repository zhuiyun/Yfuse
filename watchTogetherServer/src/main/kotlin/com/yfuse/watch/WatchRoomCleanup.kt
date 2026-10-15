package com.yfuse.watch

import kotlinx.coroutines.CoroutineScope
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
