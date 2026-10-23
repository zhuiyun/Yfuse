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
import com.yfuse.core.data.SmartShelfQuery
import com.yfuse.core.data.libraryShelf
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DialogPresence
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayActionRow
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget
import org.koin.core.context.GlobalContext
import com.yfuse.core.designsystem.ThemeText as Text

/** A search page keeps this outside its filter sheet, so dialog handoffs retain their state. */
class SmartPlaylistEditorState internal constructor() {
    internal var editing by mutableStateOf<SmartPlaylist?>(null)
    internal var naming by mutableStateOf<SearchState?>(null)
    internal var name by mutableStateOf("")

    internal fun beginNaming(state: SearchState) {
        name = state.playlistName ?: state.query.take(40)
        naming = state
    }
}

@Composable
fun SmartPlaylistShelf(
    state: SearchState? = null,
    onApply: ((SmartPlaylist) -> Unit)? = null,
    embedded: Boolean = false,
    editorState: SmartPlaylistEditorState? = null,
    onOpenEditor: (() -> Unit)? = null,
) {
    val store = remember { GlobalContext.get().get<SmartPlaylistStore>() }
    val saved by store.items.collectAsState()
    // Home shows single-library rules as poster shelves; the remaining pinned rules stay here.
    val rules = if (state == null) saved.filter { it.pinned && it.libraryShelf() == null } else saved
    val palette = LocalPalette.current
    val ownEditor = remember { SmartPlaylistEditorState() }
    val editor = editorState ?: ownEditor
    if (rules.isEmpty() && state == null) return
    Column {
        LazyRow(
            contentPadding = if (embedded) PaddingValues(0.dp) else PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (embedded && state != null) {
                item(key = "save-current-filter") {
                    SaveFilterAction(
                        onClick =
                            if (onOpenEditor != null) {
                                overlayAction {
                                    onOpenEditor()
                                    editor.beginNaming(state)
                                }
                            } else {
                                { editor.beginNaming(state) }
                            },
                    )
                }
            }
            items(rules, key = { "playlist:${it.name}" }) { rule ->
                val edit =
                    if (onOpenEditor != null) {
                        overlayAction {
                            onOpenEditor()
                            editor.editing = rule
                        }
                    } else {
                        { editor.editing = rule }
                    }
                Text(
                    rule.name,
                    style = AppTypography.caption.strong,
                    color = palette.text,
                    modifier =
                        Modifier
                            .pressable(onClick = edit)
                            .touchTarget()
                            .liquidGlass(
                                shape = AppShapes.chip,
                                fill = palette.card2,
                                border = palette.border,
                                over = palette.background,
                            ).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            if (state != null && !embedded) {
                item { SaveFilterAction { editor.beginNaming(state) } }
            }
        }
        if (editorState == null) SmartPlaylistDialogs(editor, onApply)
    }
}

@Composable
internal fun SmartPlaylistDialogs(
    editor: SmartPlaylistEditorState,
    onApply: ((SmartPlaylist) -> Unit)? = null,
) {
    val store = remember { GlobalContext.get().get<SmartPlaylistStore>() }
    val requests = remember { GlobalContext.get().get<SearchRequests>() }
    val error by store.error.collectAsState()
    DialogPresence(editor.naming) { draft ->
        GlassDialog(onDismiss = { editor.naming = null }) {
            val palette = LocalPalette.current
            OverlayHeader("保存智能片单", "保存当前关键词、服务器及筛选条件，每次打开重新查询")
            BasicTextField(
                editor.name,
                { editor.name = it.take(40) },
                textStyle = AppTypography.body.medium.copy(color = palette.text),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
            OverlayActionRow("保存并固定到首页", {
                if (store.save(draft.asPlaylist(editor.name))) editor.naming = null
            })
            error?.let { Text(it, style = AppTypography.caption.regular, color = palette.error) }
        }
    }
    DialogPresence(editor.editing) { rule ->
        GlassDialog(onDismiss = { editor.editing = null }) {
            val palette = LocalPalette.current
            OverlayHeader(rule.name, "按保存的条件获取最新结果")
            OverlayActionRow(
                "打开放映片单",
                overlayAction {
                    editor.editing = null
                    if (onApply != null) onApply(rule) else requests.openPlaylist(rule)
                },
            )
            OverlayOptionRow(if (rule.pinned) "从首页取消固定" else "固定到首页", rule.pinned, {
                if (store.save(rule.copy(pinned = !rule.pinned))) editor.editing = null
            })
            OverlayActionRow(
                "删除片单规则",
                { if (store.remove(rule.name)) editor.editing = null },
                destructive = true,
                description = "仅删除规则，不删除服务器媒体",
            )
            error?.let { Text(it, style = AppTypography.caption.regular, color = palette.error) }
        }
    }
}

@Composable
private fun SaveFilterAction(onClick: () -> Unit) {
    val palette = LocalPalette.current
    Text(
        "＋ 保存筛选",
        style = AppTypography.caption.strong,
        color = LocalAccentColors.current.accent,
        modifier =
            Modifier
                .pressable(onClickLabel = "将当前关键词和筛选保存为智能片单", onClick = onClick)
                .touchTarget()
                .glass(AppShapes.chip, palette.card2, palette.border)
                .padding(horizontal = 12.dp, vertical = 8.dp),
    )
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

/** Pinned rules 首页 shows as poster shelves, with the listing each stands for. */
@Composable
internal fun pinnedLibraryShelves(): List<Pair<SmartPlaylist, SmartShelfQuery>> {
    val store = remember { GlobalContext.get().get<SmartPlaylistStore>() }
    val saved by store.items.collectAsState()
    return remember(saved) {
        saved.filter { it.pinned }.mapNotNull { rule -> rule.libraryShelf()?.let { rule to it } }
    }
}

@Composable
internal fun hasPinnedSmartPlaylists(): Boolean {
    val store = remember { GlobalContext.get().get<SmartPlaylistStore>() }
    val saved by store.items.collectAsState()
    return saved.any { it.pinned }
}
