package com.yfuse.tv.ui

import com.yfuse.core.account.AccountDeviceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class TvAccountSessionsState(
    val sessions: List<AccountDeviceSession> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val loaded: Boolean = false,
    val loadFailed: Boolean = false,
    val status: String? = null,
    val confirmOthers: Boolean = false,
)

/** Reserve operations before launching so repeated remote presses cannot queue revocations. */
internal class TvAccountSessionsController(
    private val scope: CoroutineScope,
    private val loadSessions: suspend () -> Result<List<AccountDeviceSession>>,
    private val revokeSession: suspend (String) -> Result<Unit>,
    private val revokeOthers: suspend () -> Result<Unit>,
) {
    private val mutableState = MutableStateFlow(TvAccountSessionsState())
    val state = mutableState.asStateFlow()

    fun refresh() {
        if (state.value.busy || state.value.confirmOthers) return
        mutableState.update { it.copy(busy = true, loading = true, status = null) }
        runOperation { reload() }
    }

    fun revoke(id: String): Boolean {
        val current = state.value
        if (current.busy || current.confirmOthers) return false
        val session = current.sessions.firstOrNull { it.id == id && !it.current } ?: return false
        mutableState.update { it.copy(busy = true, status = null) }
        runOperation {
            revokeSession(id).getOrThrow()
            currentCoroutineContext().ensureActive()
            mutableState.update {
                it.copy(
                    sessions =
                        it.sessions.filterNot { row ->
                            row.id == id
                        },
                    status = "已撤销 ${session.deviceName}",
                )
            }
            reload()
        }
        return true
    }

    fun requestRevokeOthers() {
        if (!state.value.busy && state.value.sessions.any { !it.current }) {
            mutableState.update { it.copy(confirmOthers = true) }
        }
    }

    fun dismissConfirmation() {
        mutableState.update { it.copy(confirmOthers = false) }
    }

    fun confirmRevokeOthers(): Boolean {
        if (!state.value.confirmOthers || state.value.busy) return false
        mutableState.update { it.copy(confirmOthers = false, busy = true, status = null) }
        runOperation {
            revokeOthers().getOrThrow()
            currentCoroutineContext().ensureActive()
            mutableState.update {
                it.copy(
                    sessions = it.sessions.filter(AccountDeviceSession::current),
                    status = "其他设备已全部退出",
                )
            }
            reload()
        }
        return true
    }

    private fun runOperation(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(status = "撤销失败：${error.message ?: "请稍后重试"}") }
            } finally {
                mutableState.update { it.copy(busy = false, loading = false) }
            }
        }
    }

    private suspend fun reload() {
        mutableState.update { it.copy(loading = true) }
        val result =
            try {
                loadSessions()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        currentCoroutineContext().ensureActive()
        result.fold(
            onSuccess = { sessions ->
                mutableState.update {
                    it.copy(sessions = sessions.distinctBy(AccountDeviceSession::id), loaded = true, loadFailed = false)
                }
            },
            onFailure = { error ->
                mutableState.update { it.copy(loadFailed = true, status = "读取设备会话失败：${error.message ?: "请稍后重试"}") }
            },
        )
    }
}
