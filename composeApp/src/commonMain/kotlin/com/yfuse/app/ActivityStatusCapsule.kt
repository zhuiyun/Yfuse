package com.yfuse.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.cast.CastManager
import com.yfuse.core.cast.CastPlaybackStatus
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayActionRow
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.liveStatus
import com.yfuse.core.designsystem.motionAwareAnimateContentSize
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.offline.DownloadStatus
import com.yfuse.core.offline.summarizeDownloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import com.yfuse.core.designsystem.ThemeText as Text

/** One compact entry for ongoing work. State comes from the owners; no polling or fake progress. */
@Composable
internal fun ActivityStatusCapsule(
    root: RootComponent,
    onRoomInfo: () -> Unit,
    visible: Boolean,
    enter: EnterTransition,
    exit: ExitTransition,
    modifier: Modifier = Modifier,
) {
    val offline = root.dependencies.offlineMediaManager
    val items by offline.items.collectAsState()
    val summary = remember(items) { summarizeDownloads(items) }
    val cast = remember { GlobalContext.get().get<CastManager>() }
    val castSummary =
        remember(cast) {
            cast.state
                .map {
                    CastCapsuleState(
                        it.hasActiveSession,
                        it.status,
                        it.activeDevice?.name,
                    )
                }.distinctUntilChanged()
        }
    val castState by castSummary.collectAsState(CastCapsuleState(false, CastPlaybackStatus.Idle, null))
    val roomSummary =
        remember(root) {
            root.dependencies.watchTogether.state
                .map {
                    RoomCapsuleState(
                        it.roomCode,
                        it.connecting,
                        it.reconnecting,
                        it.participantCount,
                        it.syncWarning,
                        it.error,
                    )
                }.distinctUntilChanged()
        }
    val watch by roomSummary.collectAsState(RoomCapsuleState())
    val casting = castState.hasActiveSession || castState.status == CastPlaybackStatus.Connecting
    val room = watch.roomCode != null || watch.connecting || watch.reconnecting
    // 状态胶囊瞬间展开: a result — a download done, a cast ended — held for a moment, then the
    // capsule folds back to whatever is still going on, or leaves.
    var flash by remember { mutableStateOf<String?>(null) }
    val lastStatuses = remember { mutableMapOf<String, DownloadStatus>() }
    LaunchedEffect(items) {
        completedDownloadFlash(lastStatuses.toMap(), items)?.let { flash = it }
        lastStatuses.clear()
        items.forEach { lastStatuses[it.id] = it.status }
    }
    val lastCast = remember { arrayOfNulls<String>(1) }
    val wasCasting = remember { booleanArrayOf(false) }
    if (casting) castState.deviceName?.let { lastCast[0] = it }
    LaunchedEffect(casting) {
        if (wasCasting[0] && !casting) flash = castEndedFlash(lastCast[0])
        wasCasting[0] = casting
    }
    LaunchedEffect(flash) {
        if (flash != null) {
            delay(CAPSULE_FLASH_MS)
            flash = null
        }
    }
    val shown = visible && (summary.visible || casting || room || flash != null)
    var expanded by remember { mutableStateOf(false) }
    var operationError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    val labels =
        listOfNotNull(
            if (casting) "投屏 · ${castState.deviceName ?: "连接中"}" else null,
            if (room) {
                if (watch.reconnecting) {
                    "一起看 · 重连中"
                } else {
                    "一起看 · ${watch.participantCount} 人"
                }
            } else {
                null
            },
            if (summary.visible) summary.title else null,
        )
    var lastLabel by remember { mutableStateOf("") }
    val flashing = flash
    if (flashing != null) {
        lastLabel = flashing
    } else if (labels.isNotEmpty()) {
        lastLabel = labels.joinToString("  ·  ")
    }
    AnimatedVisibility(visible = shown, modifier = modifier, enter = enter, exit = exit, label = "activityCapsule") {
        Column(
            Modifier
                .fillMaxWidth()
                .pressable(onClick = { expanded = true })
                .touchTarget()
                .liquidGlass(
                    shape = GlassShapes.chip,
                    fill = palette.glassStrong,
                    border = palette.border,
                    over = palette.background,
                ).motionAwareAnimateContentSize()
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            // Stable wording — 「一起看 · 重连中」, 「下载任务 · 3 项」 — so it is read out when it
            // changes; the ticking percentage lives in the dialog, not here.
            Text(
                lastLabel,
                style = AppTypography.caption.strong,
                color = if (flashing != null) LocalAccentColors.current.accent else palette.text,
                maxLines = 2,
                modifier = Modifier.liveStatus(),
            )
        }
    }
    // A window of its own: it must not depend on dock visibility.
    if (expanded) {
        GlassDialog(onDismiss = { expanded = false }) {
            OverlayHeader("正在进行", onClose = { expanded = false })
            if (summary.visible) {
                OverlayActionRow(
                    summary.title,
                    overlayAction {
                        expanded = false
                        root.openDownloads()
                    },
                    description = summary.detail,
                )
                if (summary.active > 0) OverlayActionRow("暂停下载", offline::pauseAll)
                if (summary.paused + summary.failed > 0) OverlayActionRow("继续 / 重试下载", offline::resumeAll)
            }
            if (casting) {
                OverlayActionRow(
                    if (castState.status == CastPlaybackStatus.Playing) "暂停投屏" else "继续投屏",
                    {
                        if (!busy) {
                            busy = true
                            scope.launch {
                                try {
                                    val accepted =
                                        if (cast.state.value.status ==
                                            CastPlaybackStatus.Playing
                                        ) {
                                            cast.pause()
                                        } else {
                                            cast.resume()
                                        }
                                    operationError = if (accepted) null else "投屏设备未确认操作，请重试"
                                } catch (
                                    cancelled: CancellationException,
                                ) {
                                    throw cancelled
                                } catch (
                                    _: Exception,
                                ) {
                                    operationError = "投屏操作失败，请检查设备连接后重试"
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    },
                    description = castState.deviceName,
                )
                OverlayActionRow("结束投屏", {
                    if (!busy) {
                        busy = true
                        scope.launch {
                            try {
                                operationError = if (cast.stop()) null else "投屏设备未确认停止，请重试"
                            } catch (
                                cancelled: CancellationException,
                            ) {
                                throw cancelled
                            } catch (
                                _: Exception,
                            ) {
                                operationError = "停止投屏失败，请检查设备连接后重试"
                            } finally {
                                busy = false
                            }
                        }
                    }
                })
            }
            if (room) {
                // The room's own dialog enters once this one has left, not over it.
                OverlayActionRow(
                    "一起看房间",
                    overlayAction {
                        expanded = false
                        onRoomInfo()
                    },
                    description =
                        watch.syncWarning ?: watch.error,
                )
                OverlayActionRow(
                    "返回一起看",
                    overlayAction {
                        expanded = false
                        root.enterWatchRoom()
                    },
                )
            }
            operationError?.let {
                Text(
                    it,
                    style = AppTypography.caption.regular,
                    color = palette.error,
                    modifier = Modifier.liveStatus(assertive = true),
                )
            }
        }
    }
}

private data class CastCapsuleState(
    val hasActiveSession: Boolean,
    val status: CastPlaybackStatus,
    val deviceName: String?,
)

private data class RoomCapsuleState(
    val roomCode: String? = null,
    val connecting: Boolean = false,
    val reconnecting: Boolean = false,
    val participantCount: Int = 0,
    val syncWarning: String? = null,
    val error: String? = null,
)
