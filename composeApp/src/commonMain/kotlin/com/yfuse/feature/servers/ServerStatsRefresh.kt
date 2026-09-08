package com.yfuse.feature.servers

import com.yfuse.core.model.SavedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Resolve after health probing: its failover may have replaced the active address in the registry. */
internal suspend fun <T> refreshCurrentServerStats(
    serverIds: List<String>,
    lookup: (String) -> SavedServer?,
    fetch: suspend (SavedServer) -> Result<T>,
    record: (String, T) -> Unit,
): Map<String, Result<Unit>> =
    coroutineScope {
        serverIds
            .distinct()
            .map { id ->
                async {
                    val server = lookup(id)
                    val result =
                        if (server == null) {
                            Result.failure(IllegalStateException("服务器已删除"))
                        } else {
                            fetch(server)
                                .onFailure { if (it is CancellationException) throw it }
                                .mapCatching { counts ->
                                    check(lookup(id)?.sameManagementAccount(server) == true) { "服务器会话已改变" }
                                    record(id, counts)
                                }
                        }
                    id to result
                }
            }.awaitAll()
            .toMap()
    }
