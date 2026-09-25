package com.yfuse.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.SmartPlaylist
import com.yfuse.core.data.SmartPlaylistStore
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DialogPresence
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayActionRow
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget
import org.koin.core.context.GlobalContext
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
fun SmartPlaylistShelf(
    state: SearchState? = null,
    onApply: ((SmartPlaylist) -> Unit)? = null,
) {
    val store = remember { GlobalContext.get().get<SmartPlaylistStore>() }
    val requests = remember { GlobalContext.get().get<SearchRequests>() }
    val saved by store.items.collectAsState()
    val error by store.error.collectAsState()
    val rules = if (state == null) saved.filter { it.pinned } else saved
    val palette = LocalPalette.current
    var editing by remember { mutableStateOf<SmartPlaylist?>(null) }
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    fun apply(rule: SmartPlaylist) {
        if (onApply != null) onApply(rule) else requests.openPlaylist(rule)
    }
    if (rules.isEmpty() && state == null) return
    Column {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rules, key = { it.name }) { rule ->
                Text(
                    rule.name,
                    style = AppTypography.caption.strong,
                    color = palette.text,
                    modifier =
                        Modifier
                            .pressable(onClick = { editing = rule })
                            .touchTarget()
                            .liquidGlass(
                                shape = AppShapes.chip,
                                fill = palette.card2,
                                border = palette.border,
                                over = palette.background,
                            ).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            if (state != null) {
                item {
                    Text(
                        "＋ 保存筛选",
                        style = AppTypography.caption.strong,
                        color = palette.text,
                        modifier =
                            Modifier
                                .pressable(onClick = {
                                    name = state.playlistName ?: state.query.take(40)
                                    naming =
                                        true
                                })
                                .touchTarget()
                                .padding(8.dp),
                    )
                }
            }
        }
        if (naming && state != null) {
            GlassDialog(onDismiss = { naming = false }) {
                OverlayHeader("保存智能片单", "保存当前关键词、服务器及筛选条件，每次打开重新查询")
                BasicTextField(
                    name,
                    { name = it.take(40) },
                    textStyle = AppTypography.body.medium.copy(color = palette.text),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
                OverlayOptionRow("保存并固定到首页", false, {
                    if (store.save(state.asPlaylist(name))) naming = false
                })
                error?.let { Text(it, style = AppTypography.caption.regular, color = palette.error) }
            }
        }
        // Pinning and deleting close the sheet only once the store has taken the change; held in a
        // presence, it still leaves the way it came instead of vanishing in a frame.
        DialogPresence(editing) { rule ->
            GlassDialog(onDismiss = { editing = null }) {
                OverlayHeader(rule.name, "按保存的条件获取最新结果")
                OverlayActionRow(
                    "打开放映片单",
                    overlayAction {
                        editing = null
                        apply(rule)
                    },
                )
                OverlayOptionRow(if (rule.pinned) "从首页取消固定" else "固定到首页", rule.pinned, {
                    if (store.save(rule.copy(pinned = !rule.pinned))) editing = null
                })
                OverlayActionRow(
                    "删除片单规则",
                    { if (store.remove(rule.name)) editing = null },
                    destructive = true,
                    description = "仅删除规则，不删除服务器媒体",
                )
                error?.let { Text(it, style = AppTypography.caption.regular, color = palette.error) }
            }
        }
    }
}

internal fun SearchState.asPlaylist(name: String) =
    SmartPlaylist(
        name = name,
        query = query,
        serverId = serverId,
        libraryId = libraryId,
        type = type.name,
        year = year,
        genre = genre,
        watchStatus = watchStatus.name,
        sort = sort.name,
    )

@Composable
internal fun hasPinnedSmartPlaylists(): Boolean {
    val store = remember { GlobalContext.get().get<SmartPlaylistStore>() }
    val saved by store.items.collectAsState()
    return saved.any { it.pinned }
}
