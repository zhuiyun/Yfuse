package com.yfuse.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.app.TabBarInset
import com.yfuse.app.hideBottomBarOnScroll
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.core.designsystem.cssLinearGradient
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.sc
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackQuality
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.model.SavedServer

/** Which option sheet is open — the prototype's `settingsSheetTab`. */
private enum class Sheet { Engine, Decoder, Quality, Cache, Server }

/** 个人中心 — `padding:52px 18px 100px; gap:18px`. */
@Composable
fun ProfileScreen(component: ProfileComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val prefs = component.themePreferences
    val mode by prefs.mode.collectAsState()
    val engine by prefs.engine.collectAsState()
    val decoder by prefs.decoder.collectAsState()
    val autoNext by prefs.autoNext.collectAsState()
    val quality by prefs.quality.collectAsState()

    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var confirmRemove by remember { mutableStateOf<SavedServer?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding().hideBottomBarOnScroll(),
            contentPadding = PaddingValues(top = Dimens.contentTop, bottom = TabBarInset),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                UserCard(
                    userName = state.currentServer?.userName ?: "未登录",
                    serverName = state.currentServer?.serverName,
                    onClick = { sheet = Sheet.Server },
                )
            }

            item {
                Section(
                    title = "我的服务器",
                    action = "+ 添加",
                    onAction = component.onOpenServers,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.servers.forEach { server ->
                            ServerRow(
                                server = server,
                                isCurrent = server.id == state.currentServer?.id,
                                onClick = {
                                    component.store.accept(ProfileIntent.SwitchServer(server.id))
                                },
                                onLongClick = { confirmRemove = server },
                            )
                        }
                    }
                }
            }

            item {
                Section(title = "播放") {
                    // `gap:1px; border-radius:16px; overflow:hidden` — hairline-joined rows.
                    Column(
                        Modifier.clip(GlassShapes.card),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        SettingRow("播放器内核", engine.label, onClick = { sheet = Sheet.Engine })
                        SettingRow("解码内核", decoder.label, onClick = { sheet = Sheet.Decoder })
                        SettingRow("默认清晰度", quality.label, onClick = { sheet = Sheet.Quality })
                        SettingRow("下载与缓存", "缓存 ›", onClick = { sheet = Sheet.Cache })
                        SwitchRow("自动播放下一集", autoNext) { prefs.setAutoNext(it) }
                    }
                }
            }

            item {
                Section(title = "外观") {
                    Column(Modifier.clip(GlassShapes.card)) {
                        SwitchRow("深色模式", mode == ThemeMode.Dark) { on ->
                            prefs.setMode(if (on) ThemeMode.Dark else ThemeMode.Light)
                        }
                    }
                }
            }
        }

        when (sheet) {
            Sheet.Engine -> OptionSheet(
                title = "选择播放器内核",
                options = PlayerEngine.selectable.map { it.label to (it == engine) },
                onSelect = { index ->
                    prefs.setEngine(PlayerEngine.selectable[index])
                    sheet = null
                },
                onDismiss = { sheet = null },
            )

            Sheet.Decoder -> OptionSheet(
                title = "选择解码内核",
                options = DecoderMode.entries.map { it.label to (it == decoder) },
                onSelect = { index ->
                    prefs.setDecoder(DecoderMode.entries[index])
                    sheet = null
                },
                onDismiss = { sheet = null },
            )

            Sheet.Quality -> OptionSheet(
                title = "选择默认清晰度",
                options = PlaybackQuality.entries.map { it.label to (it == quality) },
                onSelect = { index ->
                    prefs.setQuality(PlaybackQuality.entries[index])
                    sheet = null
                },
                onDismiss = { sheet = null },
            )

            Sheet.Cache -> OptionSheet(
                title = "下载与缓存",
                options = listOf("图片与元数据缓存" to false, "清除全部缓存" to false),
                destructiveIndex = 1,
                onSelect = { index ->
                    if (index == 1) component.onClearCache()
                    sheet = null
                },
                onDismiss = { sheet = null },
            )

            Sheet.Server -> OptionSheet(
                title = "切换用户",
                options = state.servers.map {
                    "${it.userName} · ${it.serverName}" to (it.id == state.currentServer?.id)
                },
                onSelect = { index ->
                    component.store.accept(ProfileIntent.SwitchServer(state.servers[index].id))
                    sheet = null
                },
                onDismiss = { sheet = null },
            )

            null -> Unit
        }
    }

    confirmRemove?.let { server ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("移除服务器", style = sc(15f, 700)) },
            text = { Text("将从列表中移除「${server.serverName}」，可重新登录。", style = sc(13f, 400)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    if (server.id == state.currentServer?.id) {
                        component.store.accept(ProfileIntent.Logout)
                    } else {
                        component.onRemoveServer(server.id)
                    }
                }) { Text("移除", style = sc(13f, 700), color = Brand.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) {
                    Text("取消", style = sc(13f, 500))
                }
            },
        )
    }
}

/**
 * User card — `gap:14px`, `--pg-card` over 1px `--pg-border`, `radius:20px`,
 * `padding:16px`, `0 8px 24px rgba(90,120,180,.12)`; 56px avatar.
 */
