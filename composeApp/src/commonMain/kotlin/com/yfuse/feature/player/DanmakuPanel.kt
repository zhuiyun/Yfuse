package com.yfuse.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.DanmakuEpisode
import com.yfuse.core.data.DanmakuSearchResult
import com.yfuse.core.data.DanmakuSource
import com.yfuse.core.data.activeOr
import com.yfuse.core.designsystem.continuousRounded
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.PlayerTokens
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.sc

data class DanmakuPanelState(
    val sources: List<DanmakuSource> = emptyList(),
    val activeSourceId: String? = null,
    val enabled: Boolean = false,
    val count: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val matchLabel: String? = null,
    val matchPinned: Boolean = false,
    val mergeDuplicates: Boolean = true,
    val canSend: Boolean = false,
    val sendError: String? = null,
    val sending: Boolean = false,
    val areaOptions: List<Pair<String, Boolean>> = emptyList(),
    val fontOptions: List<Pair<String, Boolean>> = emptyList(),
    val speedOptions: List<Pair<String, Boolean>> = emptyList(),
    val opacityOptions: List<Pair<String, Boolean>> = emptyList(),
    val search: DanmakuSearchState = DanmakuSearchState(),
) {
    val activeSource: DanmakuSource? get() = sources.activeOr(activeSourceId)
    val configured: Boolean get() = activeSource != null
    val searchable: Boolean get() = sources.any { it.supportsSearch }
}

data class DanmakuSearchState(
    val query: String = "",
    val running: Boolean = false,
    val error: String? = null,
    val results: List<DanmakuSearchResult> = emptyList(),
    val openResult: DanmakuSearchResult? = null,
    val episodes: List<DanmakuEpisode> = emptyList(),
    val searched: Boolean = false,
    val recent: List<String> = emptyList(),
)

data class DanmakuPanelActions(
    val onToggle: () -> Unit = {},
    val onSelectArea: (Int) -> Unit = {},
    val onSelectFont: (Int) -> Unit = {},
    val onSelectSpeed: (Int) -> Unit = {},
    val onSelectOpacity: (Int) -> Unit = {},
    val onSelectSource: (String) -> Unit = {},
    val onOpenSearch: () -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onSubmitSearch: () -> Unit = {},
    val onOpenResult: (DanmakuSearchResult) -> Unit = {},
    val onBackToResults: () -> Unit = {},
    val onPickEpisode: (DanmakuEpisode) -> Unit = {},
    val onClearMatch: () -> Unit = {},
    val onToggleMerge: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onSend: (String) -> Unit = {},
)

private data class DanmakuDisplayPreset(
    val label: String,
    val summary: String,
    val areaIndex: Int,
    val fontIndex: Int,
    val speedIndex: Int,
    val opacityIndex: Int,
)

private val DanmakuDisplayPresets = listOf(
    DanmakuDisplayPreset("轻量", "1/4 · 小 · 慢 · 50%", 0, 0, 0, 0),
    DanmakuDisplayPreset("标准", "1/2 · 标准 · 标准 · 75%", 1, 1, 1, 1),
    DanmakuDisplayPreset("热闹", "3/4 · 大 · 快 · 100%", 2, 2, 2, 2),
)

private fun DanmakuDisplayPreset.isSelected(state: DanmakuPanelState): Boolean =
    state.areaOptions.getOrNull(areaIndex)?.second == true &&
        state.fontOptions.getOrNull(fontIndex)?.second == true &&
        state.speedOptions.getOrNull(speedIndex)?.second == true &&
        state.opacityOptions.getOrNull(opacityIndex)?.second == true

private fun DanmakuDisplayPreset.apply(actions: DanmakuPanelActions) {
    actions.onSelectArea(areaIndex)
    actions.onSelectFont(fontIndex)
    actions.onSelectSpeed(speedIndex)
    actions.onSelectOpacity(opacityIndex)
}

