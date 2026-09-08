package com.yfuse.feature.servers

import com.yfuse.core.data.ServerManagementSnapshot
import com.yfuse.core.model.SavedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ServerManagementUiState {
    data object Idle : ServerManagementUiState

    data class Loading(
        val serverId: String,
        val sessionId: Long = 0L,
    ) : ServerManagementUiState

    data class Ready(
        val serverId: String,
        val snapshot: ServerManagementSnapshot,
        val busyId: String? = null,
        val message: String? = null,
        val error: String? = null,
        val sessionId: Long = 0L,
    ) : ServerManagementUiState

    data class Error(
        val serverId: String,
        val message: String,
        val sessionId: Long = 0L,
    ) : ServerManagementUiState
}

internal data class ServerManagementActionResult(
    val message: String,
    val reloadServerId: String? = null,
)

/** Owned by the component's UI scope. Closing a panel cancels reads, never a submitted server action. */
internal class ServerManagementController(
    private val scope: CoroutineScope,
    private val lookup: (String) -> SavedServer?,
    private val load: suspend (SavedServer) -> Result<ServerManagementSnapshot>,
) {
    private data class Session(
        val generation: Long,
        val server: SavedServer,
    )

    private var generation = 0L
    private var session: Session? = null
    private var loading: Job? = null
    private val mutableState = MutableStateFlow<ServerManagementUiState>(ServerManagementUiState.Idle)
    val state = mutableState.asStateFlow()

    fun open(serverId: String) {
        close()
        val server = lookup(serverId) ?: return
        val owner = Session(generation, server)
        session = owner
        mutableState.value = ServerManagementUiState.Loading(server.id, owner.generation)
        loading =
            scope.launch {
                val result = managementResult { load(server) }
                if (session !== owner) return@launch
                if (lookup(server.id)?.sameManagementAccount(server) != true) {
                    close()
                    return@launch
                }
                mutableState.value =
                    result.fold(
                        onSuccess = { ServerManagementUiState.Ready(server.id, it, sessionId = owner.generation) },
                        onFailure = {
                            ServerManagementUiState.Error(server.id, it.message ?: "读取服务器管理信息失败", owner.generation)
                        },
                    )
            }
    }

    fun close() {
        generation++
        session = null
        loading?.cancel()
        loading = null
        mutableState.value = ServerManagementUiState.Idle
    }

    fun closeIfServer(serverId: String) {
        if (session?.server?.id == serverId) close()
    }

    /** The rendered generation is required even when a different panel reopens the same server. */
    fun submit(
        serverId: String,
        sessionId: Long,
        busyId: String,
        accepts: (ServerManagementSnapshot) -> Boolean,
        action: suspend (SavedServer) -> Result<ServerManagementActionResult>,
    ) {
        val owner = session ?: return
        val ready = mutableState.value as? ServerManagementUiState.Ready ?: return
        if (owner.generation != sessionId ||
            owner.server.id != serverId ||
            ready.serverId != serverId ||
            ready.sessionId != sessionId ||
            ready.busyId != null ||
            !accepts(ready.snapshot)
        ) {
            return
        }
        val target = lookup(serverId)
        if (target?.sameManagementAccount(owner.server) != true) {
            close()
            return
        }
        mutableState.value = ready.copy(busyId = busyId, message = null, error = null)
        scope.launch {
            // Capture the accepted target before suspension; later navigation cannot retarget it.
            val result = managementResult { action(target) }
            if (session !== owner) return@launch
            result.getOrNull()?.reloadServerId?.let {
                open(it)
                return@launch
            }
            if (lookup(serverId)?.sameManagementAccount(owner.server) != true) {
                open(serverId)
                return@launch
            }
            mutableState.value =
                result.fold(
                    onSuccess = { ready.copy(message = it.message) },
                    onFailure = { ready.copy(error = it.message ?: "服务器操作失败") },
                )
        }
    }
}

/** Route/icon edits keep the account; replaced credentials or users must invalidate its object IDs. */
internal fun SavedServer.sameManagementAccount(other: SavedServer): Boolean =
    id == other.id &&
        kind == other.kind &&
        userId == other.userId &&
        accessToken == other.accessToken &&
        cloudAccessToken == other.cloudAccessToken &&
        cloudOwnerAccessToken == other.cloudOwnerAccessToken

private suspend fun <T> managementResult(block: suspend () -> Result<T>): Result<T> =
    try {
        block().onFailure { if (it is CancellationException) throw it }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

internal fun ServerManagementUiState.forServer(serverId: String): ServerManagementUiState =
    when (this) {
        ServerManagementUiState.Idle -> this
        is ServerManagementUiState.Loading -> if (this.serverId == serverId) this else ServerManagementUiState.Idle
        is ServerManagementUiState.Ready -> if (this.serverId == serverId) this else ServerManagementUiState.Idle
        is ServerManagementUiState.Error -> if (this.serverId == serverId) this else ServerManagementUiState.Idle
    }
