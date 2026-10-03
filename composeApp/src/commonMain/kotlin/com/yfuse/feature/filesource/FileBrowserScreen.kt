package com.yfuse.feature.filesource

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.app.systemNavigationContentInset
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.InlineLoadingContent
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OfficialNavDisplay
import com.yfuse.core.designsystem.OrbProgress
import com.yfuse.core.designsystem.OverlayActionRow
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.PageHint
import com.yfuse.core.designsystem.PageLoadingSkeleton
import com.yfuse.core.designsystem.ReportOverlayVisible
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.motionItem
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.filesource.FileSource
import com.yfuse.core.filesource.FileSourceProgress
import com.yfuse.feature.player.PlayerLauncher
import com.yfuse.feature.player.formatTime
import com.yfuse.feature.profile.SettingsBackButton
import com.yfuse.feature.profile.SettingsBackInset
import com.yfuse.feature.profile.SettingsHeaderTop
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * A 文件来源 being browsed: its folders as a stack, each pushed and popped like any other page,
 * and its videos, which play with the rest of their folder as the queue.
 *
 * Covers the dock while shown ([ReportOverlayVisible]), as the settings pages do: a folder a few
 * levels deep is somewhere the user went, not a tab they are on.
 */
@Composable
internal fun FileBrowserScreen(controller: FileSourcesController) {
    val state by controller.browser.collectAsState()
    val browser = state ?: return
    val progress by controller.progress.collectAsState()
    val launch by controller.launch.collectAsState()
    val palette = LocalPalette.current
    var menuFor by remember { mutableStateOf<FolderRow?>(null) }
    ReportOverlayVisible()
    StatusBarIconStyle(darkIcons = !palette.isDark)

    OfficialNavDisplay(
        backStack = (0..browser.path.size).map { depth -> browser.path.take(depth) },
        onBack = controller::navigateUp,
        contentKey = { path -> "folder:" + path.joinToString("/") },
        modifier = Modifier.fillMaxSize(),
    ) { path ->
        FolderPage(
            source = browser.source,
            path = path,
            listing = browser.folders[path],
            refreshing = path in browser.refreshing,
            preparing = browser.preparing.takeIf { path == browser.path },
            progress = progress,
            onBack = controller::navigateUp,
            onRefresh = controller::refresh,
            onRow = { row ->
                if (row.entry.directory) controller.openFolder(row.entry.name) else controller.play(row.entry)
            },
            onRowMenu = { row -> menuFor = row },
            onEditSource = { controller.openEdit(browser.source) },
        )
    }

    menuFor?.let { row ->
        VideoActionsDialog(
            row = row,
            progress = row.itemId?.let(progress::get),
            onPlay = { fromStart ->
                menuFor = null
                controller.play(row.entry, fromStart = fromStart)
            },
            onClearProgress = {
                menuFor = null
                row.itemId?.let(controller::clearProgress)
            },
            onMarkWatched = {
                menuFor = null
                row.itemId?.let(controller::markWatched)
            },
            onDismiss = { menuFor = null },
        )
    }

    // A failed launch leaves the queue set; the next tap replaces it and launches again.
    launch?.let { prepared ->
        PlayerLauncher(
            items = prepared.items,
            startIndex = prepared.startIndex,
            startPositionMs = prepared.startPositionMs,
            onLaunched = controller::consumeLaunch,
        )
    }
}

