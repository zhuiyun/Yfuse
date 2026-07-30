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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.GlassBottomSheet
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.sc
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
    data class Missing(val title: String?) : InviteResolution

    data class Failed(val message: String) : InviteResolution
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
    GlassBottomSheet(onDismiss = onDismiss) {
        OverlayHeader(
            title = "一起看邀请",
            subtitle = "房间码 $roomCode · 视频仍由你自己的服务器播放",
            onClose = onDismiss,
        )

        when (resolution) {
            InviteResolution.Resolving -> Row(
                Modifier.fillMaxWidth().padding(vertical = 18.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("正在你的服务器上查找…", style = sc(12.5f, 500), color = palette.sub)
            }

            is InviteResolution.Found -> {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .solidGlass(
                            shape = GlassShapes.chip,
                            fill = Brand.Primary.copy(alpha = 0.08f),
                            border = Brand.Primary.copy(alpha = 0.24f),
                        )
                        .padding(12.dp),
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
                            style = sc(14f, 700),
                            color = palette.text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        resolution.subtitle?.let {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                it,
                                style = mr(10.5f, 400),
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
                                style = mr(10f, 500),
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
                        style = sc(10.5f, 400, lineHeight = 10.5f * 1.6f),
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
                    style = sc(12.5f, 400, lineHeight = 12.5f * 1.65f),
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
                    style = sc(12.5f, 400, lineHeight = 12.5f * 1.65f),
                    color = Brand.Danger,
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
 */
@Composable
fun WatchInviteShareSheet(
    roomCode: String,
    title: String?,
    participantCount: Int,
    shareText: String,
    onShare: (String) -> Unit,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    GlassBottomSheet(onDismiss = onDismiss) {
        OverlayHeader(
            title = "邀请一起看",
            subtitle = title?.let { "《$it》· 对方用自己的服务器播放" }
                ?: "对方用自己的服务器播放",
            onClose = onDismiss,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .solidGlass(
                    shape = GlassShapes.chip,
                    fill = Brand.Primary.copy(alpha = 0.08f),
                    border = Brand.Primary.copy(alpha = 0.24f),
                )
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(roomCode, style = sc(26f, 800), color = Brand.Primary)
            Text(
                if (participantCount > 1) "$participantCount 人在房间" else "等待对方加入",
                style = mr(10.5f, 500),
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
                tone = OverlayButtonTone.Primary,
            )
        }
        Text(
            "对方点开链接即可直接加入，无需手输房间码。",
            style = sc(10.5f, 400, lineHeight = 10.5f * 1.6f),
            color = palette.sub2,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}
