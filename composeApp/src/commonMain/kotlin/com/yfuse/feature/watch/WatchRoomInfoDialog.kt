package com.yfuse.feature.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.sc
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.sync.WatchControlMode
import com.yfuse.core.sync.WatchInvite
import com.yfuse.core.sync.WatchParticipant
import com.yfuse.core.sync.WatchTogetherState
import com.yfuse.core.sync.parseEpisodeWatchKey

/**
 * 一起看 房间信息 — who is in the room, and what it is watching.
 *
 * The bar this opens from has one line to work with, and it spends it on the participant
 * count. Everything else the room knows — the code to share, who is actually ready, which
 * title is on the timeline — had no home outside the player until this.
 *
 * The title is resolved through [WatchInviteResolver], the same lookup the invite sheet runs:
 * a room publishes a `mediaKey` (`tmdb:1399/s2e5`), never a name, and the only way to turn
 * one into words is to ask this user's own servers what they hold under it. Resolved on open
 * rather than kept warm — the dialog is opened rarely, and a brief 正在识别 beats a stale name.
 */
@Composable
fun WatchRoomInfoDialog(
    state: WatchTogetherState,
    resolver: WatchInviteResolver,
    onEnter: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val mediaKey = state.mediaKey?.takeIf { it.isNotBlank() }

    var resolution by remember(mediaKey) {
        mutableStateOf<InviteResolution>(InviteResolution.Resolving)
    }
    LaunchedEffect(mediaKey) {
        val key = mediaKey ?: return@LaunchedEffect
        resolution = resolver.resolve(
            WatchInvite(roomCode = state.roomCode.orEmpty(), mediaKey = key),
        )
    }

    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "一起看",
            subtitle = state.roomCode?.let { "房间号 $it" } ?: "未在房间中",
            onClose = onDismiss,
        )
        Column(
            modifier = Modifier
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.roomCode?.let { roomCode ->
                CopyableRoomCode(
                    roomCode = roomCode,
                    modifier = Modifier.fillMaxWidth(),
                    style = sc(20f, 800),
                )
            }
            NowWatching(mediaKey = mediaKey, resolution = resolution)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow("我的身份", if (state.isHost) "房主" else "访客")
                InfoRow("控制权", state.controlMode.describe(state.canControl))
                InfoRow(
                    "连接",
                    when {
                        state.reconnecting -> "重连中"
                        state.connected -> "已连接"
                        else -> "未连接"
                    },
                )
                state.syncWarning?.let { InfoRow("提示", it) }
            }

            // The roster arrives with the room from protocol v3 on. The count is stated in the
            // heading because that is the one figure every room reports; the list is what a
            // room that sent participants can add to it.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "参与者 · ${state.participantCount} 人",
                    style = sc(12f, 700),
                    color = palette.sub2,
                )
                state.participants.forEach { ParticipantRow(it) }
            }

            if (state.connected) {
                Text(
                    "进入房间",
                    style = mr(12f, 700),
                    color = Brand.Primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressable {
                            onDismiss()
                            onEnter()
                        }
                        .clip(GlassShapes.chip)
                        .background(Brand.Primary.copy(alpha = 0.12f))
                        .padding(vertical = 11.dp),
                )
            }
        }
    }
}

@Composable
private fun NowWatching(mediaKey: String?, resolution: InviteResolution) {
    val palette = LocalPalette.current
    val coordinate = mediaKey?.let(::parseEpisodeWatchKey)
    // What can be said from the key alone, for every case the lookup cannot improve on.
    val fromKey = when {
        mediaKey == null -> "房间还没有开始播放"
        coordinate != null -> "第 ${coordinate.seasonNumber} 季 第 ${coordinate.episodeNumber} 集"
        else -> mediaKey
    }
    val title = when (resolution) {
        is InviteResolution.Found -> resolution.title
        is InviteResolution.Missing -> resolution.title ?: "本机的服务器没有这个片子"
        is InviteResolution.Failed -> fromKey
        InviteResolution.Resolving -> if (mediaKey == null) fromKey else "正在识别…"
    }
    val subtitle = when (resolution) {
        is InviteResolution.Found -> listOfNotNull(
            resolution.subtitle,
            resolution.serverName,
        ).joinToString(" · ")
        else -> fromKey
    }
    val poster = (resolution as? InviteResolution.Found)?.posterUrl

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(44.dp)
                .height(62.dp)
                .clip(GlassShapes.chip)
                .background(palette.card3),
        ) {
            if (poster != null) {
                AsyncImage(
                    model = poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("正在观看", style = sc(11f, 700), color = palette.sub2)
            Text(
                title,
                style = sc(14f, 700),
                color = palette.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank() && subtitle != title) {
                Text(
                    subtitle,
                    style = mr(11f, 400),
                    color = palette.sub2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ParticipantRow(participant: WatchParticipant) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (participant.ready) Brand.Online else Brand.Offline),
        )
        Text(
            if (participant.isSelf) "${participant.name}（我）" else participant.name,
            style = sc(12.5f, 600),
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(110.dp),
        )
        Text(
            listOfNotNull(
                if (participant.isHost) "房主" else null,
                participant.playbackStatusLabel,
            ).joinToString(" · "),
            style = mr(11f, 400),
            color = palette.sub2,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        Text(
            participant.networkStatusLabel,
            style = mr(10.5f, 500),
            color = palette.hint,
            maxLines = 1,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = mr(11.5f, 400), color = palette.sub2)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = mr(11.5f, 600),
            color = palette.body,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** 共同控制 says nothing about *this* device, and whether it holds control is the useful half. */
private fun WatchControlMode.describe(canControl: Boolean): String =
    label + if (canControl) " · 我可控制" else " · 我不可控制"
