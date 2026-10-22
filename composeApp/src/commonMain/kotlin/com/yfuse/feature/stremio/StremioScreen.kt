package com.yfuse.feature.stremio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.OperationFeedback
import com.yfuse.core.designsystem.OperationTone
import com.yfuse.core.designsystem.PlatformBackHandler
import com.yfuse.core.designsystem.YfButton
import com.yfuse.core.designsystem.motionItem
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.stremio.StremioRepository
import com.yfuse.feature.player.PlayerLauncher
import com.yfuse.feature.widgets.WidgetEmptyState
import com.yfuse.feature.widgets.WidgetFrame
import com.yfuse.feature.widgets.WidgetIconButton
import com.yfuse.feature.widgets.WidgetSegmentedControl
import org.koin.core.context.GlobalContext
import org.koin.core.parameter.parametersOf

@Composable
fun StremioScreen(
    personal: PersonalLibraryRepository,
    onBack: () -> Unit,
) {
    val policy by personal.policy.collectAsState()
    if (policy.child) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            WidgetFrame("Stremio 插件", onBack) {
                StremioInset {
                    WidgetEmptyState(AppIcons.User, "当前资料暂不开放插件", "切换至成人资料后，可以管理第三方插件。")
                }
            }
        }
        return
    }
    key(policy.profileId) {
        val repository = remember { GlobalContext.get().get<StremioRepository> { parametersOf(policy.profileId) } }
        val scope = rememberCoroutineScope()
        val controller = remember(repository) { StremioController(repository, scope) }
        StremioPage(controller, onBack)
    }
}

@Composable
private fun StremioPage(
    controller: StremioController,
    onBack: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val addons by controller.repository.addons.collectAsState()
    val visible = LocalRouteVisible.current
    var installing by rememberSaveable { mutableStateOf(false) }
    var catalogsSelected by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(controller) { controller.load() }
    LaunchedEffect(visible) { if (!visible) controller.deactivate() }
    PlatformBackHandler(enabled = visible && (state.catalog != null || state.detail != null)) { controller.back() }
    val back = { if (!controller.back()) onBack() }
    val title =
        when {
            state.detail != null -> state.detail!!.name
            state.catalog != null -> state.catalog!!.catalog.name
            else -> "Stremio 插件"
        }
    val listState =
        rememberStremioListState(state.catalog?.key, state.detail?.let { it.type to it.id })
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        WidgetFrame(title, back, action = {
            if (state.catalog == null && state.detail == null) {
                WidgetIconButton(AppIcons.Add, "安装 Stremio 插件", { installing = true })
            }
        }) {
            if (state.catalog == null && state.detail == null) {
                WidgetSegmentedControl(
                    listOf("插件 ${addons.size}", "目录 ${state.catalogs.size}"),
                    if (catalogsSelected) 1 else 0,
                    { catalogsSelected = it == 1 },
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
            key(state.catalog?.key, state.detail?.type, state.detail?.id) {
                LazyColumn(
                    Modifier.weight(1f),
                    state = listState,
                    contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    stremioStatus(state)
                    when {
                        !state.ready ->
                            item {
                                StremioInset {
                                    YfButton(
                                        "重新读取",
                                        controller::load,
                                        enabled = !state.busy,
                                    )
                                }
                            }
                        state.detail != null -> stremioDetail(state, controller)
                        state.catalog != null -> stremioCatalog(state, controller)
                        else -> stremioHome(state, addons, controller, catalogsSelected, { installing = true })
                    }
                }
            }
        }
    }
    if (installing) {
        StremioInstaller(state, controller) {
            controller.discardCandidate()
            installing = false
        }
    }
    state.launch?.takeIf { visible }?.let { item ->
        PlayerLauncher(listOf(item), 0, 0L, onLaunched = controller::consumeLaunch)
    }
}

/** Detail, episodes and sources share one scroll position; returning restores the parent catalog. */
@Composable
internal fun rememberStremioListState(
    catalogKey: String?,
    detailKey: Pair<String, String>?,
): LazyListState {
    val home = rememberLazyListState()
    val catalog = key(catalogKey) { rememberLazyListState() }
    val detail = key(catalogKey, detailKey) { rememberLazyListState() }
    return when {
        detailKey != null -> detail
        catalogKey != null -> catalog
        else -> home
    }
}

private fun LazyListScope.stremioStatus(state: StremioScreenState) {
    if (state.busy || state.loading) {
        motionItem("working") {
            StremioInset { OperationFeedback(if (state.busy) "正在处理插件…" else "正在加载…", OperationTone.Working) }
        }
    }
    state.message?.let { message -> motionItem("message") { StremioInset { OperationFeedback(message) } } }
    state.failures.forEachIndexed { index, failure ->
        motionItem("failure-$index") { StremioInset { OperationFeedback(failure, OperationTone.Error) } }
    }
}

@Composable
internal fun StremioInset(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        content()
    }
}
