package com.yfuse.core.data

import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.ServerRoute
import com.yfuse.core.model.ServersData
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import com.yfuse.core.security.platformCryptoPrimitives
import com.yfuse.core.security.toBase64Url
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A lightweight health model used by server rows and playback failover decisions. */
enum class ServerHealthStatus { Unknown, Healthy, Degraded, Offline, AuthRequired }

private fun <T> Result<T>.rethrowProbeCancellation(): Result<T> =
    onFailure { if (it is CancellationException) throw it }

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

    /**
     * What the last probe of one server concluded, and for which configuration of it.
     *
     * Only probes write these. They drive the automatic schedule and the background-work gate;
     * the [health] shown on screen is also fed by ordinary requests failing or succeeding.
     */
    private data class ProbeVerdict(
        /** The server's probe fingerprint at the time: a new token or address is a new question. */
        val fingerprint: String,
        val atEpochMs: Long,
        val status: ServerHealthStatus,
        /** Consecutive Offline verdicts for this fingerprint; the backoff doubles with each. */
        val offlineStreak: Int,
    )

    private val _health = MutableStateFlow<Map<String, ServerHealth>>(emptyMap())
    val health: StateFlow<Map<String, ServerHealth>> = _health.asStateFlow()
    private val appForeground = MutableStateFlow(false)
    private val playerVisible = MutableStateFlow(false)

    /** Counts real returns of the app to the foreground; each one re-checks every server that is due. */
    private val foregroundEntries = MutableStateFlow(0)
    private var started = false

    // Written by concurrent probes; a plain map raced its own iteration in `retainAll`.
    private val lastAutoSwitchAtMs = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** When each server's backup addresses were last probed; they are not worth a minute cadence. */
    private val lastBackupProbeAtMs = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val verdicts = MutableStateFlow<Map<String, ProbeVerdict>>(emptyMap())
    private val probePermits = Semaphore(4)

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        val probing =
            combine(appForeground, playerVisible) { foreground, player -> foreground && !player }
                .distinctUntilChanged()
        scope.launch {
            // Keyed on what a probe actually depends on - which servers exist, which address each
            // is using and with which credential - not on the whole registry. Every registry write
            // republishes it, so collecting the raw flow re-probed every server after a
            // default-server change, a rename, or this monitor's own route failover, each of which
            // then wrote the registry again. distinctUntilChanged over the probe-relevant shape
            // breaks that loop.
            var roundedFor: Pair<List<String>, Int>? = null
            combine(
                registry.data.map { data -> data to data.probeIdentity() }.distinctUntilChangedBy { it.second },
                foregroundEntries,
                probing,
            ) { (data, identity), entries, active -> Triple(data, identity to entries, active) }
                .collectLatest { (data, trigger, active) ->
                    val ids = data.servers.mapTo(hashSetOf()) { it.id }
                    _health.update { current -> current.filterKeys { it in ids } }
                    lastAutoSwitchAtMs.update { current -> current.filterKeys { it in ids } }
                    lastBackupProbeAtMs.update { current -> current.filterKeys { it in ids } }
                    verdicts.update { current -> current.filterKeys { it in ids } }
                    // Pausing cancels a round in flight. Resuming alone - the player closing over
                    // the library - is not a reason for one: it used to re-probe every server,
                    // dead ones included, after each playback. Only a changed registry or a real
                    // return from the background is, and then only for servers that are due.
                    if (!active || trigger == roundedFor) return@collectLatest
                    roundedFor = trigger
                    refreshDue(data.servers)
                }
        }
        scope.launch {
            probing.collectLatest { active ->
                if (!active) return@collectLatest
                while (isActive) {
                    delay(HEALTH_REFRESH_INTERVAL_MS)
                    refreshDue(registry.data.value.servers)
                }
            }
        }
    }

    /**
     * Whether the app's own UI is started. Probing stops without it, and a return from the
     * background re-checks every server that is due.
     */
    fun setAppForeground(value: Boolean) {
        // Coming back while a player covers the library is not a return to the library. Counted
        // before the flag flips, so the round starts once rather than being restarted by it.
        if (value && !appForeground.value && !playerVisible.value) foregroundEntries.update { it + 1 }
        appForeground.value = value
    }

    /**
     * Whether a player is on screen, picture-in-picture included. Probing pauses under it; when
     * it closes, probing resumes at its normal cadence without an immediate round.
     */
    fun setPlayerVisible(value: Boolean) {
        playerVisible.value = value
    }

    /**
     * Whether background work - calendar scans, lookups nobody is waiting on - should contact
     * [server] now.
     *
     * False while the server's own probe says its credential is refused, and while it is Offline
     * within its probe backoff: each such request would collect another 401 or wait out a
     * connect timeout, holding a request slot that a reachable server's work needed.
     */
    fun allowsBackgroundWork(server: SavedServer): Boolean {
        val verdict = currentVerdict(server) ?: return true
        return when (verdict.status) {
            ServerHealthStatus.AuthRequired -> false
            ServerHealthStatus.Offline -> verdict.expired(offlineProbeBackoffMs(verdict.offlineStreak))
            else -> true
        }
    }

    /**
     * Whether an automatic round should probe [server]: never for a credential its probe saw refused
     * until that credential or the address changes, after the backoff for an Offline one, and
     * otherwise once a minute. Explicit refreshes do not ask.
     */
    private fun automaticProbeDue(server: SavedServer): Boolean {
        val verdict = currentVerdict(server) ?: return true
        return when (verdict.status) {
            ServerHealthStatus.AuthRequired -> false
            ServerHealthStatus.Offline -> verdict.expired(offlineProbeBackoffMs(verdict.offlineStreak))
            else -> verdict.expired(HEALTH_REFRESH_INTERVAL_MS)
        }
    }

    private fun currentVerdict(server: SavedServer): ProbeVerdict? =
        verdicts.value[server.id]?.takeIf { it.fingerprint == server.probeFingerprint() }

    // Rounds tick on a fixed cadence, so a verdict a few milliseconds short of its age must not
    // wait out one more whole interval.
    private fun ProbeVerdict.expired(holdOffMs: Long): Boolean =
        nowEpochMs() - atEpochMs !in 0 until holdOffMs - PROBE_SCHEDULE_SLACK_MS

    private suspend fun refreshDue(servers: List<SavedServer>) {
        val due = servers.filter(::automaticProbeDue)
        if (due.isNotEmpty()) refreshAllResults(due)
    }

    private fun rememberVerdict(
        server: SavedServer,
        result: Result<*>,
    ) {
        val status = result.fold(onSuccess = { ServerHealthStatus.Healthy }, onFailure = ::statusFor)
        val fingerprint = server.probeFingerprint()
        val now = nowEpochMs()
        verdicts.update { current ->
            val previous = current[server.id]?.takeIf { it.fingerprint == fingerprint }
            val streak = if (status == ServerHealthStatus.Offline) (previous?.offlineStreak ?: 0) + 1 else 0
            current + (server.id to ProbeVerdict(fingerprint, now, status, streak))
        }
    }

    /** Explicit: probes every server it is given, whatever their last verdict. */
    suspend fun refreshAll(servers: List<SavedServer> = registry.data.value.servers) {
        refreshAllResults(servers)
    }

    /** Results belong to these probes, even when a periodic round publishes newer health concurrently. */
    suspend fun refreshAllResults(servers: List<SavedServer>): Map<String, Result<Unit>> =
        coroutineScope {
            servers.map { server -> async { server.id to refreshResult(server) } }.awaitAll().toMap()
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
        refreshResult(server)
    }

    suspend fun refreshResult(server: SavedServer): Result<Unit> =
        probeRoutes(server).also { rememberVerdict(server, it) }

    private suspend fun probeRoutes(server: SavedServer): Result<Unit> {
        val routes = server.effectiveRoutes
        if (routes.size <= 1) {
            val result =
                probePermits
                    .withPermit { repository.probeServer(server) }
                    .rethrowProbeCancellation()
                    .onSuccess { latency -> recordSuccess(server.id, latency) }
                    .onFailure { recordFailure(server.id, it) }
            return result.map { Unit }
        }
        // The active address answers the foreground question every minute. Backups are
        // re-checked every few minutes, or at once when the active one stops answering,
        // which is the only moment their freshness decides anything.
        val now = nowEpochMs()
        val backupsDue = now - (lastBackupProbeAtMs.value[server.id] ?: 0L) >= BACKUP_PROBE_INTERVAL_MS
        if (!backupsDue) {
            val active = server.activeRoute
            val activeOnly =
                probePermits
                    .withPermit {
                        repository.probeAddress(active.url, server.accessToken, server.kind)
                    }.rethrowProbeCancellation()
            val latency = activeOnly.getOrNull()
            if (latency != null) {
                val previousRoutes = _health.value[server.id]?.routes.orEmpty()
                recordSuccess(
                    serverId = server.id,
                    latencyMs = latency,
                    routes = previousRoutes + (active.id to RouteHealth(ServerHealthStatus.Healthy, latency)),
                )
                return Result.success(Unit)
            }
        }
        lastBackupProbeAtMs.update { current -> current + (server.id to now) }
        val probed: List<Pair<ServerRoute, Result<Long>>> =
            coroutineScope {
                routes
                    .map { route ->
                        async {
                            route to
                                probePermits
                                    .withPermit {
                                        repository.probeAddress(route.url, server.accessToken, server.kind)
                                    }.rethrowProbeCancellation()
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
        return activeResult?.map { Unit }
            ?: Result.failure(IllegalStateException("Active route missing from probe results"))
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
        val last = lastAutoSwitchAtMs.value[server.id]
        if (last != null && now - last < MIN_AUTO_SWITCH_INTERVAL_MS) return
        val fallback =
            probed
                .filter { it.first.id != server.activeRoute.id }
                .mapNotNull { (route, result) -> result.getOrNull()?.let { route to it } }
                .minByOrNull { it.second }
                ?: return
        lastAutoSwitchAtMs.update { current -> current + (server.id to now) }
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
        // A server that just answered has disproved a refused or offline verdict: automatic
        // probing returns to its normal cadence instead of sitting out a backoff.
        verdicts.update { current ->
            val verdict =
                current[serverId]?.takeIf { it.status != ServerHealthStatus.Healthy } ?: return@update current
            current + (serverId to verdict.copy(status = ServerHealthStatus.Healthy, offlineStreak = 0))
        }
    }

    fun recordFailure(
        serverId: String,
        error: Throwable,
        routes: Map<String, RouteHealth>? = null,
    ) {
        val status = statusFor(error)
        // Answered by the client's cooldown: the server's own failure was counted and logged when it
        // happened. Counting each short-circuited request again logged a warning per request.
        val repeated = (error as? EmbyErrorException)?.fromCooldown == true
        val newFailures = if (repeated) 0 else 1
        update(serverId) { previous ->
            ServerHealth(
                status = status,
                latencyMs = previous?.latencyMs,
                consecutiveFailures = (previous?.consecutiveFailures ?: 0) + newFailures,
                message =
                    when (status) {
                        ServerHealthStatus.AuthRequired -> "需要重新登录"
                        ServerHealthStatus.Offline -> "无法连接"
                        else -> "服务器暂时异常"
                    },
                routes = routes ?: previous?.routes.orEmpty(),
            )
        }
        if (repeated) {
            AppLog.debug(
                category = "server.health",
                event = "failure_cooled_down",
                message = "A request failed from its server's cooldown",
                attributes = mapOf("serverId" to serverId, "status" to status.name),
            )
            return
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
        _health.update { current ->
            current.toMutableMap().apply {
                this[serverId] = block(this[serverId])
            }
        }
    }
}

/**
 * The part of the registry a health probe depends on: which servers exist, where each is currently
 * reached, and with which credential. Everything else - the default server, display names, user
 * settings - changes nothing about what a probe would do, so it must not cause one. Signing in again
 * must: it is the only way out of a refused credential, whose automatic probes stop until then.
 */
private fun ServersData.probeIdentity(): List<String> =
    servers.map { server -> "${server.id}|${server.probeFingerprint()}" }

private fun SavedServer.probeFingerprint(): String =
    listOf(
        kind.name,
        activeRoute.id,
        activeRoute.url,
        effectiveRoutes.joinToString(",") { route -> "${route.id}=${route.url}" },
        credentialFingerprint(accessToken),
    ).joinToString("|")

/**
 * Tells two credentials apart without keeping either: a digest under a per-process salt, so the
 * token itself never lands in the probe bookkeeping or anything that might one day print it.
 */
private fun credentialFingerprint(credential: String): String =
    fingerprintCrypto
        .sha256(fingerprintSalt + credential.encodeToByteArray())
        .copyOf(CREDENTIAL_FINGERPRINT_BYTES)
        .toBase64Url()

private val fingerprintCrypto by lazy { platformCryptoPrimitives() }
private val fingerprintSalt by lazy { fingerprintCrypto.randomBytes(16) }

/**
 * How long an Offline server waits for its next automatic probe: one refresh interval after the
 * first failure, doubling with each further one up to [MAX_OFFLINE_PROBE_BACKOFF_MS]. A dead host
 * probed every minute held one of the four probe slots for a full connect timeout each time.
 */
internal fun offlineProbeBackoffMs(offlineStreak: Int): Long {
    val exponent = (offlineStreak - 1).coerceIn(0, 5)
    return (HEALTH_REFRESH_INTERVAL_MS shl exponent).coerceAtMost(MAX_OFFLINE_PROBE_BACKOFF_MS)
}

private const val HEALTH_REFRESH_INTERVAL_MS = 60_000L
private const val BACKUP_PROBE_INTERVAL_MS = 5 * 60_000L
private const val MAX_OFFLINE_PROBE_BACKOFF_MS = 30 * 60_000L
private const val PROBE_SCHEDULE_SLACK_MS = 5_000L
private const val CREDENTIAL_FINGERPRINT_BYTES = 12

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