@Composable
private fun FolderPage(
    source: FileSource,
    path: List<String>,
    listing: FolderListing?,
    refreshing: Boolean,
    preparing: String?,
    progress: Map<String, FileSourceProgress>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRow: (FolderRow) -> Unit,
    onRowMenu: (FolderRow) -> Unit,
    onEditSource: () -> Unit,
) {
    val palette = LocalPalette.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding =
            PaddingValues(
                top = SettingsHeaderTop,
                bottom = systemNavigationContentInset(),
                start = Dimens.pageHorizontal,
                end = Dimens.pageHorizontal,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        motionItem(key = "header") {
            FolderHeader(
                title = path.lastOrNull() ?: source.name,
                subtitle = folderLocation(source, path),
                refreshing = refreshing,
                refreshEnabled = listing != FolderListing.Loading,
                onBack = onBack,
                onRefresh = onRefresh,
            )
        }
        when (listing) {
            null, FolderListing.Loading ->
                motionItem(key = "loading") { PageLoadingSkeleton(rows = 6, horizontalPadding = 0.dp) }
            is FolderListing.Failed ->
                motionItem(key = "failed") {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ErrorState(message = listing.message, onRetry = onRefresh, modifier = Modifier.fillMaxWidth())
                        // A refused login is fixed in the source, not by retrying the same one.
                        OverlayActionRow(
                            label = "编辑连接",
                            description = "地址、账号或密码不对时，在这里改",
                            onClick = onEditSource,
                        )
                    }
                }
            is FolderListing.Loaded ->
                if (listing.rows.isEmpty()) {
                    motionItem(key = "empty") {
                        PageHint("这个文件夹里没有视频，也没有子文件夹", modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    motionItem(key = "summary") {
                        Text(
                            listing.rows.summary(),
                            style = AppTypography.caption.medium,
                            color = palette.sub2,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
                        )
                    }
                    items(
                        listing.rows,
                        key = { "entry:${it.entry.name}" },
                        contentType = { if (it.entry.directory) "folder" else "video" },
                    ) { row ->
                        FolderRowItem(
                            row = row,
                            progress = row.itemId?.let(progress::get),
                            preparing = preparing == row.entry.name,
                            onClick = { onRow(row) },
                            onLongClick = if (row.entry.directory) null else ({ onRowMenu(row) }),
                        )
                    }
                }
        }
    }
}

@Composable
private fun FolderHeader(
    title: String,
    subtitle: String,
    refreshing: Boolean,
    refreshEnabled: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().offset(x = SettingsBackInset - Dimens.pageHorizontal).padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsBackButton(onBack)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(
                title,
                style = AppTypography.section.strong,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = AppTypography.caption.regular,
                color = palette.sub2,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
        }
        Box(
            Modifier
                .pressable(enabled = refreshEnabled, onClickLabel = "刷新文件夹", label = "刷新文件夹", onClick = onRefresh)
                .touchTarget()
                .liquidGlass(shape = AppShapes.control, fill = palette.card2, border = null, over = palette.background)
                .size(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            InlineLoadingContent(loading = refreshing, slotSize = 20.dp, orbSize = 18.dp, color = palette.sub2) {
                Icon(AppIcons.Refresh, contentDescription = null, tint = palette.text, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * A folder or a video. A video says what it is and what is known of it without a request per
 * row: its format and size from the listing, its sidecars from the names beside it, and how far
 * it was watched from this device's own record.
 */
@Composable
private fun FolderRowItem(
    row: FolderRow,
    progress: FileSourceProgress?,
    preparing: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val entry = row.entry
    val resumeFraction = progress?.fraction
    val watched = progress?.watched == true && resumeFraction == null
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(
                onClickLabel = if (entry.directory) "打开${entry.name}" else "播放${entry.name}",
                onLongClick = onLongClick,
                onLongClickLabel = onLongClick?.let { "更多操作" },
                onClick = onClick,
            ).liquidGlass(shape = AppShapes.chip, fill = palette.card2, border = palette.border, sheen = 0.62f)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(AppShapes.thumb)
                .background(if (entry.directory) accent.container else palette.card3),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (entry.directory) AppIcons.Folder else AppIcons.Movie,
                contentDescription = null,
                tint = if (entry.directory) accent.accent else palette.sub,
                modifier = Modifier.size(16.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = AppTypography.body.medium,
                color = palette.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.directory) {
                Spacer(Modifier.height(2.dp))
                Text(
                    videoFacts(row, progress, watched),
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (resumeFraction != null) {
                    Spacer(Modifier.height(6.dp))
                    ResumeBar(resumeFraction)
                }
            }
        }
        when {
            preparing -> OrbProgress(size = 16.dp, contentDescription = "正在准备播放")
            entry.directory -> Icon(AppIcons.ChevronRight, null, tint = palette.sub2, modifier = Modifier.size(13.dp))
            watched ->
                Icon(
                    AppIcons.Check,
                    contentDescription = "已看完",
                    tint = accent.accent,
                    modifier = Modifier.size(14.dp),
                )
        }
    }
}

/** How far a video was watched; a still bar, so it asks nothing of 减少动画 or 静息. */
@Composable
private fun ResumeBar(fraction: Float) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(AppShapes.chip)
            .background(palette.border),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(MIN_BAR_FRACTION, 1f))
                .background(accent.accent),
        )
    }
}

@Composable
private fun VideoActionsDialog(
    row: FolderRow,
    progress: FileSourceProgress?,
    onPlay: (fromStart: Boolean) -> Unit,
    onClearProgress: () -> Unit,
    onMarkWatched: () -> Unit,
    onDismiss: () -> Unit,
) {
    val resume = progress?.resumePositionMs?.takeIf { it > 0L }
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = row.entry.name,
            subtitle = videoFacts(row, progress, watched = false),
            onClose = onDismiss,
        )
        OverlayActionRow(
            label = if (resume != null) "从 ${formatTime(resume)} 继续播放" else "播放",
            description = "同一文件夹里的其他视频接着播",
            onClick = overlayAction { onPlay(false) },
        )
        if (resume != null) {
            Spacer(Modifier.height(8.dp))
            OverlayActionRow(
                label = "从头播放",
                description = "保留现在的进度，直到这次看得更远",
                onClick = overlayAction { onPlay(true) },
            )
        }
        // Half-watched offers both: finished elsewhere, or not worth going back to.
        if (progress?.watched != true) {
            Spacer(Modifier.height(8.dp))
            OverlayActionRow(
                label = "标为已看完",
                description = "只记在这台设备上",
                onClick = overlayAction(onMarkWatched),
            )
        }
        if (progress != null) {
            Spacer(Modifier.height(8.dp))
            OverlayActionRow(
                label = "标为未看",
                description = "清除本机记录的进度和已看完标记",
                onClick = overlayAction(onClearProgress),
            )
        }
    }
}

