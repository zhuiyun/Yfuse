package com.yfuse.feature.servers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ServerRefreshResult { Success, PartialFailure, Failure, Cancelled, Empty }

data class ServerRefreshOutcome(
    val result: ServerRefreshResult,
    val totalServers: Int = 0,
    val failedServers: Int = 0,
) {
    val message: String
        get() =
            when (result) {
                ServerRefreshResult.Success -> "已刷新 $totalServers 台服务器"
                ServerRefreshResult.PartialFailure -> "部分刷新失败 · $failedServers 台需重试"
                ServerRefreshResult.Failure -> "刷新失败，请稍后重试"
                ServerRefreshResult.Cancelled -> "已取消刷新"
                ServerRefreshResult.Empty -> "暂无服务器可刷新"
            }
}

data class ServerRefreshState(
    val generation: Long = 0L,
    val refreshing: Boolean = false,
    val outcome: ServerRefreshOutcome? = null,
    val feedbackAllowed: Boolean = true,
)

internal fun summarizeServerRefresh(
    serverIds: List<String>,
    healthResults: Map<String, Result<Unit>>,
    statsResults: Map<String, Result<Unit>>,
): ServerRefreshOutcome {
    val ids = serverIds.distinct()
    if (ids.isEmpty()) return ServerRefreshOutcome(ServerRefreshResult.Empty)
    var successfulChecks = 0
    var failedServers = 0
    for (id in ids) {
        val checks = listOf(healthResults[id], statsResults[id])
        checks.forEach { check ->
            check?.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        }
        successfulChecks += checks.count { it?.isSuccess == true }
        if (checks.any { it?.isSuccess != true }) failedServers++
    }
    val result =
        when {
            failedServers == 0 -> ServerRefreshResult.Success
            successfulChecks == 0 -> ServerRefreshResult.Failure
            else -> ServerRefreshResult.PartialFailure
        }
    return ServerRefreshOutcome(result, ids.size, failedServers)
}

/** Only the visible page that requested this generation may present its completion. */
internal fun serverRefreshFeedback(
    state: ServerRefreshState,
    requestedGeneration: Long?,
    visible: Boolean,
): ServerRefreshOutcome? =
    state.outcome.takeIf {
        visible && state.feedbackAllowed && !state.refreshing && requestedGeneration == state.generation
    }

/** Owns the active request; idle/initial composition cannot fabricate a successful result. */
internal class ServerRefreshController(
    private val scope: CoroutineScope,
    private val refresh: suspend () -> ServerRefreshOutcome,
) {
    private val mutableState = MutableStateFlow(ServerRefreshState())
    val state = mutableState.asStateFlow()

    /** Persistent for this generation: UI collection may be suspended for the entire background interval. */
    fun suppressFeedback() {
        mutableState.update { it.copy(feedbackAllowed = false) }
    }

    fun refreshAll(): Long? {
        val previous = mutableState.value
        if (previous.refreshing) return null
        val generation = previous.generation + 1L
        mutableState.value = ServerRefreshState(generation = generation, refreshing = true)
        scope
            .launch {
                try {
                    val outcome = refresh()
                    currentCoroutineContext().ensureActive()
                    complete(generation, outcome)
                } catch (cancelled: CancellationException) {
                    complete(generation, ServerRefreshOutcome(ServerRefreshResult.Cancelled))
                    throw cancelled
                } catch (_: Exception) {
                    complete(generation, ServerRefreshOutcome(ServerRefreshResult.Failure))
                }
            }.invokeOnCompletion { cause ->
                // A scope cancelled before launch never enters the body or its catch clauses.
                if (cause is CancellationException) {
                    complete(generation, ServerRefreshOutcome(ServerRefreshResult.Cancelled))
                }
            }
        return generation
    }

    private fun complete(
        generation: Long,
        outcome: ServerRefreshOutcome,
    ) {
        mutableState.update { current ->
            if (current.generation == generation && current.refreshing) {
                current.copy(refreshing = false, outcome = outcome)
            } else {
                current
            }
        }
    }
}
