package com.yfuse.feature.player

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.DarkPalette
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.WatchAvatar
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberAccentColorsForSurface
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.sync.WatchControlMode
import com.yfuse.core.sync.WatchInvite
import com.yfuse.core.sync.WatchNetworkQuality
import com.yfuse.core.sync.WatchParticipant
import com.yfuse.feature.watch.CopyableRoomCode

/**
 * In-player watch-together control. Since the entry points moved to where people actually
 * decide what to watch — 详情页 for hosting, an invite link for joining — this is now the
 * recovery path: "we're already watching, pull someone in."
 *
 * The relay address is deliberately not asked for here. It's infrastructure with a working
 * default, it belongs in 「我的」's settings, and putting it in front of someone mid-film (as
 * a required field, with both buttons disabled until it validated) was the single biggest
 * obstacle in the old flow.
 */
@Composable
internal fun WatchTogetherDialog(
    endpoint: String,
    connecting: Boolean,
    connected: Boolean,
    roomCode: String?,
    isHost: Boolean,
    canControl: Boolean,
    controlMode: WatchControlMode,
    participantCount: Int,
    participants: List<WatchParticipant>,
    error: String?,
    controlRequested: Boolean,
    onCreate: (String) -> Unit,
    onJoin: (String, String) -> Unit,
    onLeave: () -> Unit,
    onRequestControl: () -> Unit,
    onSetControlMode: (WatchControlMode) -> Unit,
    onSetModerator: (String, Boolean) -> Unit,
    onKickParticipant: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var roomDraft by remember { mutableStateOf("") }
    var kickCandidate by remember { mutableStateOf<WatchParticipant?>(null) }
    val normalizedRoom = WatchInvite.normalizeCode(roomDraft)
    val palette = LocalPalette.current
    val accent = rememberAccentColorsForSurface(dark = true)

    GlassDialog(liquidButtons = false, onDismiss = onDismiss) {
        OverlayHeader(
            title = "一起看",
            subtitle =
                if (connected) {
                    "房主控制播放、暂停与进度，其他成员自动跟随。"
                } else {
                    "视频仍由每个人自己的媒体服务器播放，房间服务只同步状态。"
                },
            onClose = onDismiss,
        )
        if (connected) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .glass(AppShapes.card, palette.card2, palette.border)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                CopyableRoomCode(
                    roomCode = roomCode.orEmpty(),
                    style = AppTypography.display.strong,
                    color = accent.accent,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${if (isHost) {
                        "房主"
                    } else if (canControl) {
                        "可控制"
                    } else {
                        "成员"
                    }} · " +
                        "$participantCount 人在线 · ${participants.count { it.ready }} 人就绪",
                    style = AppTypography.caption.medium,
                    color = palette.sub2,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            if (isHost) {
                Text(
                    "控制权限",
                    style = AppTypography.caption.medium,
                    color = palette.sub2,
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                )
                SegmentedRow(
                    options = WatchControlMode.entries.map { it.label },
                    selectedIndex = WatchControlMode.entries.indexOf(controlMode),
                    onSelect = { onSetControlMode(WatchControlMode.entries[it]) },
                )
                if (controlMode == WatchControlMode.Moderators) {
                    Text(
                        "可在下方设置管理员或移出成员",
                        style = AppTypography.caption.medium,
                        color = palette.sub2,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            if (participants.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    participants.forEach { participant ->
                        Column(
                            Modifier
                                .width(108.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            WatchAvatar(participant.avatarId, 32.dp)
                            Text(
                                when {
                                    participant.isSelf -> "我"
                                    participant.isHost -> "${participant.name} · 房主"
                                    participant.isModerator -> "${participant.name} · 管理员"
                                    participant.canControl -> "${participant.name} · 可控制"
                                    else -> participant.name
                                },
                                style = AppTypography.caption.medium,
                                color = palette.sub2,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                participant.playbackStatusLabel,
                                style = AppTypography.caption.medium,
                                color =
                                    when {
                                        !participant.mediaAvailable -> DarkPalette.error
                                        participant.buffering -> Color(0xFFFFC857)
                                        participant.ready -> accent.accent
                                        else -> palette.sub2
                                    },
                                maxLines = 1,
                            )
                            Text(
                                participant.networkStatusLabel,
                                style = AppTypography.caption.medium,
                                color =
                                    when (participant.networkQuality) {
                                        WatchNetworkQuality.Excellent -> accent.accent
                                        WatchNetworkQuality.Fair -> Color(0xFFFFC857)
                                        WatchNetworkQuality.Poor -> DarkPalette.error
                                        WatchNetworkQuality.Unknown -> palette.sub2
                                    },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (isHost && !participant.isHost && !participant.isSelf) {
                                if (controlMode == WatchControlMode.Moderators) {
                                    Text(
                                        if (participant.isModerator) "取消管理员" else "设为管理员",
                                        style = AppTypography.caption.medium,
                                        color = accent.accent,
                                        modifier =
                                            Modifier
                                                .pressable {
                                                    onSetModerator(
                                                        participant.clientId,
                                                        !participant.isModerator,
                                                    )
                                                }.touchTarget(),
                                    )
                                }
                                Text(
                                    "移出房间",
                                    style = AppTypography.caption.medium,
                                    color = DarkPalette.error,
                                    modifier =
                                        Modifier
                                            .pressable {
                                                kickCandidate = participant
                                            }.touchTarget(),
                                )
                            }
                        }
                    }
                }
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = AppTypography.caption.medium, color = DarkPalette.error)
            }
            if (!canControl) {
                // Deliberately still enabled once asked: a host who never answers would
                // otherwise leave this pinned on "waiting" with no way to ask again.
                OverlayButton(
                    label = if (controlRequested) "再次请求控制权" else "请求控制权",
                    onClick = onRequestControl,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    tone = OverlayButtonTone.Primary,
                )
                if (controlRequested) {
                    Text(
                        "已发送请求，等待房主响应。",
                        style = AppTypography.caption.medium,
                        color = palette.sub2,
                        modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            OverlayButton(
                label = "退出房间",
                onClick = onLeave,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                tone = OverlayButtonTone.Destructive,
            )
        } else {
            WatchInput(
                value = normalizedRoom,
                placeholder = "输入 6 位房间码",
                onValueChange = { roomDraft = it },
            )
            error?.let {
                Spacer(Modifier.height(7.dp))
                Text(it, style = AppTypography.caption.medium, color = DarkPalette.error)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OverlayButton(
                    label = if (connecting) "连接中…" else "创建房间",
                    onClick = { onCreate(endpoint) },
                    modifier = Modifier.weight(1f),
                    tone = OverlayButtonTone.Primary,
                    enabled = !connecting,
                )
                OverlayButton(
                    label = "加入房间",
                    onClick = { onJoin(endpoint, normalizedRoom) },
                    modifier = Modifier.weight(1f),
                    enabled = WatchInvite.isCompleteCode(normalizedRoom) && !connecting,
                )
            }
        }
    }

    kickCandidate?.let { participant ->
        ConfirmDialog(
            liquidButtons = false,
            title = "移出成员",
            message = "确定将 ${participant.name} 移出房间吗？对方将无法再次加入当前房间。",
            confirmLabel = "移出",
            onConfirm = {
                onKickParticipant(participant.clientId)
                kickCandidate = null
            },
            onDismiss = { kickCandidate = null },
            destructive = true,
        )
    }
}

/**
 * Host side of the control handoff: a member has asked to drive the room.
 *
 * Both answers are explicit. Granting moves the timeline to them — this device becomes a
 * follower and its own controls lock — and denying tells the asker so, rather than letting
 * the request expire into silence.
 */
@Composable
internal fun ControlRequestDialog(
    requesterName: String,
    onGrant: () -> Unit,
    onDeny: () -> Unit,
) {
    GlassDialog(liquidButtons = false, onDismiss = onDeny) {
        OverlayHeader(
            title = "控制权请求",
            subtitle = "$requesterName 想要控制这个房间。交出后由对方掌握播放、暂停、进度和集数，你会跟随他们。",
            onClose = onDeny,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OverlayButton(
                label = "拒绝",
                onClick = onDeny,
                modifier = Modifier.weight(1f),
            )
            OverlayButton(
                label = "交给对方",
                onClick = onGrant,
                modifier = Modifier.weight(1f),
                tone = OverlayButtonTone.Primary,
            )
        }
    }
}

@Composable
private fun WatchInput(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val palette = LocalPalette.current
    val accent = rememberAccentColorsForSurface(dark = true)
    Box(
        Modifier
            .fillMaxWidth()
            .glass(AppShapes.control, palette.card2, palette.border)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isBlank()) {
            Text(
                placeholder,
                style = AppTypography.body.medium,
                color = palette.hint,
                maxLines = 1,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = AppTypography.body.medium.copy(color = palette.text),
            cursorBrush = SolidColor(accent.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
