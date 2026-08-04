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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.WatchAvatar
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.sc
import com.yfuse.core.sync.WatchControlMode
import com.yfuse.core.sync.WatchInvite
import com.yfuse.core.sync.WatchNetworkQuality
import com.yfuse.core.sync.WatchParticipant

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
    onDismiss: () -> Unit,
) {
    var roomDraft by remember { mutableStateOf("") }
    val normalizedRoom = WatchInvite.normalizeCode(roomDraft)
    val palette = LocalPalette.current

    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "一起看",
            subtitle = if (connected) {
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
                    .glass(RoundedCornerShape(14.dp), palette.card2, palette.border)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    roomCode.orEmpty(),
                    style = sc(24f, 800),
                    color = Brand.Primary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Text(
                    "${if (isHost) "房主" else if (canControl) "可控制" else "成员"} · " +
                        "$participantCount 人在线 · ${participants.count { it.ready }} 人就绪",
                    style = mr(11f, 500),
                    color = palette.sub2,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            if (isHost) {
                Text(
                    "控制权限",
                    style = mr(10f, 600),
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
                        "点击下方成员可添加或取消管理员",
                        style = mr(9f, 500),
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
                                .width(108.dp)
                                .pressable(
                                    enabled = isHost &&
                                        controlMode == WatchControlMode.Moderators &&
                                        !participant.isHost,
                                    onClick = {
                                        onSetModerator(
                                            participant.clientId,
                                            !participant.isModerator,
                                        )
                                    },
                                ),
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
                                style = mr(8.5f, 500),
                                color = palette.sub2,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                participant.playbackStatusLabel,
                                style = mr(8f, 600),
                                color = when {
                                    !participant.mediaAvailable -> Brand.Danger
                                    participant.buffering -> Color(0xFFFFC857)
                                    participant.ready -> Brand.Primary
                                    else -> palette.sub2
                                },
                                maxLines = 1,
                            )
                            Text(
                                participant.networkStatusLabel,
                                style = mr(7.5f, 500),
                                color = when (participant.networkQuality) {
                                    WatchNetworkQuality.Excellent -> Brand.Primary
                                    WatchNetworkQuality.Fair -> Color(0xFFFFC857)
                                    WatchNetworkQuality.Poor -> Brand.Danger
                                    WatchNetworkQuality.Unknown -> palette.sub2
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = sc(10.5f, 500), color = Brand.Danger)
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
                        style = sc(10.5f, 500),
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
                Text(it, style = sc(10.5f, 500), color = Brand.Danger)
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
    GlassDialog(onDismiss = onDeny) {
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
    Box(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(13.dp), palette.card2, palette.border)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isBlank()) {
            Text(
                placeholder,
                style = mr(12f, 500),
                color = palette.hint,
                maxLines = 1,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = mr(12f, 500).copy(color = palette.text),
            cursorBrush = SolidColor(Brand.Primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