/** `MKV · 58.3 GB · 字幕 2 · 看到 45:12`. */
private fun videoFacts(
    row: FolderRow,
    progress: FileSourceProgress?,
    watched: Boolean,
): String =
    listOfNotNull(
        row.entry.extension
            .uppercase()
            .takeIf(String::isNotEmpty),
        row.entry.sizeBytes?.let(::formatFileSize),
        "字幕 ${row.subtitleCount}".takeIf { row.subtitleCount > 0 },
        when {
            watched -> "已看完"
            (progress?.resumePositionMs ?: 0L) > 0L -> "看到 ${formatTime(progress?.resumePositionMs ?: 0L)}"
            else -> null
        },
    ).joinToString(" · ")

/** `3 个文件夹 · 12 个视频`. */
private fun List<FolderRow>.summary(): String {
    val folders = count { it.entry.directory }
    val videos = size - folders
    return listOfNotNull(
        "$folders 个文件夹".takeIf { folders > 0 },
        "$videos 个视频".takeIf { videos > 0 },
    ).joinToString(" · ")
}

/** Where a folder is: the source, then the folders above this one. */
private fun folderLocation(
    source: FileSource,
    path: List<String>,
): String =
    if (path.isEmpty()) {
        source.subtitle()
    } else {
        (listOf(source.name) + path.dropLast(1)).joinToString(" / ")
    }

/** A bar that started is never drawn empty, or a minute in would look like nothing. */
private const val MIN_BAR_FRACTION = 0.03f
