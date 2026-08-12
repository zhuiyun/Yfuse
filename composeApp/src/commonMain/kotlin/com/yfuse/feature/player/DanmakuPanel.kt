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
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DarkPalette
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.PlayerTokens
import com.yfuse.core.designsystem.glass

/**
 * Everything the 弹幕 tab and 搜索弹幕 sheet read, in one bundle.
 *
 * One parameter instead of the fourteen this used to add to [PlayerControls]. The player's
 * signature is long because the player genuinely has that many knobs, but 弹幕 is one
 * feature and it should cost one argument.
 */
data class DanmakuPanelState(
    val sources: List<DanmakuSource> = emptyList(),
    val activeSourceId: String? = null,
    val enabled: Boolean = false,
    val count: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
    /**
     * The episode the comments on screen came from — `九门(2026) - 第4集`.
     *
     * Shown because matching is a guess. Without it, 弹幕 from the wrong episode looks
     * exactly like 弹幕 from the right one that happens to be badly timed.
     */
    val matchLabel: String? = null,
    /** True when [matchLabel] is a hand-picked match rather than the server's guess. */
    val matchPinned: Boolean = false,
    val mergeDuplicates: Boolean = true,
    /** True when this source can be written to at all — a template cannot. */
    val canSend: Boolean = false,
    /** Non-null while a 发送 is in flight or has just failed. */
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

    /**
     * Whether 搜索弹幕 is worth offering — true as soon as *any* source can be searched,
     * not only the selected one. The sheet carries the source chips, so a template being
     * active is a reason to open it rather than a reason to hide the way in.
     */
    val searchable: Boolean get() = sources.any { it.supportsSearch }
}

/** The 搜索弹幕 sheet's contents: a keyword, its 作品 hits, and one of them opened. */
data class DanmakuSearchState(
    val query: String = "",
    val running: Boolean = false,
    val error: String? = null,
    val results: List<DanmakuSearchResult> = emptyList(),
    /** Non-null once a result is opened, which swaps the list for that 作品's 集. */
    val openResult: DanmakuSearchResult? = null,
    val episodes: List<DanmakuEpisode> = emptyList(),
    /** True after a search that came back with nothing, so "无结果" isn't shown before one. */
    val searched: Boolean = false,
    /** Newest first. Shown in place of the hint before anything has been typed. */
    val recent: List<String> = emptyList(),
)

/** Callbacks for [DanmakuPanelState], grouped for the same reason the state is. */
data class DanmakuPanelActions(
    val onToggle: () -> Unit = {},
    val onSelectArea: (Int) -> Unit = {},
    val onSelectFont: (Int) -> Unit = {},
    val onSelectSpeed: (Int) -> Unit = {},
    val onSelectOpacity: (Int) -> Unit = {},
    val onSelectSource: (String) -> Unit = {},
    /** Called as the sheet opens, so the keyword can be seeded from what is playing. */
    val onOpenSearch: () -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onSubmitSearch: () -> Unit = {},
    val onOpenResult: (DanmakuSearchResult) -> Unit = {},
    val onBackToResults: () -> Unit = {},
    val onPickEpisode: (DanmakuEpisode) -> Unit = {},
    /** Drops a hand-picked match and goes back to whatever the server matches on its own. */
    val onClearMatch: () -> Unit = {},
    val onToggleMerge: () -> Unit = {},
    /** Re-runs the load that failed, without changing anything about the match. */
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

/** The 弹幕 tab of the settings panel. */
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
        style = AppTypography.caption.medium,
        color = if (state.error != null) DarkPalette.error else Color.White.copy(alpha = 0.56f),
        modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp),
    )
    // A failed load used to be a red line and nothing else: the only way to try again was
    // to leave the episode and come back. Most of these failures are a timeout.
    if (state.error != null && !state.loading) {
        OptionRow("重试", selected = false, onClick = actions.onRetry)
    }
    // What the comments are actually from. A wrong match is the single most common thing
    // to go wrong here and the only way to see it is to print it.
    state.matchLabel?.let { label ->
        Text(
            label,
            style = AppTypography.caption.medium,
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
            // 取消匹配 only means something once a hand-picked one is in force; the
            // server's own guess is not something there is a way to un-choose.
            actionLabel = if (state.matchPinned) "取消匹配" else null,
            onAction = actions.onClearMatch,
        )
    }

    if (state.canSend) {
        OptionRow("发送弹幕", selected = false, onClick = onOpenSend)
    }
    // The single most effective thing that can be done to fourteen thousand comments.
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

