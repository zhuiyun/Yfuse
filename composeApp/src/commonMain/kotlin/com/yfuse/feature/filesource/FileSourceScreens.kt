package com.yfuse.feature.filesource

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.DecorativeTints
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassLift
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.MinTouchTarget
import com.yfuse.core.designsystem.OrbProgress
import com.yfuse.core.designsystem.OverlayActionRow
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.filesource.FileSource
import com.yfuse.core.filesource.FileSourceKind
import com.yfuse.core.network.LocalNetworkPermissionRequiredException
import com.yfuse.core.network.localNetworkConnectionsRestricted
import com.yfuse.core.network.localNetworkPermissionGranted
import com.yfuse.core.network.rememberLocalNetworkPermissionRequest
import com.yfuse.feature.servers.serverUsesLocalNetwork
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * 文件来源 in the 服务器 tab, under the servers: a card per share and the way to add one.
 *
 * One full-width item in the servers grid rather than cards of the grid's own. A share has no
 * health, latency or counts to compare side by side — it is a place to go — so it reads as a row
 * of places, not as more servers.
 */
@Composable
internal fun FileSourcesSection(controller: FileSourcesController) {
    val sources by controller.sources.collectAsState()
    val scanning by controller.scans.progress.collectAsState()
    val libraries by controller.scans.libraries.collectAsState()
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val connect = rememberFileSourceConnection(controller::showNotice)
    var actionsFor by remember { mutableStateOf<FileSource?>(null) }
    var confirmRemove by remember { mutableStateOf<FileSource?>(null) }

    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "文件来源",
                style = AppTypography.section.strong,
                color = palette.text,
                modifier = Modifier.weight(1f),
            )
            if (sources.isNotEmpty()) {
                Text(
                    "添加",
                    style = AppTypography.caption.strong,
                    color = accent.accent,
                    modifier =
                        Modifier
                            .pressable(onClickLabel = "添加文件来源", onClick = controller::openAdd)
                            .touchTarget()
                            .glass(AppShapes.thumb, palette.card2, palette.border)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        if (sources.isEmpty()) {
            EmptyFileSources(onAdd = controller::openAdd)
        } else {
            sources.forEach { source ->
                FileSourceCard(
                    source = source,
                    status = libraryStatus(scanning[source.id], libraries[source.id]),
                    scanning = source.id in scanning,
                    onOpen = { connect(source.origin) { controller.open(source) } },
                    onMore = { actionsFor = source },
                )
            }
        }
    }

    actionsFor?.let { source ->
        FileSourceActionsDialog(
            source = source,
            scanning = source.id in scanning,
            scanned = source.id in libraries,
            onBrowse = {
                actionsFor = null
                connect(source.origin) { controller.open(source) }
            },
            onScan = {
                actionsFor = null
                if (source.id in scanning) {
                    controller.scans.cancel(source.id)
                } else {
                    connect(source.origin) { controller.scans.scan(source) }
                }
            },
            onEdit = {
                actionsFor = null
                controller.openEdit(source)
            },
            onRemove = {
                actionsFor = null
                confirmRemove = source
            },
            onDismiss = { actionsFor = null },
        )
    }

    confirmRemove?.let { source ->
        ConfirmDialog(
            title = "移除文件来源",
            message = "将移除「${source.name}」和它在本机的观看进度。共享里的文件不受影响，之后可以重新添加。",
            confirmLabel = "移除",
            destructive = true,
            onConfirm = {
                confirmRemove = null
                controller.remove(source)
            },
            onDismiss = { confirmRemove = null },
        )
    }
}