@Composable
internal fun DanmakuTab(
    state: DanmakuPanelState,
    actions: DanmakuPanelActions,
    onOpenSearch: () -> Unit,
    onOpenSend: () -> Unit,
) {
    var advancedVisible by remember { mutableStateOf(false) }

    GroupLabel("弹幕")
    OptionRow(
        if (state.enabled) "关闭弹幕" else "开启弹幕",
        state.enabled,
        onClick = actions.onToggle,
    )
    Text(
        when {
            !state.configured -> "请先在个人中心配置弹幕链接"
            state.loading -> "正在加载弹幕…"
            state.error != null -> state.error
            state.count > 0 -> "弹幕条数：${state.count}"
            else -> "没有匹配到弹幕"
        },
        style = mr(10.5f, 500),
        color = if (state.error != null) Brand.Danger else Color.White.copy(alpha = 0.56f),
        modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp),
    )
    if (state.error != null && !state.loading) {
        OptionRow("重试", selected = false, onClick = actions.onRetry)
    }
    state.matchLabel?.let { label ->
        Text(
            label,
            style = sc(10.5f, 600),
            color = Color.White.copy(alpha = 0.82f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
    if (state.searchable) {
        OptionRow(
            "搜索弹幕",
            selected = false,
            onClick = onOpenSearch,
            actionLabel = if (state.matchPinned) "取消匹配" else null,
            onAction = actions.onClearMatch,
        )
    }
    if (state.canSend) {
        OptionRow("发送弹幕", selected = false, onClick = onOpenSend)
    }
    OptionRow("合并重复弹幕", state.mergeDuplicates, onClick = actions.onToggleMerge)

    GroupLabel("显示风格")
    DanmakuDisplayPresets.forEach { preset ->
        OptionRow(
            label = "${preset.label} · ${preset.summary}",
            selected = preset.isSelected(state),
            onClick = { preset.apply(actions) },
        )
    }

    OptionRow(
        label = if (advancedVisible) "收起高级设置" else "高级设置",
        selected = advancedVisible,
        onClick = { advancedVisible = !advancedVisible },
    )

    if (advancedVisible) {
        GroupLabel("显示区域")
        state.areaOptions.forEachIndexed { index, (label, selected) ->
            OptionRow(label, selected, onClick = { actions.onSelectArea(index) })
        }
        GroupLabel("字体大小")
        state.fontOptions.forEachIndexed { index, (label, selected) ->
            OptionRow(label, selected, onClick = { actions.onSelectFont(index) })
        }
        GroupLabel("移动速度")
        state.speedOptions.forEachIndexed { index, (label, selected) ->
            OptionRow(label, selected, onClick = { actions.onSelectSpeed(index) })
        }
        GroupLabel("透明度")
        state.opacityOptions.forEachIndexed { index, (label, selected) ->
            OptionRow(label, selected, onClick = { actions.onSelectOpacity(index) })
        }
    }
}

@Composable
internal fun DanmakuSearchPanel(
    state: DanmakuPanelState,
    actions: DanmakuPanelActions,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val search = state.search
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).noRippleClickable(onDismiss))
    Column(
        modifier
            .fillMaxHeight()
            .width(340.dp)
            .glass(
                shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
                fill = PlayerTokens.drawerFillLandscape,
                border = Color.White.copy(alpha = 0.24f),
            )
            .noRippleClickable { }
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("搜索弹幕", style = sc(13.5f, 700), color = Color.White)
            Icon(
                AppIcons.Close,
                contentDescription = "关闭",
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(12.dp).noRippleClickable(onDismiss),
            )
        }

        SearchField(
            value = search.query,
            onValueChange = actions.onQueryChange,
            onSubmit = actions.onSubmitSearch,
            autoFocus = true,
        )

        if (state.sources.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.sources.forEach { source ->
                    SourceChip(
                        label = source.name,
                        active = source.id == state.activeSource?.id,
                        onClick = { actions.onSelectSource(source.id) },
                    )
                }
            }
        }

        search.openResult?.let { result ->
            Row(
                Modifier.fillMaxWidth().noRippleClickable(actions.onBackToResults),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    AppIcons.ChevronLeft,
                    contentDescription = "返回搜索结果",
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(11.dp),
                )
                Text(
                    result.title,
                    style = sc(11.5f, 600),
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        when {
            search.running -> Box(
                Modifier.fillMaxWidth().padding(vertical = 30.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    Modifier.size(22.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            }

            search.error != null -> PanelNote(search.error, Brand.Danger)

            search.openResult != null -> if (search.episodes.isEmpty()) {
                PanelNote("这个作品下没有可用的集", Color.White.copy(alpha = 0.5f))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(search.episodes, key = { it.episodeId }) { episode ->
                        SearchRow(
                            title = episode.title,
                            subtitle = null,
                            onClick = { actions.onPickEpisode(episode) },
                        )
                    }
                }
            }

            search.results.isNotEmpty() -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(search.results, key = { it.animeId }) { result ->
                    SearchRow(
                        title = result.title,
                        subtitle = result.subtitle.takeIf { it.isNotBlank() },
                        onClick = { actions.onOpenResult(result) },
                    )
                }
            }

            search.searched -> PanelNote("没有搜到这个名字", Color.White.copy(alpha = 0.5f))

            search.recent.isNotEmpty() -> Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "最近搜索",
                    style = mr(10f, 600),
                    color = Color.White.copy(alpha = 0.42f),
                    modifier = Modifier.padding(vertical = 2.dp),
                )
                search.recent.forEach { keyword ->
                    SearchRow(
                        title = keyword,
                        subtitle = null,
                        onClick = {
                            actions.onQueryChange(keyword)
                            actions.onSubmitSearch()
                        },
                    )
                }
            }

            else -> PanelNote(
                "输入片名后搜索，选中的集会记住，下次进入这一集直接用。",
                Color.White.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
internal fun DanmakuSendDialog(
    sending: Boolean,
    error: String?,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "发送弹幕",
            subtitle = "发到当前匹配的那一集，出现在此刻的位置。",
            onClose = onDismiss,
        )
        SearchField(
            value = draft,
            onValueChange = { draft = it.take(120) },
            onSubmit = { if (draft.isNotBlank()) onSend(draft) },
            autoFocus = true,
            placeholder = "说点什么",
            action = null,
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error, style = mr(10.5f, 500), color = Brand.Danger)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OverlayButton("取消", onDismiss, Modifier.weight(1f))
            OverlayButton(
                label = if (sending) "发送中…" else "发送",
                onClick = { onSend(draft) },
                modifier = Modifier.weight(1f),
                tone = OverlayButtonTone.Primary,
                enabled = draft.isNotBlank() && !sending,
            )
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    autoFocus: Boolean = false,
    placeholder: String = "片名",
    action: ImageVector? = AppIcons.Search,
) {
    val focusRequester = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .glass(
                shape = continuousRounded(12.dp),
                fill = Color.White.copy(alpha = 0.08f),
                border = Color.White.copy(alpha = 0.20f),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    style = mr(12f, 500),
                    color = Color.White.copy(alpha = 0.38f),
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = mr(12f, 500).copy(color = Color.White),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
        action?.let { icon ->
            Icon(
                icon,
                contentDescription = "搜索",
                tint = Color.White,
                modifier = Modifier.size(16.dp).noRippleClickable(onSubmit),
            )
        }
    }
}

@Composable
private fun SourceChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = sc(11f, if (active) 700 else 500),
        color = if (active) Color.White else Color.White.copy(alpha = 0.62f),
        maxLines = 1,
        modifier = Modifier
            .glass(
                shape = CircleShape,
                fill = if (active) Color.White.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.06f),
                border = Color.White.copy(alpha = if (active) 0.30f else 0.12f),
            )
            .noRippleClickable(onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun SearchRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .glass(
                shape = GlassShapes.thumb,
                fill = Color.White.copy(alpha = 0.06f),
                border = Color.White.copy(alpha = 0.12f),
            )
            .noRippleClickable(onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text(
            title,
            style = sc(11.5f, 600),
            color = Color.White.copy(alpha = 0.92f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        subtitle?.let {
            Spacer(Modifier.height(3.dp))
            Text(it, style = mr(10f, 400), color = Color.White.copy(alpha = 0.5f), maxLines = 1)
        }
    }
}

@Composable
private fun PanelNote(text: String, color: Color) {
    Text(
        text,
        style = mr(10.5f, 500),
        color = color,
        modifier = Modifier.padding(horizontal = 2.dp, vertical = 8.dp),
    )
}
