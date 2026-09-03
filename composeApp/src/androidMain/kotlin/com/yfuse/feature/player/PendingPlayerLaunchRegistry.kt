package com.yfuse.feature.player

import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.arkivanov.mvikotlin.core.store.Store
import java.util.UUID

internal data class PendingPlayerLaunch(
    val store: Store<PlayerIntent, PlayerState, Nothing>,
    val startPlaybackRequested: Boolean,
)

/** One-shot handoff of an in-flight queue load to the dedicated player Activity. */
internal object PendingPlayerLaunchRegistry {
    private const val EXTRA_ID = "yfuse.player.pendingLaunchId"
    private const val MAX_ENTRIES = 4
    private const val TTL_MS = 2 * 60_000L
    private val handler = Handler(Looper.getMainLooper())
    private val entries = LinkedHashMap<String, PendingPlayerLaunch>()

    fun register(pending: PendingPlayerLaunch): String =
        synchronized(entries) {
            while (entries.size >= MAX_ENTRIES) {
                entries.remove(entries.keys.first())?.store?.dispose()
            }
            val id = UUID.randomUUID().toString()
            entries[id] = pending
            handler.postDelayed({ discard(id) }, TTL_MS)
            id
        }

    fun consume(id: String?): PendingPlayerLaunch? =
        synchronized(entries) { id?.let(entries::remove) }

    fun discard(id: String?) {
        synchronized(entries) { id?.let(entries::remove) }?.store?.dispose()
    }

    fun writeTo(intent: Intent, id: String) {
        intent.putExtra(EXTRA_ID, id)
    }

    fun readFrom(intent: Intent): String? =
        intent.getStringExtra(EXTRA_ID)?.takeIf { it.length == 36 }
}
