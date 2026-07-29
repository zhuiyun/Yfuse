package com.yfuse.feature.player

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.network.EmbyStream
import com.yfuse.core.model.PlaybackSegment
import kotlinx.coroutines.launch

/** One entry in the player's playlist, with a transcode fallback URL. */
data class PlayerMediaItem(
    val id: String,
    val url: String,
    val transcodeUrl: String,
    val title: String,
    val fallbackTranscodeUrl: String = transcodeUrl,
    val serverId: String? = null,
    val playbackSegments: List<PlaybackSegment> = emptyList(),
    /** Cross-server identity used by watch-together rooms. */
    val watchKey: String = id,
)

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
    private val serverId: String? = null,
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
            val server = serverId?.let(registry::serverById) ?: registry.defaultServer
            val startMs = startPositionTicks / 10_000L
            scope.launch {
                if (server == null) {
                    dispatch(PlayerMsg.Failed("没有可用的服务器"))
                    return@launch
                }

                fun itemOf(
                    id: String,
                    title: String,
                    playbackSegments: List<PlaybackSegment> = emptyList(),
                    providerIds: Map<String, String> = emptyMap(),
                ) = PlayerMediaItem(
                    id = id,
                    url = EmbyStream.directPlay(server.baseUrl, id, server.accessToken),
                    transcodeUrl = EmbyStream.transcode(server.baseUrl, id, server.accessToken),
                    title = title,
                    fallbackTranscodeUrl = EmbyStream.progressiveTranscode(
                        server.baseUrl,
                        id,
                        server.accessToken,
                    ),
                    serverId = server.id,
                    playbackSegments = playbackSegments,
                    watchKey = providerIds.watchKey(id),
                )

                val detail = repo.itemDetail(server, itemId).getOrNull()
                val seriesId = detail?.seriesId

                if (detail?.type == "Episode" && seriesId != null) {
                    val episodes = repo.episodes(server, seriesId, null).getOrDefault(emptyList())
                    if (episodes.isNotEmpty()) {
                        val items = episodes.map { ep ->
                            itemOf(
                                ep.id,
                                listOfNotNull(ep.indexNumber?.let { "第 $it 集" }, ep.name).joinToString("  "),
                                ep.playbackSegments,
                                ep.providerIds,
                            )
                        }
                        val index = items.indexOfFirst { it.id == itemId }.coerceAtLeast(0)
                        dispatch(PlayerMsg.Ready(items, index, startMs))
                        return@launch
                    }
                }

                dispatch(
                    PlayerMsg.Ready(
                        listOf(
                            itemOf(
                                itemId,
                                detail?.title ?: "",
                                detail?.playbackSegments.orEmpty(),
                                detail?.providerIds.orEmpty(),
                            ),
                        ),
                        0,
                        startMs,
                    ),
                )
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

private fun Map<String, String>.watchKey(fallbackId: String): String {
    val preferred = listOf("Tmdb", "Tvdb", "Imdb")
    for (provider in preferred) {
        entries.firstOrNull { it.key.equals(provider, ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?.let { return "${provider.lowercase()}:$it" }
    }
    return "emby:$fallbackId"
}