/** One share: what kind it is, what it is called, where it lives, and what its 片库 holds. */
@Composable
private fun FileSourceCard(
    source: FileSource,
    status: String?,
    scanning: Boolean,
    onOpen: () -> Unit,
    onMore: () -> Unit,
) {
    val palette = LocalPalette.current
    val tint = source.kind.tint()
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(
                onClickLabel = "浏览${source.name}",
                onLongClick = onMore,
                onLongClickLabel = "打开${source.name}操作",
                onClick = onOpen,
            ).shadow(GlassLift.control, AppShapes.card)
            .liquidGlass(shape = AppShapes.card, fill = palette.card, border = palette.border, sheen = 0.52f)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(AppShapes.thumb)
                .background(lerp(palette.card2, tint, if (palette.isDark) 0.34f else 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Folder, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                source.name,
                style = AppTypography.body.strong,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                source.subtitle(),
                style = AppTypography.caption.regular,
                color = palette.sub2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            status?.let { line ->
                Row(
                    Modifier.padding(top = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (scanning) OrbProgress(size = 11.dp, contentDescription = null)
                    Text(
                        line,
                        style = AppTypography.caption.medium,
                        color = if (scanning) LocalAccentColors.current.accent else palette.hint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // The same corner key the server cards carry, for the same reason: a place to press that
        // is always in the same spot, apart from the row's own tap.
        Box(
            Modifier
                .pressable(label = "打开${source.name}操作", onClick = onMore)
                .touchTarget(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .liquidGlass(shape = CircleShape, fill = palette.card2, border = palette.border, sheen = 0.9f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.More, contentDescription = null, tint = palette.sub2, modifier = Modifier.size(13.dp))
            }
        }
    }
}

@Composable
private fun EmptyFileSources(onAdd: () -> Unit) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .glass(AppShapes.card, palette.card2, palette.border)
            .padding(horizontal = 18.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(AppIcons.Folder, null, tint = palette.sub2, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(10.dp))
        Text("浏览 NAS 与网盘里的视频", style = AppTypography.body.strong, color = palette.text)
        Spacer(Modifier.height(4.dp))
        Text(
            "WebDAV、SMB 共享，或经 Alist / OpenList 接入的网盘",
            style = AppTypography.caption.regular,
            color = palette.sub2,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "添加文件来源",
            style = AppTypography.body.strong,
            color = accent.onAccent,
            modifier =
                Modifier
                    .pressable(onClick = onAdd)
                    .heightIn(min = MinTouchTarget)
                    .glass(AppShapes.chip, accent.accent, accent.border)
                    .padding(horizontal = 22.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun FileSourceActionsDialog(
    source: FileSource,
    scanning: Boolean,
    scanned: Boolean,
    onBrowse: () -> Unit,
    onScan: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    GlassDialog(onDismiss = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(AppShapes.thumb)
                    .background(source.kind.tint()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Folder, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    source.name,
                    style = AppTypography.section.strong,
                    color = palette.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    source.subtitle(),
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        OverlayActionRow(
            label = "浏览文件",
            description = "打开共享的文件夹，直接播放视频",
            onClick = overlayAction(onBrowse),
        )
        Spacer(Modifier.height(8.dp))
        OverlayActionRow(
            label =
                when {
                    scanning -> "停止刮削"
                    scanned -> "重新刮削"
                    else -> "刮削到片库"
                },
            description =
                if (scanning) {
                    "这次读到的不会保存，片库保持上次的样子"
                } else {
                    "按文件名在 TMDB 识别电影与剧集，合并进「全部服务器」片库"
                },
            onClick = overlayAction(onScan),
        )
        Spacer(Modifier.height(8.dp))
        OverlayActionRow(
            label = "编辑连接与名称",
            description = "地址、账号或密码变了，在这里改",
            onClick = overlayAction(onEdit),
        )
        Spacer(Modifier.height(8.dp))
        OverlayActionRow(
            label = "移除文件来源",
            description = "本机的观看进度一并清除，共享里的文件不受影响",
            destructive = true,
            onClick = overlayAction(onRemove),
        )
    }
}

/**
 * Runs a connection to [origin] once the platform lets this app reach it.
 *
 * Android 17 guards every LAN connection behind 附近的设备, and a NAS is almost always on the
 * LAN. Asked for at the tap that needs it, as 添加服务器 does; refused, the share would only
 * time out, so the refusal is said instead.
 */
@Composable
internal fun rememberFileSourceConnection(onDenied: (String) -> Unit): (String, () -> Unit) -> Unit {
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val request =
        rememberLocalNetworkPermissionRequest(
            onGranted = {
                pending?.invoke()
                pending = null
            },
            onDenied = {
                pending = null
                LocalNetworkPermissionRequiredException().message?.let(onDenied)
            },
        )
    return { origin, action ->
        val lan = serverUsesLocalNetwork(origin.replaceFirst("smb://", "http://"))
        if (lan && localNetworkConnectionsRestricted() && !localNetworkPermissionGranted()) {
            if (pending == null) {
                pending = action
                request()
            }
        } else {
            action()
        }
    }
}

/** `WebDAV · nas.local:5006`; `/dav` is what every Alist has, so it is not repeated. */
internal fun FileSource.subtitle(): String {
    val root = rootSegments.filterNot { kind == FileSourceKind.Alist && it == "dav" }
    return listOfNotNull(kind.label, hostLabel + root.joinToString("") { "/$it" }).joinToString(" · ")
}

/** A colour per kind, so two shares of different kinds are told apart before their names are read. */
private fun FileSourceKind.tint(): Color =
    when (this) {
        FileSourceKind.WebDav -> DecorativeTints.ramp[0]
        FileSourceKind.Smb -> DecorativeTints.ramp[1]
        FileSourceKind.Alist -> DecorativeTints.ramp[4]
    }
