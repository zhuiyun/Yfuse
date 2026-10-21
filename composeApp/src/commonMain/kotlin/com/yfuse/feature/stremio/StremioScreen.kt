package com.yfuse.feature.stremio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.OperationFeedback
import com.yfuse.core.designsystem.OperationTone
import com.yfuse.core.designsystem.PageHint
import com.yfuse.core.designsystem.PlatformBackHandler
import com.yfuse.core.designsystem.YfButton
import com.yfuse.core.designsystem.motionItem
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.stremio.StremioRepository
import com.yfuse.feature.player.PlayerLauncher
import com.yfuse.feature.profile.SettingsPage
import org.koin.core.context.GlobalContext
import org.koin.core.parameter.parametersOf

@Composable
fun StremioScreen(
    personal: PersonalLibraryRepository,
    onBack: () -> Unit,
) {
    val policy by personal.policy.collectAsState()
    if (policy.child) {
        SettingsPage("Stremio 插件", onBack = onBack) {
            item { PageHint("儿童资料暂不开放第三方插件，请切换至成人资料。") }
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
    LaunchedEffect(controller) { controller.load() }
    LaunchedEffect(visible) { if (!visible) controller.deactivate() }
    PlatformBackHandler(enabled = visible && (state.catalog != null || state.detail != null)) { controller.back() }
    val back = { if (!controller.back()) onBack() }
    val title =
        when {
            state.video != null -> "选择播放源"
            state.detail != null -> state.detail!!.name
            state.catalog != null -> state.catalog!!.catalog.name
            else -> "Stremio 插件"
        }
    val listState =
        rememberStremioListState(state.catalog?.key, state.detail?.let { it.type to it.id }, state.video?.id)
    key(state.catalog?.key, state.detail?.type, state.detail?.id, state.video?.id) {
        SettingsPage(
            title = title,
            subtitle = state.video?.label ?: "目录 · 搜索 · 选集 · HTTP 播放",
            onBack = back,
            state = listState,
        ) {
            stremioStatus(state)
            when {
                !state.ready -> item { StremioInset { YfButton("重新读取", controller::load, enabled = !state.busy) } }
                state.video != null -> stremioSources(state, controller)
                state.detail != null -> stremioDetail(state, controller)
                state.catalog != null -> stremioCatalog(state, controller)
                else -> stremioHome(state, addons, controller)
            }
        }
    }
    state.launch?.takeIf { visible }?.let { item ->
        PlayerLauncher(listOf(item), 0, 0L, onLaunched = controller::consumeLaunch)
    }
}

/** Keep the four active navigation levels mounted; discarded branches do not accumulate state. */
@Composable
internal fun rememberStremioListState(
    catalogKey: String?,
    detailKey: Pair<String, String>?,
    videoKey: String?,
): LazyListState {
    val home = rememberLazyListState()
    val catalog = key(catalogKey) { rememberLazyListState() }
    val detail = key(catalogKey, detailKey) { rememberLazyListState() }
    val sources = key(catalogKey, detailKey, videoKey) { rememberLazyListState() }
    return when {
        videoKey != null -> sources
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
