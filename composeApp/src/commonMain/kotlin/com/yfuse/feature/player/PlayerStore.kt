package com.yfuse.feature.player

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.logging.AppLog
import com.yfuse.core.network.EmbyStream
import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.sync.watchKey
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
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    /**
     * The series this entry belongs to, or null for a film. Skip times are stored against
     * it — an opening is a property of the show, not of any one episode, so setting it once
     * has to hold for the rest of the queue and for every season after it.
     */
    val seriesId: String? = null,
    val seriesName: String? = null,
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
                    AppLog.error(
                        category = "feature.player",
                        event = "server_missing",
                        message = "Playback queue could not be built because no server is available",
                    )
                    dispatch(PlayerMsg.Failed("没有可用的服务器"))
                    return@launch
                }

                fun itemOf(
                    id: String,
                    title: String,
                    playbackSegments: List<PlaybackSegment> = emptyList(),
                    providerIds: Map<String, String> = emptyMap(),
                    seasonNumber: Int? = null,
                    episodeNumber: Int? = null,
                    seriesId: String? = null,
                    seriesName: String? = null,
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
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    seriesId = seriesId,
                    seriesName = seriesName,
                    watchKey = providerIds.watchKey(id),
                )

                val detailResult = repo.itemDetail(server, itemId)
                detailResult.onFailure {
                    AppLog.warning(
                        category = "feature.player",
                        event = "item_detail_failed",
                        message = "Playback queue item detail failed to load",
                        throwable = it,
                        attributes = mapOf("serverId" to server.id),
                    )
                }
                val detail = detailResult.getOrNull()
                val seriesId = detail?.seriesId

                if (detail?.type == "Episode" && seriesId != null) {
                    val episodesResult = repo.episodes(server, seriesId, null)
                    episodesResult.onFailure {
                        AppLog.warning(
                            category = "feature.player",
                            event = "episode_queue_failed",
                            message = "Episode playback queue failed to load",
                            throwable = it,
                            attributes = mapOf("serverId" to server.id),
                        )
                    }
                    val episodes = episodesResult.getOrDefault(emptyList())
                    if (episodes.isNotEmpty()) {
                        val items = episodes.map { ep ->
                            itemOf(
                                ep.id,
                                listOfNotNull(ep.indexNumber?.let { "第 $it 集" }, ep.name).joinToString("  "),
                                ep.playbackSegments,
                                ep.providerIds,
                                ep.seasonNumber,
                                ep.indexNumber,
                                seriesId,
                                detail.seriesName,
                            )
                        }
                        val index = items.indexOfFirst { it.id == itemId }.coerceAtLeast(0)
                        AppLog.info(
                            category = "feature.player",
                            event = "queue_ready",
                            message = "Episode playback queue prepared",
                            attributes = mapOf("itemCount" to items.size.toString()),
                        )
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
                AppLog.info(
                    category = "feature.player",
                    event = "queue_ready",
                    message = "Single-item playback queue prepared",
                    attributes = mapOf("detailAvailable" to (detail != null).toString()),
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

// watchKey now lives in com.yfuse.core.sync alongside the invite payload that carries it.
