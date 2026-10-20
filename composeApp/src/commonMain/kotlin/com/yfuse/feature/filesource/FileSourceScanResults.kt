package com.yfuse.feature.filesource

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.CrossServerMediaHit
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.OverlayActionRow
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.PageHint
import com.yfuse.core.designsystem.motionItems
import com.yfuse.core.filesource.FileSource

/** The saved scan, reached directly from its source; a running scan never replaces this snapshot. */
@Composable
internal fun FileSourceScanResults(
    source: FileSource,
    onDismiss: () -> Unit,
) {
    val shelf = rememberFileSourceLibraryShelf(allowed = true) ?: return
    val libraries by shelf.libraries.collectAsState()
    val progress by shelf.progress.collectAsState()
    val hits =
        remember(source, libraries, progress) {
            fileSourceLibraryHits(listOf(source), libraries, progress, null, false)
        }
    var selected by remember(source.id) { mutableStateOf<CrossServerMediaHit?>(null) }
    val player = rememberFileSourceTitlePlayer(shelf)
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader("${source.name} · 刮削结果", "已保存 ${hits.size} 部影片与剧集", onClose = onDismiss)
        if (hits.isEmpty()) PageHint("这次扫描没有识别出影片，可浏览原始文件或重新刮削。")
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            motionItems(hits, key = { it.item.id }) { hit ->
                OverlayActionRow(hit.item.title, onClick = { selected = hit })
            }
        }
    }
    selected?.let { hit ->
        FileSourceTitleSheet(shelf, player, listOf(hit), onDismiss = { selected = null })
    }
    FileSourceTitlePlayerHost(player, onLaunched = onDismiss)
}
