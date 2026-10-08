package com.yfuse.watch

import com.yfuse.watch.account.isLoopbackHost
import io.ktor.http.Headers
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * Headers a reverse proxy adds. Caddy runs on the same box, so every request it forwards reaches
 * the service from 127.0.0.1: a loopback peer alone says nothing about where the caller is.
 */
private val PROXY_HEADERS = listOf("X-Forwarded-For", "Forwarded", "X-Forwarded-Proto", "X-Forwarded-Host", "X-Real-IP")

/**
 * `/watch/metrics` answers a matching bearer token, or — with no token configured — only a
 * caller on the box itself that came through no proxy. The loopback-only rule used to admit every
 * request Caddy forwarded, which made the endpoint public on the production layout.
 */
internal fun metricsRequestAllowed(
    remoteHost: String,
    headers: Headers,
    metricsToken: String?,
): Boolean {
    if (metricsToken != null) {
        val presented =
            headers["Authorization"]
                ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                ?.substringAfter(' ')
                ?.trim()
        return presented != null && constantTimeEquals(presented, metricsToken)
    }
    return isLoopbackHost(remoteHost) && PROXY_HEADERS.none { headers.contains(it) }
}

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
    val roomUpdatesSent = AtomicLong()
    val roomUpdatesDeferred = AtomicLong()
    val outboundBroadcastBytes = AtomicLong()
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
            counter("yfuse_watch_room_updates_sent_total", roomUpdatesSent)
            counter("yfuse_watch_room_updates_deferred_total", roomUpdatesDeferred)
            counter("yfuse_watch_broadcast_bytes_total", outboundBroadcastBytes)
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
