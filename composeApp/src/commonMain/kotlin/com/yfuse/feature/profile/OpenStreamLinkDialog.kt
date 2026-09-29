package com.yfuse.feature.profile

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButtonRow
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.YfFormField
import com.yfuse.feature.player.ExternalStreamUrl
import com.yfuse.feature.player.MAX_EXTERNAL_STREAM_URL_CHARS
import com.yfuse.feature.player.parseExternalStreamUrl
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * 打开链接 — plays an http(s) address that belongs to no library.
 *
 * The address is checked as it is typed and handed on trimmed but otherwise as given. What plays
 * it is an entry with no server behind it (see `externalPlaybackItem`), so no account header or
 * token goes with the request, and nothing of the address is written to the diagnostic log.
 */
@Composable
internal fun OpenStreamLinkDialog(
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val verdict = remember(draft) { parseExternalStreamUrl(draft) }
    val rejection = (verdict as? ExternalStreamUrl.Rejected)?.reason?.takeIf { draft.isNotBlank() }
    val palette = LocalPalette.current

    // A form: a flick that lands a little too fast must not throw a long pasted address away.
    GlassDialog(onDismiss = onDismiss, dragToDismiss = false) {
        OverlayHeader(
            title = "打开链接",
            subtitle = "粘贴 http:// 或 https:// 视频地址，直接用播放器打开。",
            onClose = onDismiss,
        )
        YfFormField(
            value = draft,
            onValueChange = { value ->
                // One character over the limit is kept, so an over-long paste is refused with a
                // reason rather than silently cut into a different address.
                draft =
                    value
                        .replace("\r", "")
                        .replace("\n", "")
                        .take(MAX_EXTERNAL_STREAM_URL_CHARS + 1)
            },
            label = "视频链接",
            placeholder = "https://…/video.m3u8",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            rejection ?: "只向这个地址本身取流，不附带任何服务器账号或令牌。",
            style = AppTypography.caption.regular,
            color = if (rejection != null) palette.error else palette.sub2,
        )
        OverlayButtonRow(
            dismissLabel = "取消",
            confirmLabel = "播放",
            onDismiss = onDismiss,
            onConfirm = { (verdict as? ExternalStreamUrl.Accepted)?.let { onOpen(it.url) } },
            confirmEnabled = verdict is ExternalStreamUrl.Accepted,
        )
    }
}
