package com.yfuse.core.data

import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.ServerRoute
import com.yfuse.core.model.ServersData
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A lightweight health model used by server rows and playback failover decisions. */
enum class ServerHealthStatus { Unknown, Healthy, Degraded, Offline, AuthRequired }

/**
 * The experience implied by a measured round-trip time.
 *
 * This deliberately does not reuse [ServerHealthStatus]: a server can be reachable while
 * being painfully slow. Keeping the dimensions separate prevents a successful 1.3 second
 * response from inheriting the same green treatment as a fast one.
 */
enum class LatencySeverity(
    val label: String,
) {
    Unknown("未测速"),
    Stable("稳定"),
    Slow("较慢"),
    Unstable("不稳定"),
}

const val SLOW_LATENCY_MS = 400L
const val UNSTABLE_LATENCY_MS = 1_200L

/** What one address of a server answered on its last probe. */
data class RouteHealth(
    val status: ServerHealthStatus = ServerHealthStatus.Unknown,
    val latencyMs: Long? = null,
) {
    val reachable: Boolean
        get() = status == ServerHealthStatus.Healthy || status == ServerHealthStatus.Degraded

    val latencySeverity: LatencySeverity
        get() = latencySeverity(latencyMs)
}

data class ServerHealth(
    val status: ServerHealthStatus = ServerHealthStatus.Unknown,
    val latencyMs: Long? = null,
    val consecutiveFailures: Int = 0,
    val message: String? = null,
    /** Keyed by [ServerRoute.id]; empty until the first multi-route probe lands. */
    val routes: Map<String, RouteHealth> = emptyMap(),
) {
    val reachable: Boolean
        get() = status == ServerHealthStatus.Healthy || status == ServerHealthStatus.Degraded

    val latencySeverity: LatencySeverity
        get() = latencySeverity(latencyMs)

    val summary: String
        get() =
            when (status) {
                ServerHealthStatus.Healthy ->
                    latencyMs?.let { "在线 · ${latencySeverity.label} · $it ms" } ?: "在线"
                ServerHealthStatus.Degraded -> message ?: "连接不稳定"
                ServerHealthStatus.Offline -> "无法连接"
                ServerHealthStatus.AuthRequired -> "需要重新登录"
                ServerHealthStatus.Unknown -> "正在检查"
            }

    fun route(id: String): RouteHealth? = routes[id]

    /** How many of the probed addresses answered, for the "2/3 条线路可用" summary. */
    val reachableRouteCount: Int get() = routes.values.count { it.reachable }
}