@Composable
private fun UserCard(userName: String, serverName: String?, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pageHorizontal)
            .shadow(Shadows.profileCard, shape)
            .glass(shape)
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(com.yfuse.core.designsystem.PrimaryGradient),
        )
        Column(Modifier.weight(1f)) {
            Text(userName, style = sc(15f, 700), color = palette.text)
            Spacer(Modifier.height(3.dp))
            Text(
                serverName?.let { "当前：$it · 切换用户 ›" } ?: "请先添加服务器",
                style = mr(11f, 400),
                color = palette.sub2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Section header — `700 12px`, `--pg-sub2`, `letter-spacing:.5px`, `margin-bottom:8px`;
 * optional trailing action at `600 11px Manrope`, `#3D64C9`.
 */
@Composable
private fun Section(
    title: String,
    action: String? = null,
    onAction: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    Column(Modifier.padding(horizontal = Dimens.pageHorizontal)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = sc(12f, 700).copy(letterSpacing = 0.5.sp), color = palette.sub2)
            if (action != null) {
                Text(
                    action,
                    style = mr(11f, 600),
                    color = Brand.Primary,
                    modifier = Modifier.clickable(onClick = onAction),
                )
            }
        }
        content()
    }
}

/**
 * Server row — `radius:14px`, `padding:11px 12px`, `gap:11px`; current uses
 * `rgba(61,100,201,.1)` over `rgba(61,100,201,.3)`, others `--pg-card2`-ish white.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ServerRow(
    server: SavedServer,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val shape = GlassShapes.chip
    Row(
        Modifier
            .fillMaxWidth()
            .glass(
                shape = shape,
                fill = if (isCurrent) Brand.Primary.copy(alpha = 0.1f) else palette.card2,
                border = if (isCurrent) Brand.Primary.copy(alpha = 0.3f) else palette.border,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 34×34, `radius:9px`, 135deg gradient, `700 12px Manrope` initial.
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(serverGradient(server.id)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                server.serverName.take(1).uppercase(),
                style = mr(12f, 700),
                color = Color.White,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                server.serverName,
                style = sc(12.5f, 700),
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (isCurrent) Brand.Online else Brand.Offline),
                )
                Text(
                    if (isCurrent) "当前使用 · ${server.userName}" else server.userName,
                    style = mr(10f, 400),
                    color = palette.sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isCurrent) {
            Icon(AppIcons.Check, null, tint = Brand.Primary, modifier = Modifier.size(13.dp))
        } else {
            Text("切换", style = mr(11f, 400), color = Brand.Offline)
        }
    }
}

/** Settings row — `--pg-card2`, `padding:13px 16px`, `500 13px` / `400 12px Manrope`. */
@Composable
private fun SettingRow(title: String, value: String, onClick: (() -> Unit)? = null) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(palette.card2)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = sc(13f, 500), color = palette.text, maxLines = 1)
        Text(value, style = mr(12f, 400), color = palette.sub2, maxLines = 1)
    }
}

/** Same row with the prototype's 38×22 pill switch. */
@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(palette.card2)
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = sc(13f, 500), color = palette.text, maxLines = 1)
        PillSwitch(checked)
    }
}

/**
 * `width:38px;height:22px;border-radius:11px` track — `#3D64C9` on, `rgba(0,0,0,.15)`
 * off — with an 18px knob inset 2px.
 */
@Composable
private fun PillSwitch(checked: Boolean) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .width(38.dp)
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(
                if (checked) {
                    Brand.Primary
                } else if (palette.isDark) {
                    Color.White.copy(alpha = 0.15f)
                } else {
                    Color.Black.copy(alpha = 0.15f)
                },
            ),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 2.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/**
 * Bottom option sheet — scrim `rgba(0,0,0,.35)`; panel `left/right:16px`,
 * `bottom:90px`, `rgba(255,255,255,.95)` over a `rgba(255,255,255,.9)` hairline,
 * `radius:18px`, `padding:12px`, `0 20px 44px -10px rgba(30,40,70,.3)`.
 */
@Composable
private fun OptionSheet(
    title: String,
    options: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    destructiveIndex: Int = -1,
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onDismiss),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 90.dp)
            .shadow(Shadows.sheet, shape)
            .glass(shape, Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.9f))
            .padding(12.dp),
    ) {
        Text(
            title,
            style = sc(12f, 700),
            color = Color(0xFF151A22),
            modifier = Modifier.padding(bottom = 8.dp),
        )
        options.forEachIndexed { index, (label, selected) ->
            val destructive = index == destructiveIndex
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(GlassShapes.chipSmall)
                    .background(
                        if (selected) Brand.Primary.copy(alpha = 0.1f) else Color.Transparent,
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = sc(12.5f, if (selected || destructive) 700 else 500),
                    color = when {
                        destructive -> Brand.Danger
                        selected -> Brand.Primary
                        else -> Color(0xFF151A22)
                    },
                )
                if (selected) {
                    Icon(
                        AppIcons.Check,
                        null,
                        tint = Brand.Primary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
    }
}

/** Stable per-server badge gradient, drawn from the prototype's four pairs. */
private fun serverGradient(id: String) = cssLinearGradient(
    135f,
    0f to serverGradients[(id.hashCode().let { if (it < 0) -it else it }) % serverGradients.size].first,
    1f to serverGradients[(id.hashCode().let { if (it < 0) -it else it }) % serverGradients.size].second,
)

private val serverGradients = listOf(
    Color(0xFF8FB2E8) to Color(0xFF5B7FD1),
    Color(0xFFE8C9A0) to Color(0xFFC98F5B),
    Color(0xFFC9D4E8) to Color(0xFF8FA4C9),
    Color(0xFFA9C9E8) to Color(0xFF6F93D1),
)
