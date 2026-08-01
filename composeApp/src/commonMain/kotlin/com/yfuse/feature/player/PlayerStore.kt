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
import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.sync.episodeWatchKey
import com.yfuse.core.sync.watchKey
import com.yfuse.core.sync.watchMatchKeys
import kotlinx.coroutines.launch

/**
 * One selectable file behind a queue entry, with its stream URLs already built.
 *
 * URLs rather than a bare id so that switching version mid-playback needs nothing from the
 * network and no credentials in the player: every version's addresses were resolved when
 * the queue was built.
 */
data class PlayerMediaVersion(
    val id: String,
    /** The server's name for this file — "Bluray 2160p", or its container. */
    val label: String,
    /** `4K HDR10 · 42.3 GB · 68 Mbps · MKV` */
    val detail: String,
    val url: String,
    val transcodeUrl: String,
    val fallbackTranscodeUrl: String,
    /** `MKV`, for the player's readout line. */
    val container: String? = null,
    /**
     * What the file carries, decided by [com.yfuse.core.model.MediaVersion] rather than
     * re-derived here — Emby hides Dolby Vision in four different fields and one place
     * knowing where is enough.
     */
    val dolbyVision: Boolean = false,
    val dolbyAtmos: Boolean = false,
)

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
    /** Cross-server identity used by watch-together rooms — the one this device publishes. */
    val watchKey: String = id,
    /**
     * Every name this entry answers to when a room says what it is playing. A superset of
     * [watchKey]: the other device picked its key from its own metadata, which is rarely
     * the same subset as this one's. See `watchMatchKeys`.
     */
    val matchKeys: List<String> = listOf(watchKey),
    /**
     * Every file the server holds for this entry. Empty for entries whose sources were
     * never fetched — the sibling episodes of a queue, which are listed rather than
     * detailed — and for the ordinary case of a library holding exactly one file.
     */
    val versions: List<PlayerMediaVersion> = emptyList(),
    /** Which of [versions] the URLs above were built from. */
    val versionId: String? = null,
) {
    /**
     * The file currently playing, when the entry's sources were fetched at all.
     *
     * Null for the sibling episodes of a queue: they are listed from `/Shows/…/Episodes`,
     * which does not carry `MediaSources`, so nothing here knows what those files hold. The
     * readout and the 杜比 badge simply say less on them rather than guessing from the
     * episode that was opened — a different episode is a different file.
     */
    val activeVersion: PlayerMediaVersion?
        get() = versions.firstOrNull { it.id == versionId } ?: versions.firstOrNull()

    /** The same entry playing a different file, or unchanged when there is no such file. */
    fun withVersion(id: String?): PlayerMediaItem {
        val version = versions.firstOrNull { it.id == id } ?: return this
        return copy(
            url = version.url,
            transcodeUrl = version.transcodeUrl,
            fallbackTranscodeUrl = version.fallbackTranscodeUrl,
            versionId = version.id,
        )
    }
}

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
    /** The file the detail page picked, when the item has more than one. */
    private val mediaSourceId: String? = null,
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

                fun versionsOf(id: String, versions: List<MediaVersion>) = versions.map {
                    PlayerMediaVersion(
                        id = it.id,
                        label = it.name,
                        detail = it.summary,
                        url = EmbyStream.directPlay(
                            server.baseUrl,
                            id,
                            server.accessToken,
                            mediaSourceId = it.id,
                        ),
                        transcodeUrl = EmbyStream.transcode(
                            server.baseUrl,
                            id,
                            server.accessToken,
                            mediaSourceId = it.id,
                        ),
                        fallbackTranscodeUrl = EmbyStream.progressiveTranscode(
                            server.baseUrl,
                            id,
                            server.accessToken,
                            mediaSourceId = it.id,
                        ),
                        container = it.container?.uppercase(),
                        dolbyVision = it.isDolbyVision,
                        dolbyAtmos = it.hasDolbyAtmos,
                    )
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
                    seriesProviderIds: Map<String, String>? = null,
                    versions: List<MediaVersion> = emptyList(),
                ): PlayerMediaItem {
                  val playerVersions = versionsOf(id, versions)
                  // The file the detail page picked, else the server's first — which is
                  // also what an unqualified stream request would have returned anyway.
                  val chosen = playerVersions.firstOrNull { it.id == mediaSourceId }
                      ?: playerVersions.firstOrNull()
                  return PlayerMediaItem(
                    id = id,
                    url = chosen?.url
                        ?: EmbyStream.directPlay(server.baseUrl, id, server.accessToken),
                    transcodeUrl = chosen?.transcodeUrl
                        ?: EmbyStream.transcode(server.baseUrl, id, server.accessToken),
                    title = title,
                    fallbackTranscodeUrl = chosen?.fallbackTranscodeUrl
                        ?: EmbyStream.progressiveTranscode(
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
                    watchKey = if (seriesProviderIds == null) {
                        providerIds.watchKey(id)
                    } else {
                        episodeWatchKey(
                            ownProviderIds = providerIds,
                            seriesProviderIds = seriesProviderIds,
                            seasonNumber = seasonNumber,
                            episodeNumber = episodeNumber,
                            fallbackId = id,
                        )
                    },
                    matchKeys = watchMatchKeys(
                        ownProviderIds = providerIds,
                        seriesProviderIds = seriesProviderIds.orEmpty(),
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        fallbackId = id,
                    ),
                    versions = playerVersions,
                    versionId = chosen?.id,
                  )
                }

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
                    // The show's provider ids, not this episode's: they are what makes an
                    // episode recognisable on someone else's server (see episodeWatchKey).
                    // One extra request per queue, and a miss only costs the cross-server
                    // half of watch-together.
                    val seriesProviderIds = repo.itemDetail(server, seriesId)
                        .getOrNull()
                        ?.providerIds
                        .orEmpty()
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
                                seriesProviderIds,
                                // Only the episode actually opened has had its sources
                                // fetched; the rest of the season came from a list query
                                // that doesn't carry them, and detailing every episode to
                                // populate a picker almost nobody opens isn't worth the
                                // round trips.
                                versions = if (ep.id == itemId) detail.versions else emptyList(),
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
                                id = itemId,
                                title = detail?.title ?: "",
                                playbackSegments = detail?.playbackSegments.orEmpty(),
                                providerIds = detail?.providerIds.orEmpty(),
                                versions = detail?.versions.orEmpty(),
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