class ServerHealthMonitor(
    private val repository: EmbyRepository,
    private val registry: ServerRegistry,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    private companion object {
        /**
         * A switch republishes the registry, which restarts the probe loop, which probes
         * again. That converges — the newly active route is the one that just answered — but
         * a server flapping between two half-broken addresses would otherwise rewrite the
         * registry on every round.
         */
        const val MIN_AUTO_SWITCH_INTERVAL_MS = 30_000L
    }

    private val _health = MutableStateFlow<Map<String, ServerHealth>>(emptyMap())
    val health: StateFlow<Map<String, ServerHealth>> = _health.asStateFlow()
    private val appForeground = MutableStateFlow(false)
    private var started = false
    private val lastAutoSwitchAtMs = mutableMapOf<String, Long>()
    private val probePermits = Semaphore(4)

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch {
            // Keyed on what a probe actually depends on - which servers exist and which address
            // each is using - not on the whole registry. Every registry write republishes it, so
            // collecting the raw flow re-probed every server after a default-server change, a
            // rename, or this monitor's own route failover, each of which then wrote the registry
            // again. distinctUntilChanged over the probe-relevant shape breaks that loop.
            combine(
                registry.data.map { data -> data to data.probeIdentity() }.distinctUntilChangedBy { it.second },
                appForeground,
            ) { (data, _), foreground -> data to foreground }
                .collectLatest { (data, foreground) ->
                    val ids = data.servers.mapTo(hashSetOf()) { it.id }
                    _health.value = _health.value.filterKeys { it in ids }
                    lastAutoSwitchAtMs.keys.retainAll(ids)
                    if (foreground) refreshAll(data.servers)
                }
        }
        scope.launch {
            appForeground.collectLatest { foreground ->
                if (!foreground) return@collectLatest
                while (isActive) {
                    delay(HEALTH_REFRESH_INTERVAL_MS)
                    refreshAll(registry.data.value.servers)
                }
            }
        }
    }

    /** Cancels in-flight probes and periodic network wakes while the library UI is not visible. */
    fun setAppForeground(value: Boolean) {
        appForeground.value = value
    }

    suspend fun refreshAll(servers: List<SavedServer> = registry.data.value.servers) =
        coroutineScope {
            servers.map { server -> async { refresh(server) } }.awaitAll()
            Unit
        }

    /**
     * Probes every address the server has and records each one, then reports the active
     * route's result as the server's own health.
     *
     * Probing the backups costs one cheap request each and is what makes the route list
     * useful: without it the user picks between addresses with no idea which of them is
     * currently up, and failover has nothing to fail over to.
     */
    suspend fun refresh(server: SavedServer) {
        val routes = server.effectiveRoutes
        if (routes.size <= 1) {
            probePermits
                .withPermit { repository.probeServer(server) }
                .onSuccess { latency -> recordSuccess(server.id, latency) }
                .onFailure { recordFailure(server.id, it) }
            return
        }
        val probed: List<Pair<ServerRoute, Result<Long>>> =
            coroutineScope {
                routes
                    .map { route ->
                        async {
                            route to
                                probePermits.withPermit {
                                    repository.probeAddress(route.url, server.accessToken, server.kind)
                                }
                        }
                    }.awaitAll()
            }
        val routeHealth =
            probed.associate { (route, result) ->
                route.id to
                    result.fold(
                        onSuccess = {
                            RouteHealth(
                                status = ServerHealthStatus.Healthy,
                                latencyMs = it,
                            )
                        },
                        onFailure = { RouteHealth(statusFor(it)) },
                    )
            }
        val activeId = server.activeRoute.id
        val activeResult = probed.firstOrNull { it.first.id == activeId }?.second
        when {
            activeResult == null -> Unit
            activeResult.isSuccess ->
                recordSuccess(
                    serverId = server.id,
                    latencyMs = activeResult.getOrNull(),
                    routes = routeHealth,
                )
            else -> {
                val error = activeResult.exceptionOrNull() ?: IllegalStateException("probe failed")
                recordFailure(server.id, error, routes = routeHealth)
                failOver(server, probed)
            }
        }
    }

    /**
     * Moves a server onto its fastest reachable backup when the active address stops
     * answering.
     *
     * Only away from a failure, never back: the user's pick of address is a deliberate one —
     * a LAN route is chosen because it is faster, not because the WAN route is down — and
     * silently returning to it the moment it answers would fight that choice on every probe.
     */
    private fun failOver(
        server: SavedServer,
        probed: List<Pair<ServerRoute, Result<Long>>>,
    ) {
        val now = nowEpochMs()
        val last = lastAutoSwitchAtMs[server.id]
        if (last != null && now - last < MIN_AUTO_SWITCH_INTERVAL_MS) return
        val fallback =
            probed
                .filter { it.first.id != server.activeRoute.id }
                .mapNotNull { (route, result) -> result.getOrNull()?.let { route to it } }
                .minByOrNull { it.second }
                ?: return
        lastAutoSwitchAtMs[server.id] = now
        if (!registry.activateRoute(server.id, fallback.first.id)) return
        AppLog.info(
            category = "server.health",
            event = "route_failover",
            message = "Switched to a reachable route after the active one stopped answering",
            attributes =
                mapOf(
                    "serverId" to server.id,
                    "routeId" to fallback.first.id,
                    "latencyMs" to fallback.second.toString(),
                ),
        )
    }

    fun recordSuccess(
        serverId: String,
        latencyMs: Long? = null,
        routes: Map<String, RouteHealth>? = null,
    ) {
        update(serverId) {
            val resolvedLatency = latencyMs ?: it?.latencyMs
            ServerHealth(
                status = ServerHealthStatus.Healthy,
                latencyMs = resolvedLatency,
                consecutiveFailures = 0,
                message = null,
                routes = routes ?: it?.routes.orEmpty(),
            )
        }
    }

    fun recordFailure(
        serverId: String,
        error: Throwable,
        routes: Map<String, RouteHealth>? = null,
    ) {
        val status = statusFor(error)
        update(serverId) { previous ->
            ServerHealth(
                status = status,
                latencyMs = previous?.latencyMs,
                consecutiveFailures = (previous?.consecutiveFailures ?: 0) + 1,
                message =
                    when (status) {
                        ServerHealthStatus.AuthRequired -> "需要重新登录"
                        ServerHealthStatus.Offline -> "无法连接"
                        else -> "服务器暂时异常"
                    },
                routes = routes ?: previous?.routes.orEmpty(),
            )
        }
        AppLog.warning(
            category = "server.health",
            event = "probe_failed",
            message = "Server health probe failed",
            throwable = error,
            attributes = mapOf("serverId" to serverId, "status" to status.name),
        )
    }

    private fun statusFor(error: Throwable): ServerHealthStatus =
        when (val emby = (error as? EmbyErrorException)?.error) {
            EmbyError.Unauthorized -> ServerHealthStatus.AuthRequired
            is EmbyError.AccessDenied -> ServerHealthStatus.Offline
            EmbyError.Network -> ServerHealthStatus.Offline
            is EmbyError.Server ->
                if (emby.code in 500..599) {
                    ServerHealthStatus.Degraded
                } else {
                    ServerHealthStatus.Offline
                }
            else -> ServerHealthStatus.Degraded
        }

    private inline fun update(
        serverId: String,
        block: (ServerHealth?) -> ServerHealth,
    ) {
        _health.value =
            _health.value.toMutableMap().apply {
                this[serverId] = block(this[serverId])
            }
    }
}

/**
 * The part of the registry a health probe depends on: which servers exist, and where each is
 * currently reached. Everything else - the default server, display names, user settings - changes
 * nothing about what a probe would do, so it must not cause one.
 */
private fun ServersData.probeIdentity(): List<String> =
    servers.map { server ->
        listOf(
            server.id,
            server.kind.name,
            server.activeRoute.id,
            server.activeRoute.url,
            server.effectiveRoutes.joinToString(",") { route -> "${route.id}=${route.url}" },
        ).joinToString("|")
    }

private const val HEALTH_REFRESH_INTERVAL_MS = 60_000L

/** Shared thresholds for cards, route diagnostics, filtering and source ranking. */
fun latencySeverity(
    latencyMs: Long?,
    slowLatencyMs: Long = SLOW_LATENCY_MS,
    unstableLatencyMs: Long = UNSTABLE_LATENCY_MS,
): LatencySeverity =
    when {
        latencyMs == null -> LatencySeverity.Unknown
        latencyMs >= unstableLatencyMs -> LatencySeverity.Unstable
        latencyMs >= slowLatencyMs -> LatencySeverity.Slow
        else -> LatencySeverity.Stable
    }
