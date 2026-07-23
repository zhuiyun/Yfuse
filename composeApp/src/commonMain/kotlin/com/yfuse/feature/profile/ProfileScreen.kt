package com.yfuse.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.app.TabBarInset
import com.yfuse.core.designsystem.AccentColor
import com.yfuse.core.designsystem.GlassCard
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalGlass
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.core.designsystem.glass

@Composable
fun ProfileScreen(component: ProfileComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val mode by component.themePreferences.mode.collectAsState()
    val accent by component.themePreferences.accent.collectAsState()
    val glass = LocalGlass.current
    var confirmLogout by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = TabBarInset),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                    ),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Person, null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            state.currentServer?.userName ?: "未登录",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = glass.onGlass,
                        )
                        Text(
                            state.currentServer?.let { "当前:${it.serverName}" } ?: "请先添加服务器",
                            style = MaterialTheme.typography.bodySmall,
                            color = glass.onGlassMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        item { SectionLabel("我的服务器") }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                SettingRow(
                    icon = Icons.Rounded.Dns,
                    title = "服务器管理",
                    subtitle = if (state.serverCount > 0) "共 ${state.serverCount} 个服务器" else "添加 Emby 服务器",
                    onClick = component.onOpenServers,
                )
            }
        }

        item { SectionLabel("外观") }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("主题", style = MaterialTheme.typography.bodyLarge, color = glass.onGlass)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { entry ->
                            SegmentChip(
                                label = entry.label,
                                selected = entry == mode,
                                onClick = { component.themePreferences.setMode(entry) },
                            )
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    Text("强调色", style = MaterialTheme.typography.bodyLarge, color = glass.onGlass)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AccentColor.entries.forEach { entry ->
                            AccentDot(
                                accent = entry,
                                selected = entry == accent,
                                onClick = { component.themePreferences.setAccent(entry) },
                            )
                        }
                    }
                }
            }
        }

        item { SectionLabel("其他") }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    SettingRow(
                        icon = Icons.Rounded.Info,
                        title = "关于",
                        subtitle = "Yfuse v${state.appVersion}",
                        onClick = {},
                    )
                    if (state.currentServer != null) {
                        SettingRow(
                            icon = Icons.AutoMirrored.Rounded.Logout,
                            title = "退出当前服务器",
                            subtitle = null,
                            tint = MaterialTheme.colorScheme.error,
                            onClick = { confirmLogout = true },
                        )
                    }
                }
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出登录") },
            text = { Text("将从列表中移除当前服务器「${state.currentServer?.serverName ?: ""}」,可重新登录。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    component.store.accept(ProfileIntent.Logout)
                }) { Text("退出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = LocalGlass.current.onGlassMuted,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun SegmentChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val glass = LocalGlass.current
    Box(
        Modifier
            .glass(GlassShapes.chip, strong = selected)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else glass.onGlassMuted,
        )
    }
}

@Composable
private fun AccentDot(accent: AccentColor, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.onBackground else Color.Transparent
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(accent.color)
            .border(2.dp, ring, CircleShape)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    tint: Color = LocalGlass.current.onGlass,
    onClick: () -> Unit,
) {
    val glass = LocalGlass.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tint)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = glass.onGlassMuted)
            }
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = glass.onGlassMuted, modifier = Modifier.size(20.dp))
    }
}
