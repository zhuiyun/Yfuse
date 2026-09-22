package com.yfuse.feature.player

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.PlaybackBookmark
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ThemeText as Text

data class PlaybackBookmarkPanelState(
    val mediaIdentity: String = "",
    val items: List<PlaybackBookmark> = emptyList(),
    val available: Boolean = false,
    val error: String? = null,
)

data class PlaybackBookmarkActions(
    val onSave: (String, String) -> Unit = { _, _ -> },
    val onDelete: (Long) -> Unit = {},
    val onSeek: (Long) -> Unit = {},
)

@Composable
internal fun PlaybackBookmarkPanel(
    state: PlaybackBookmarkPanelState,
    actions: PlaybackBookmarkActions,
) {
    var title by remember(state.mediaIdentity) { mutableStateOf("") }
    var note by remember(state.mediaIdentity) { mutableStateOf("") }
    GroupLabel("标记当前时间")
    Text("名称", color = Color.White, style = AppTypography.caption.medium)
    BasicTextField(
        value = title,
        onValueChange = { title = it.take(80) },
        singleLine = true,
        textStyle = TextStyle(color = Color.White),
        cursorBrush = SolidColor(Color.White),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(vertical = 10.dp)
                .semantics { contentDescription = "书签名称" },
    )
    Text("备注（可选）", color = Color.White, style = AppTypography.caption.medium)
    BasicTextField(
        value = note,
        onValueChange = { note = it.take(240) },
        maxLines = 3,
        textStyle = TextStyle(color = Color.White),
        cursorBrush = SolidColor(Color.White),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(vertical = 10.dp)
                .semantics { contentDescription = "书签备注" },
    )
    if (state.available) {
        OptionRow("保存当前播放时间", selected = false, onClick = { actions.onSave(title, note) })
    }
    state.error?.let { Text(it, color = Color.White, style = AppTypography.caption.medium) }
    GroupLabel("本片书签")
    if (state.items.isEmpty()) Text("还没有时间书签", color = Color.White.copy(alpha = 0.6f))
    state.items.sortedBy { it.positionMs }.forEach { bookmark ->
        OptionRow(
            "${formatTime(bookmark.positionMs)} · ${bookmark.title}",
            selected = false,
            onClick = { actions.onSeek(bookmark.positionMs) },
        )
        if (bookmark.note.isNotBlank()) Text(bookmark.note, color = Color.White.copy(alpha = 0.7f))
        OptionRow("删除此书签", selected = false, onClick = { actions.onDelete(bookmark.id) })
        PopupDivider()
    }
}
