package com.yfuse.feature.servers

import com.yfuse.core.data.LatencySeverity
import com.yfuse.core.data.ServerHealth
import com.yfuse.core.data.ServerHealthStatus
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.EndpointTransportDecision
import com.yfuse.core.network.validateEmbyServerEndpoint

enum class ServerSortOrder(
    val label: String,
) {
    Saved("默认顺序"),
    Online("在线优先"),
    Latency("延迟最低"),
    Recent("最近使用"),
    Account("账号名称"),
}

enum class ServerLatencyFilter(
    val label: String,
) {
    All("全部延迟"),
    Stable("稳定 · 低于 400 ms"),
    Slow("较慢 · 400–1199 ms"),
    Unstable("不稳定 · 1200 ms 及以上"),
    Untested("未测速"),
}

data class ServerListFilter(
    val sort: ServerSortOrder = ServerSortOrder.Saved,
    val account: String? = null,
    val latency: ServerLatencyFilter = ServerLatencyFilter.All,
)

fun ServerListFilter.displayLabel(): String {
    val parts =
        listOfNotNull(
            "排序：${sort.label}",
            latency.label.takeUnless { latency == ServerLatencyFilter.All },
            account,
        )
    return parts.joinToString(" · ")
}

fun filterAndSortServers(
    servers: List<SavedServer>,
    health: Map<String, ServerHealth>,
    lastWatched: Map<String, Long>,
    filter: ServerListFilter,
): List<SavedServer> {
    val accountFiltered =
        filter.account?.let { account ->
            servers.filter { it.userName == account }
        } ?: servers
    val filtered =
        if (filter.latency == ServerLatencyFilter.All) {
            accountFiltered
        } else {
            accountFiltered.filter { server ->
                filter.latency.matches(health[server.id]?.latencySeverity ?: LatencySeverity.Unknown)
            }
        }
    return when (filter.sort) {
        ServerSortOrder.Saved -> filtered
        ServerSortOrder.Online ->
            filtered.sortedWith(
                compareBy<SavedServer> { statusRank(health[it.id]?.status) }
                    .thenBy { health[it.id]?.latencyMs ?: Long.MAX_VALUE }
                    .thenBy { it.serverName.lowercase() },
            )
        ServerSortOrder.Latency ->
            filtered.sortedWith(
                compareBy<SavedServer> {
                    val status = health[it.id]?.status
                    if (status == ServerHealthStatus.Healthy ||
                        status == ServerHealthStatus.Degraded
                    ) {
                        0
                    } else {
                        statusRank(status) + 1
                    }
                }.thenBy { health[it.id]?.latencyMs ?: Long.MAX_VALUE }
                    .thenBy { it.serverName.lowercase() },
            )
        ServerSortOrder.Recent ->
            filtered.sortedWith(
                compareByDescending<SavedServer> { lastWatched[it.id] ?: Long.MIN_VALUE }
                    .thenBy { it.serverName.lowercase() },
            )
        ServerSortOrder.Account ->
            filtered.sortedWith(
                compareBy<SavedServer> { it.userName.lowercase() }
                    .thenBy { it.serverName.lowercase() },
            )
    }
}

private fun ServerLatencyFilter.matches(severity: LatencySeverity): Boolean =
    when (this) {
        ServerLatencyFilter.All -> true
        ServerLatencyFilter.Stable -> severity == LatencySeverity.Stable
        ServerLatencyFilter.Slow -> severity == LatencySeverity.Slow
        ServerLatencyFilter.Unstable -> severity == LatencySeverity.Unstable
        ServerLatencyFilter.Untested -> severity == LatencySeverity.Unknown
    }

enum class TransportDiagnosticSeverity { Secure, LocalCleartext, Blocked }

data class TransportDiagnostic(
    val routeName: String,
    val address: String,
    val severity: TransportDiagnosticSeverity,
    val summary: String,
)

/** User-facing HTTPS/LAN diagnosis for every address a server may send credentials to. */
fun diagnoseServerTransport(server: SavedServer): List<TransportDiagnostic> =
    server.effectiveRoutes.map { route ->
        val validation =
            validateEmbyServerEndpoint(
                value = route.url,
                localCleartextConfirmed = server.localCleartextConfirmed,
            )
        val severity =
            when (validation.decision) {
                EndpointTransportDecision.Secure -> TransportDiagnosticSeverity.Secure
                EndpointTransportDecision.LocalCleartextConfirmed ->
                    TransportDiagnosticSeverity.LocalCleartext
                else -> TransportDiagnosticSeverity.Blocked
            }
        val summary =
            when (severity) {
                TransportDiagnosticSeverity.Secure -> "HTTPS 加密正常"
                TransportDiagnosticSeverity.LocalCleartext -> "局域网 HTTP · 已在本机确认"
                TransportDiagnosticSeverity.Blocked -> validation.message ?: "不安全的地址，已阻止"
            }
        TransportDiagnostic(route.name, route.url, severity, summary)
    }

private fun statusRank(status: ServerHealthStatus?): Int =
    when (status) {
        ServerHealthStatus.Healthy -> 0
        ServerHealthStatus.Degraded -> 1
        ServerHealthStatus.AuthRequired -> 2
        ServerHealthStatus.Unknown, null -> 3
        ServerHealthStatus.Offline -> 4
    }
