package com.yfuse.watch

import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local counters exposed in Prometheus text format at `/watch/metrics`.
 *
 * Counters only ever grow; gauges are sampled at render time from the room store. Nothing here
 * identifies a user: the point is to see load and failure rates, not who caused them.
 */
internal object WatchMetrics {
    val connectionsAccepted = AtomicLong()
    val connectionsRejected = AtomicLong()
    val authFailures = AtomicLong()
    val roomsCreated = AtomicLong()
    val joinsRejected = AtomicLong()
    val messagesHandled = AtomicLong()
    val chatRejected = AtomicLong()
    val broadcastDrops = AtomicLong()
    val httpRequests = AtomicLong()
    val httpServerErrors = AtomicLong()

    fun render(
        activeRooms: Int,
        activeParticipants: Int,
    ): String {
        val runtime = Runtime.getRuntime()
        val uptimeSeconds = ManagementFactory.getRuntimeMXBean().uptime / 1_000.0
        return buildString {
            counter("yfuse_watch_connections_accepted_total", connectionsAccepted)
            counter("yfuse_watch_connections_rejected_total", connectionsRejected)
            counter("yfuse_watch_auth_failures_total", authFailures)
            counter("yfuse_watch_rooms_created_total", roomsCreated)
            counter("yfuse_watch_joins_rejected_total", joinsRejected)
            counter("yfuse_watch_messages_handled_total", messagesHandled)
            counter("yfuse_watch_chat_rejected_total", chatRejected)
            counter("yfuse_watch_broadcast_drops_total", broadcastDrops)
            counter("yfuse_http_requests_total", httpRequests)
            counter("yfuse_http_server_errors_total", httpServerErrors)
            gauge("yfuse_watch_rooms_active", activeRooms.toLong())
            gauge("yfuse_watch_participants_active", activeParticipants.toLong())
            gauge("yfuse_jvm_memory_used_bytes", runtime.totalMemory() - runtime.freeMemory())
            gauge("yfuse_jvm_memory_max_bytes", runtime.maxMemory())
            append("# TYPE yfuse_process_uptime_seconds gauge\n")
            append("yfuse_process_uptime_seconds ").append(uptimeSeconds).append('\n')
        }
    }

    private fun StringBuilder.counter(
        name: String,
        value: AtomicLong,
    ) {
        append("# TYPE ").append(name).append(" counter\n")
        append(name).append(' ').append(value.get()).append('\n')
    }

    private fun StringBuilder.gauge(
        name: String,
        value: Long,
    ) {
        append("# TYPE ").append(name).append(" gauge\n")
        append(name).append(' ').append(value).append('\n')
    }
}