/**
 * 搜索弹幕 — a keyword, the 作品 that match it, and the 集 under the one you open.
 *
 * Automatic matching works from the filename, and filenames are not titles: a library that
 * calls a show 九门.2026.S01E04.2160p and a 弹幕 server that files it under 九门(2026) have
 * nothing in common for a matcher to work with. This is the manual override, and it is a
 * two-step list rather than a picker because the servers hold several copies of the same
 * title — a 2021 film and a 2026 series, one per platform — and only a person can say which
 * one is on screen.
 *
 * A right-edge sheet, like the episode drawer, and for the same reason: it is a list to
 * scroll while the film keeps playing behind it, not something to stop the world for.
 */
@Composable
internal fun DanmakuSearchPanel(
    state: DanmakuPanelState,
    actions: DanmakuPanelActions,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val search = state.search
    // Same drawer as 设置 and 房间聊天 — see [PlayerSidePanel]. Dimmed, because this one
    // is typed into rather than glanced at.
    PlayerSidePanel(
        onDismiss = onDismiss,
        modifier = modifier,
        dim = true,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("搜索弹幕", style = AppTypography.body.strong, color = Color.White)
            Icon(
                AppIcons.Close,
                contentDescription = "关闭",
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.noRippleClickable(onDismiss).size(12.dp),
            )
        }

        SearchField(
            value = search.query,
            onValueChange = actions.onQueryChange,
            onSubmit = actions.onSubmitSearch,
            // The sheet exists to be typed into; opening it and then having to tap the
            // field is a step with exactly one possible answer.
            autoFocus = true,
        )

        // Which server answers. Only drawn with a real choice to make — one source is not
        // a set of options, it is a fact.
        if (state.sources.size > 1) {
            // Chips overflow once there are four or five sources; slide rather than clip.
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
                    style = AppTypography.caption.medium,
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

            search.error != null -> PanelNote(search.error, DarkPalette.error)

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
                // The same show, next episode, tomorrow night. Retyping a title on a
                // landscape keyboard is the sort of chore a list of eight strings removes.
                Text(
                    "最近搜索",
                    style = AppTypography.caption.medium,
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

/**
 * 发送弹幕 — one line, onto the episode the player is matched to.
 *
 * A dialog rather than a bar along the bottom of the picture: sending is occasional, the
 * bar would be permanent, and the player already treats a modal question this way (一起看
 * asks for a room code the same way). It closes on send, because the sent line appearing
 * over the picture is the confirmation.
 *
 * The position is taken at the moment 发送 is pressed rather than when the dialog opened —
 * the film has been playing the whole time it was being typed, and a comment that lands
 * twenty seconds early is a comment about the wrong shot.
 */
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
            Text(error, style = AppTypography.caption.medium, color = DarkPalette.error)
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
    /** The trailing icon. Null leaves the field bare, for a form with its own buttons. */
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
                shape = AppShapes.control,
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
                    style = AppTypography.body.medium,
                    color = Color.White.copy(alpha = 0.38f),
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = AppTypography.body.medium.copy(color = Color.White),
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
                modifier = Modifier.noRippleClickable(onSubmit).size(16.dp),
            )
        }
    }
}

@Composable
private fun SourceChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = if (active) AppTypography.caption.strong else AppTypography.caption.medium,
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
            style = AppTypography.caption.medium,
            color = Color.White.copy(alpha = 0.92f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        subtitle?.let {
            Spacer(Modifier.height(3.dp))
            Text(it, style = AppTypography.caption.regular, color = Color.White.copy(alpha = 0.5f), maxLines = 1)
        }
    }
}

@Composable
private fun PanelNote(text: String, color: Color) {
    Text(
        text,
        style = AppTypography.caption.medium,
        color = color,
        modifier = Modifier.padding(horizontal = 2.dp, vertical = 8.dp),
    )
}
