package com.yfuse.core.data

import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.SavedServer
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A lightweight health model used by server rows and playback failover decisions. */
enum class ServerHealthStatus { Unknown, Healthy, Degraded, Offline, AuthRequired }

data class ServerHealth(
    val status: ServerHealthStatus = ServerHealthStatus.Unknown,
    val latencyMs: Long? = null,
    val consecutiveFailures: Int = 0,
    val message: String? = null,
) {
    val summary: String
        get() = when (status) {
            ServerHealthStatus.Healthy -> latencyMs?.let { "在线 · ${it} ms" } ?: "在线"
            ServerHealthStatus.Degraded -> message ?: "连接不稳定"
            ServerHealthStatus.Offline -> "无法连接"
            ServerHealthStatus.AuthRequired -> "需要重新登录"
            ServerHealthStatus.Unknown -> "正在检查"
        }
}

class ServerHealthMonitor(
    private val repository: EmbyRepository,
    private val registry: ServerRegistry,
) {
    private val _health = MutableStateFlow<Map<String, ServerHealth>>(emptyMap())
    val health: StateFlow<Map<String, ServerHealth>> = _health.asStateFlow()
    private var started = false

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch {
            registry.data.collectLatest { data ->
                val ids = data.servers.mapTo(hashSetOf()) { it.id }
                _health.value = _health.value.filterKeys { it in ids }
                refreshAll(data.servers)
            }
        }
        scope.launch {
            while (isActive) {
                delay(60_000L)
                refreshAll(registry.data.value.servers)
            }
        }
    }

    suspend fun refreshAll(servers: List<SavedServer> = registry.data.value.servers) = coroutineScope {
        servers.map { server -> async { refresh(server) } }.awaitAll()
        Unit
    }

    suspend fun refresh(server: SavedServer) {
        repository.probeServer(server)
            .onSuccess { latency -> recordSuccess(server.id, latency) }
            .onFailure { recordFailure(server.id, it) }
    }

    fun recordSuccess(serverId: String, latencyMs: Long? = null) {
        update(serverId) {
            ServerHealth(
                status = ServerHealthStatus.Healthy,
                latencyMs = latencyMs ?: it?.latencyMs,
                consecutiveFailures = 0,
            )
        }
    }

    fun recordFailure(serverId: String, error: Throwable) {
        val status = when (val emby = (error as? EmbyErrorException)?.error) {
            EmbyError.Unauthorized, is EmbyError.AccessDenied -> ServerHealthStatus.AuthRequired
            EmbyError.Network -> ServerHealthStatus.Offline
            is EmbyError.Server -> if (emby.code in 500..599) {
                ServerHealthStatus.Degraded
            } else {
                ServerHealthStatus.Offline
            }
            else -> ServerHealthStatus.Degraded
        }
        update(serverId) { previous ->
            ServerHealth(
                status = status,
                latencyMs = previous?.latencyMs,
                consecutiveFailures = (previous?.consecutiveFailures ?: 0) + 1,
                message = when (status) {
                    ServerHealthStatus.AuthRequired -> "需要重新登录"
                    ServerHealthStatus.Offline -> "无法连接"
                    else -> "服务器暂时异常"
                },
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

    private inline fun update(serverId: String, block: (ServerHealth?) -> ServerHealth) {
        _health.value = _health.value.toMutableMap().apply {
            this[serverId] = block(this[serverId])
        }
    }
}
