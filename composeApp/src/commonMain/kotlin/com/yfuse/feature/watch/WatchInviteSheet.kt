package com.yfuse.feature.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.solidGlass

/**
 * What the guest sees after tapping an invite link. Everything the old flow made them do by
 * hand — find the title in their own library, start it, dig into the player's settings,
 * type a six-character code — collapses into confirming one sheet.
 */
sealed interface InviteResolution {
    data object Resolving : InviteResolution

    /** Found on one of this user's own servers; [posterUrl] may still be null. */
    data class Found(
        val serverName: String,
        val title: String,
        val subtitle: String?,
        val posterUrl: String?,
    ) : InviteResolution

    /** No server has it. The room is real, this viewer just can't play along. */
    data class Missing(
        val title: String?,
    ) : InviteResolution

    data class Failed(
        val message: String,
    ) : InviteResolution
}

@Composable
fun WatchInviteSheet(
    roomCode: String,
    resolution: InviteResolution,
    /** Non-null only when the invite named a relay other than the configured one. */
    unfamiliarEndpoint: String?,
    onJoin: () -> Unit,
    onSearchByName: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "一起看邀请",
            subtitle = "房间码 $roomCode · 视频仍由你自己的服务器播放",
            onClose = onDismiss,
        )
        CopyableRoomCode(
            roomCode = roomCode,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            style = AppTypography.section.strong,
        )

        when (resolution) {
            InviteResolution.Resolving ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        color = accent.accent,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("正在你的服务器上查找…", style = AppTypography.body.medium, color = palette.sub)
                }

            is InviteResolution.Found -> {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .solidGlass(
                            shape = GlassShapes.chip,
                            fill = accent.container,
                            border = accent.border,
                        ).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Poster(
                        url = resolution.posterUrl,
                        modifier = Modifier.width(56.dp).height(82.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            resolution.title,
                            style = AppTypography.body.strong,
                            color = palette.text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        resolution.subtitle?.let {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                it,
                                style = AppTypography.caption.regular,
                                color = palette.sub2,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(6.dp).clip(CircleShape).background(Brand.Online),
                            )
                            Text(
                                "在「${resolution.serverName}」找到",
                                style = AppTypography.caption.medium,
                                color = palette.sub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                unfamiliarEndpoint?.let { endpoint ->
                    // The link can point the app at any relay. Naming it before use keeps
                    // that visible: whoever runs it learns what this room is watching.
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "这条邀请使用的一起看服务器是 $endpoint，与你当前配置的不同。加入即表示信任该服务器。",
                        style = AppTypography.caption.regular.copy(lineHeight = 16.8.sp),
                        color = palette.sub2,
                    )
                }

                OverlayButton(
                    label = "加入并开始播放",
                    onClick = onJoin,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    tone = OverlayButtonTone.Primary,
                )
            }

            is InviteResolution.Missing -> {
                Text(
                    buildString {
                        append("你的服务器上没有找到")
                        resolution.title?.let { append("《$it》") }
                        append("。一起看要求各自播放自己的文件，所以需要先在你的媒体库里有这部片。")
                    },
                    style = AppTypography.body.regular.copy(lineHeight = 20.6.sp),
                    color = palette.body,
                )
                OverlayButton(
                    label = "按名字搜索",
                    onClick = onSearchByName,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    tone = OverlayButtonTone.Primary,
                    enabled = resolution.title != null,
                )
            }

            is InviteResolution.Failed -> {
                Text(
                    resolution.message,
                    style = AppTypography.body.regular.copy(lineHeight = 20.6.sp),
                    color = palette.error,
                )
                OverlayButton(
                    label = "关闭",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                )
            }
        }
    }
}

/**
 * The host's half: shown right after a room is created, so "I want to watch this with
 * someone" reaches "invitation sent" without leaving the page.
 *
 * Playback is started *from here* rather than alongside the room. Creating the room and
 * launching the player in the same tap put this sheet behind a full-screen player activity:
 * the host got a room whose invite they could not see until they backed out of the film,
 * which is the one moment the invite is worthless. The room now has to be handed over before
 * the picture starts.
 *
 * [roomCode] is null until the relay answers, so the sheet also owns the waiting and failing
 * states — a relay that never answers used to leave the tap with no visible result at all.
 */
@Composable
fun WatchInviteShareSheet(
    roomCode: String?,
    connecting: Boolean,
    error: String?,
    title: String?,
    participantCount: Int,
    shareText: String,
    onShare: (String) -> Unit,
    onCopy: (String) -> Unit,
    onStartPlayback: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "邀请一起看",
            subtitle =
                title?.let { "《$it》· 对方用自己的服务器播放" }
                    ?: "对方用自己的服务器播放",
            onClose = onDismiss,
        )
        when {
            error != null -> {
                Text(
                    error,
                    style = AppTypography.caption.medium.copy(lineHeight = 18.4.sp),
                    color = palette.error,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OverlayButton(
                        label = "关闭",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    // The film is still what they came for; a dead relay shouldn't also cost
                    // them the tap that starts it.
                    OverlayButton(
                        label = "直接播放",
                        onClick = onStartPlayback,
                        modifier = Modifier.weight(1f),
                        tone = OverlayButtonTone.Primary,
                    )
                }
            }

            roomCode == null -> {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 22.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp),
                        color = accent.accent,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (connecting) "正在创建房间…" else "正在连接一起看服务…",
                        style = AppTypography.body.medium,
                        color = palette.sub,
                    )
                }
            }

            else -> {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .solidGlass(
                            shape = GlassShapes.chip,
                            fill = accent.container,
                            border = accent.border,
                        ).padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CopyableRoomCode(
                        roomCode = roomCode,
                        modifier = Modifier.fillMaxWidth(),
                        style = AppTypography.display.strong,
                    )
                    Text(
                        if (participantCount > 1) "$participantCount 人在房间" else "等待对方加入",
                        style = AppTypography.caption.medium,
                        color = palette.sub2,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OverlayButton(
                        label = "复制邀请",
                        onClick = { onCopy(shareText) },
                        modifier = Modifier.weight(1f),
                    )
                    OverlayButton(
                        label = "分享链接",
                        onClick = { onShare(shareText) },
                        modifier = Modifier.weight(1f),
                    )
                }
                OverlayButton(
                    label = "开始播放",
                    onClick = onStartPlayback,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    tone = OverlayButtonTone.Primary,
                )
                Text(
                    "对方点开链接即可直接加入，无需手输房间码。你开始播放后，房间里的其他人会自动跟上。",
                    style = AppTypography.caption.regular.copy(lineHeight = 16.8.sp),
                    color = palette.sub2,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}
