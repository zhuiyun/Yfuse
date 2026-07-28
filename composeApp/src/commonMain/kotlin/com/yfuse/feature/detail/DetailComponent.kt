package com.yfuse.feature.detail

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.network.EmbyStream
import com.yfuse.core.offline.OfflineDownloadRequest
import com.yfuse.core.offline.OfflineMediaManager
import com.yfuse.core.util.componentScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koin.core.context.GlobalContext

class DetailComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    repo: EmbyRepository,
    registry: ServerRegistry,
    itemId: String,
    serverId: String? = null,
    val onBack: () -> Unit,
    private val onPlay: (serverId: String, itemId: String, startPositionTicks: Long) -> Unit,
) : ComponentContext by componentContext {

    val store = DetailStoreFactory(storeFactory, repo, registry, itemId, serverId).create()

    fun download() {
        val state = store.state
        val detail = state.detail ?: return
        val server = state.server ?: return
        GlobalContext.get().get<OfflineMediaManager>().enqueue(
            OfflineDownloadRequest(
                serverId = server.id,
                itemId = detail.id,
                title = detail.title,
                sourceUrl = EmbyStream.directPlay(
                    server.baseUrl,
                    detail.id,
                    server.accessToken,
                ),
                posterUrl = EmbyImages.poster(server.baseUrl, detail),
            ),
        )
    }

    init {
        val scope = componentScope(lifecycle)
        store.labels
            .onEach {
                if (it is DetailLabel.Play) {
                    onPlay(it.serverId, it.itemId, it.startPositionTicks)
                }
            }
            .launchIn(scope)
        lifecycle.doOnDestroy(store::dispose)
    }
}
