package com.yfuse.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfuse.core.designsystem.AppIcons

/**
 * The settings sub-pages a television viewer can reach.
 *
 * Android keeps the same list as a local `ProfilePage` enum inside `ProfileScreen.kt` rather than as
 * Decompose children, so there is no component seam to reuse. These pages therefore mirror that
 * structure and share the phone's stores and preference objects instead of its composables, which
 * are built for touch.
 */
internal enum class TvSettingsPage(
    val title: String,
    val subtitle: String,
) {
    Root("设置", ""),
    Account("账号与同步", "登录、加密同步与云端数据"),
    AccountSessions("设备会话", "在其他设备上的登录状态"),
    Playback("播放", "播放行为、进度与片头片尾"),
    AdvancedPlayback("高级播放", "内核、解码与设备输出"),
    Danmaku("弹幕", "开关、显示与过滤"),
    WatchTogether("一起看", "房间资料与聊天显示"),
    Appearance("外观与辅助", "主题、背景、启动位置与辅助显示"),
    Splash("开屏动画", "启动时播放的品牌动画"),
    Downloads("下载与离线库", "离线内容、队列与存储位置"),
    ServerBackup("服务器备份与迁移", "导出、导入与换机搬迁"),
    PermissionHealth("权限检查", "影响播放与发现的系统权限"),
    DataAndDiagnostics("数据与诊断", "日志、缓存与导出"),
}

/**
 * Shared page chrome. The navigation rail stays visible on a sub-page, so the header carries the
 * page name rather than a back button; Back and D-pad left both return to the settings root.
 */
@Composable
internal fun TvSettingsPageScaffold(
    page: TvSettingsPage,
    status: String? = null,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = TvSafeVertical, bottom = TvSafeVertical),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "page-header:${page.name}") {
            Column {
                Text(
                    page.title,
                    color = TvOnSurface,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                if (page.subtitle.isNotEmpty()) {
                    Text(page.subtitle, color = TvOnSurfaceMuted, fontSize = 15.sp)
                }
                if (!status.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(status, color = TvAccent, fontSize = 14.sp)
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        content()
    }
}

/** A section heading inside a settings page. */
@Composable
internal fun TvSettingsSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        title,
        color = TvOnSurface,
        fontSize = 21.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(top = 16.dp),
    )
}

/** Explanatory copy that is read but never focused, so the remote never stops on it. */
@Composable
internal fun TvSettingsNote(
    text: String,
    modifier: Modifier = Modifier,
    tone: Color = TvOnSurfaceMuted,
) {
    Text(
        text,
        color = tone,
        fontSize = 14.sp,
        modifier = modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

/**
 * One focusable settings row.
 *
 * `focusScope` is a parameter rather than a constant because every sub-page needs its own scope;
 * sharing one scope would make focus memory restore the wrong row after a page change.
 */
@Composable
internal fun TvSettingRow(
    title: String,
    value: String,
    stableId: String,
    focusMemory: TvUiFocusMemory,
    onClick: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    focusScope: String = "settings",
    subtitle: String? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    navigationRequester: FocusRequester? = null,
) {
    TvFocusableSurface(
        stableId = stableId,
        focusScope = focusScope,
        focusMemory = focusMemory,
        onClick = { if (enabled) onClick() },
        contentDescription = if (value.isBlank()) title else "$title，$value",
        modifier = modifier.fillMaxWidth().height(if (subtitle == null) 72.dp else 88.dp),
        selected = selected,
        focusRequester = focusRequester,
        navigationRequester = navigationRequester,
        returnToNavigationOnLeft = true,
        scaleWhenFocused = 1.015f,
    ) { focused ->
        Row(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint =
                    when {
                        !enabled -> TvOnSurfaceMuted.copy(alpha = 0.45f)
                        focused -> Color.White
                        else -> TvAccent
                    },
            )
            Spacer(Modifier.width(17.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = if (enabled) TvOnSurface else TvOnSurface.copy(alpha = 0.45f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (subtitle != null) {
                    Text(subtitle, color = TvOnSurfaceMuted, fontSize = 14.sp, maxLines = 2)
                }
            }
            if (value.isNotBlank()) {
                Spacer(Modifier.width(12.dp))
                Text(
                    value,
                    color = if (enabled) TvOnSurfaceMuted else TvOnSurfaceMuted.copy(alpha = 0.45f),
                    fontSize = 15.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = TvOnSurfaceMuted,
            )
        }
    }
}

/** A row whose click flips a boolean. The value column states the resulting condition, not a verb. */
@Composable
internal fun TvToggleRow(
    title: String,
    checked: Boolean,
    stableId: String,
    focusMemory: TvUiFocusMemory,
    onToggle: (Boolean) -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    focusScope: String = "settings",
    subtitle: String? = null,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    navigationRequester: FocusRequester? = null,
) {
    TvSettingRow(
        title = title,
        value = if (checked) "已开启" else "已关闭",
        stableId = stableId,
        focusMemory = focusMemory,
        onClick = { onToggle(!checked) },
        icon = icon,
        modifier = modifier,
        focusScope = focusScope,
        subtitle = subtitle,
        enabled = enabled,
        selected = checked,
        focusRequester = focusRequester,
        navigationRequester = navigationRequester,
    )
}

/**
 * A row that steps through a fixed list of options.
 *
 * A remote has no room for a dropdown on every setting, so select advances to the next option and
 * wraps. The current option is always shown, so one press is always reversible by cycling round.
 */
@Composable
internal fun <T> TvChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    stableId: String,
    focusMemory: TvUiFocusMemory,
    onSelect: (T) -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    focusScope: String = "settings",
    subtitle: String? = null,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    navigationRequester: FocusRequester? = null,
) {
    TvSettingRow(
        title = title,
        value = label(selected),
        stableId = stableId,
        focusMemory = focusMemory,
        onClick = {
            if (options.isNotEmpty()) {
                val index = options.indexOf(selected)
                onSelect(options[(index + 1).mod(options.size)])
            }
        },
        icon = icon,
        modifier = modifier,
        focusScope = focusScope,
        subtitle = subtitle,
        enabled = enabled,
        focusRequester = focusRequester,
        navigationRequester = navigationRequester,
    )
}
