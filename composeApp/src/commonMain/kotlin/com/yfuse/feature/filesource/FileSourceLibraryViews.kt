package com.yfuse.feature.filesource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.CrossServerMediaHit
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.CaptionedPoster
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OrbProgress
import com.yfuse.core.designsystem.OverlayActionRow
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.liveStatus
import com.yfuse.core.filesource.FileSourceProgress
import com.yfuse.core.filesource.FileSourceTitle
import com.yfuse.core.filesource.fileBaseName
import com.yfuse.core.network.TmdbImages
import com.yfuse.feature.player.PlayerLauncher
import com.yfuse.feature.player.formatTime
import org.koin.core.context.GlobalContext
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * The shelf for this screen, or null where 文件来源 does not exist — a build or a profile without
 * it. A child profile is given none: its servers were chosen for it, and a share was not.
 */
@Composable
fun rememberFileSourceLibraryShelf(allowed: Boolean): FileSourceLibraryShelf? {
    val shelf =
        remember {
            runCatching {
                val koin = GlobalContext.get()
                FileSourceLibraryShelf(koin.get(), koin.get(), koin.get(), koin.get())
            }.getOrNull()
        }
    return shelf?.takeIf { allowed }
}

@Composable
fun rememberFileSourceTitlePlayer(shelf: FileSourceLibraryShelf): FileSourceTitlePlayer {
    val scope = rememberCoroutineScope()
    return remember(shelf) { FileSourceTitlePlayer(shelf, scope) }
}

/** A share's copy of a title on 全部服务器's grid: TMDB's poster, since no server has one for it. */
@Composable
fun FileSourcePosterCard(
    title: FileSourceTitle?,
    hit: CrossServerMediaHit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CaptionedPoster(
        url = TmdbImages.poster(title?.posterPath, POSTER_WIDTH),
        fallbackUrls = listOfNotNull(TmdbImages.media(title?.posterPath, POSTER_WIDTH)),
        title = hit.item.title,
        year = hit.item.year?.toString(),
        rating = hit.item.communityRating,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * A title's files on every share that holds it: one row for a film, a row per episode for a
 * show, each saying how far it was watched here. Tapping one plays it with the rest of its folder
 * as the queue, exactly as it would from the share's own browser.
 */
@Composable
fun FileSourceTitleSheet(
    shelf: FileSourceLibraryShelf,
    player: FileSourceTitlePlayer,
    copies: List<CrossServerMediaHit>,
    onDismiss: () -> Unit,
) {
    val progress by shelf.progress.collectAsState()
    val preparing by player.preparing.collectAsState()
    val notice by player.notice.collectAsState()
    val connect = rememberFileSourceConnection(player::showNotice)
    val first = copies.firstOrNull() ?: return
    val title = shelf.title(first)
    val files = remember(copies) { shelf.files(copies) }
    val palette = LocalPalette.current
    // Held open while a file is prepared: the spinner is on its row, and a share that will not
    // answer says so here, where the tap was, rather than after the sheet has gone.
    GlassDialog(onDismiss = {
        player.dismissNotice()
        onDismiss()
    }) {
        OverlayHeader(
            title = first.item.title,
            subtitle =
                listOfNotNull(
                    title?.year?.toString(),
                    if (title?.isSeries == true) "剧集" else "电影",
                    copies.joinToString("、") { it.serverName },
                ).joinToString(" · "),
            onClose = {
                player.dismissNotice()
                onDismiss()
            },
        )
        notice?.let { message ->
            Text(
                message,
                style = AppTypography.caption.medium,
                color = palette.error,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).liveStatus(assertive = true),
            )
        }
        if (files.isEmpty()) {
            Text("这部作品的文件已不在片库中，请重新刮削", style = AppTypography.caption.regular, color = palette.sub2)
        }
        files.take(MAX_ROWS).forEachIndexed { index, file ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            val record = progress[file.itemId]
            OverlayActionRow(
                label = file.label(title?.isSeries == true),
                description = file.facts(record, copies.size > 1),
                onClick = {
                    player.dismissNotice()
                    connect(file.source.origin) { player.play(file) }
                },
                leadingContent =
                    if (preparing == file) {
                        { OrbProgress(size = 16.dp, contentDescription = "正在准备播放") }
                    } else {
                        null
                    },
            )
        }
        if (files.size > MAX_ROWS) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
                Text(
                    "只列出前 $MAX_ROWS 个文件，其余请在「服务器」里浏览文件来源",
                    style = AppTypography.caption.regular,
                    color = palette.hint,
                )
            }
        }
    }
}

/** Hands a prepared queue to the player; [onLaunched] lets the screen close what asked for it. */
@Composable
fun FileSourceTitlePlayerHost(
    player: FileSourceTitlePlayer,
    onLaunched: () -> Unit,
) {
    val launch by player.launch.collectAsState()
    launch?.let { prepared ->
        PlayerLauncher(
            items = prepared.items,
            startIndex = prepared.startIndex,
            startPositionMs = prepared.startPositionMs,
            onLaunched = {
                player.consumeLaunch()
                onLaunched()
            },
        )
    }
}

/** `S2 · 第 5 集`, `第 12 集`, or the file's name when the scan could not number it. */
private fun FileSourceTitleFile.label(series: Boolean): String {
    val episode = file.episode
    return when {
        !series -> "播放"
        episode == null -> fileBaseName(file.path.last())
        file.season != null -> "S${file.season} · 第 $episode 集"
        else -> "第 $episode 集"
    }
}

/** `看到 45:12 · 4.3 GB · NAS`: how far, how big, and — when more than one share has it — where. */
private fun FileSourceTitleFile.facts(
    record: FileSourceProgress?,
    namedSource: Boolean,
): String =
    listOfNotNull(
        when {
            record?.watched == true && record.resumePositionMs == 0L -> "已看完"
            (record?.resumePositionMs ?: 0L) > 0L -> "看到 ${formatTime(record?.resumePositionMs ?: 0L)}"
            else -> null
        },
        file.sizeBytes?.let(::formatFileSize),
        source.name.takeIf { namedSource },
        fileBaseName(file.path.last()).takeIf { file.episode == null },
    ).joinToString(" · ").ifEmpty { fileBaseName(file.path.last()) }

private const val POSTER_WIDTH = "w342"
private const val MAX_ROWS = 300
