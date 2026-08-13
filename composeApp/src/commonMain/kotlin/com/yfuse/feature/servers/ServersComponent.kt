package com.yfuse.feature.servers

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.network.LanDiscovery
import com.yfuse.core.util.componentScope
import com.yfuse.feature.player.PlaybackReportingCoordinator
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koin.core.context.GlobalContext

class ServersComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    repo: EmbyRepository,
    registry: ServerRegistry,
    private val onServerAdded: () -> Unit,
    val onBack: () -> Unit,
) : ComponentContext by componentContext {
    private val discovery: LanDiscovery = GlobalContext.get().get()
    private val playbackReportingCoordinator: PlaybackReportingCoordinator? =
        runCatching {
            GlobalContext.get().get<PlaybackReportingCoordinator>()
        }.getOrNull()
    val store =
        ServersStoreFactory(
            storeFactory = storeFactory,
            repo = repo,
            registry = registry,
            discovery = discovery,
            onAuthenticated = { serverId ->
                playbackReportingCoordinator?.resumeAfterAuthentication(serverId)
            },
        ).create()

    init {
        val scope = componentScope(lifecycle)
        store.labels
            .onEach { if (it is ServersLabel.ServerAdded) onServerAdded() }
            .launchIn(scope)
        lifecycle.doOnDestroy(store::dispose)
    }
}
