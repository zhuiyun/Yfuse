package com.yfuse.feature.player

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.network.EmbyStream
import kotlinx.coroutines.launch

/** One entry in the player's playlist. */
data class PlayerMediaItem(val id: String, val url: String, val title: String)

data class PlayerState(
    val loading: Boolean = true,
    val items: List<PlayerMediaItem> = emptyList(),
    val startIndex: Int = 0,
    val startPositionMs: Long = 0L,
    val error: String? = null,
)

sealed interface PlayerIntent

private sealed interface PlayerAction { data object Load : PlayerAction }

private sealed interface PlayerMsg {
    data class Ready(val items: List<PlayerMediaItem>, val startIndex: Int, val startMs: Long) : PlayerMsg
    data class Failed(val message: String) : PlayerMsg
}

/**
 * Builds the playback queue. For an episode the whole series is queued so the
 * player's next/previous controls move between episodes.
 */
class PlayerStoreFactory(
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
    private val itemId: String,
    private val startPositionTicks: Long,
) {
    fun create(): Store<PlayerIntent, PlayerState, Nothing> =
        storeFactory.create(
            name = "PlayerStore",
            initialState = PlayerState(),
            bootstrapper = coroutineBootstrapper<PlayerAction> { dispatch(PlayerAction.Load) },
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl :
        CoroutineExecutor<PlayerIntent, PlayerAction, PlayerState, PlayerMsg, Nothing>() {

        override fun executeAction(action: PlayerAction) {
            val server = registry.defaultServer
            val startMs = startPositionTicks / 10_000L
            scope.launch {
                if (server == null) {
                    dispatch(PlayerMsg.Failed("没有可用的服务器"))
                    return@launch
                }

                fun urlOf(id: String) = EmbyStream.directPlay(server.baseUrl, id, server.accessToken)

                val detail = repo.itemDetail(server, itemId).getOrNull()
                val seriesId = detail?.seriesId

                if (detail?.type == "Episode" && seriesId != null) {
                    val episodes = repo.episodes(server, seriesId, null).getOrDefault(emptyList())
                    if (episodes.isNotEmpty()) {
                        val items = episodes.map { ep ->
                            PlayerMediaItem(
                                id = ep.id,
                                url = urlOf(ep.id),
                                title = listOfNotNull(ep.indexNumber?.let { "第 $it 集" }, ep.name).joinToString("  "),
                            )
                        }
                        val index = items.indexOfFirst { it.id == itemId }.coerceAtLeast(0)
                        dispatch(PlayerMsg.Ready(items, index, startMs))
                        return@launch
                    }
                }

                val single = PlayerMediaItem(itemId, urlOf(itemId), detail?.title ?: "")
                dispatch(PlayerMsg.Ready(listOf(single), 0, startMs))
            }
        }

        override fun executeIntent(intent: PlayerIntent) = Unit
    }

    private object ReducerImpl : Reducer<PlayerState, PlayerMsg> {
        override fun PlayerState.reduce(msg: PlayerMsg): PlayerState = when (msg) {
            is PlayerMsg.Ready -> copy(
                loading = false,
                items = msg.items,
                startIndex = msg.startIndex,
                startPositionMs = msg.startMs,
            )
            is PlayerMsg.Failed -> copy(loading = false, error = msg.message)
        }
    }
}
