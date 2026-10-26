package com.yfuse.feature.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.SettingRow
import com.yfuse.core.designsystem.SettingTint
import com.yfuse.core.widget.widgetCutoutPreferences
import com.yfuse.feature.widgets.WidgetCutoutSettingsDialog

@Composable
internal fun WidgetCutoutSettingsRow() {
    val preferences = remember { widgetCutoutPreferences() }
    val configuration by preferences.configuration.collectAsState()
    val status by preferences.status.collectAsState()
    var editing by remember { mutableStateOf(false) }
    SettingRow(
        "海报抠图",
        when {
            !configuration.enabled -> "已关闭"
            !configuration.configured -> "Poof · 未配置密钥"
            else -> status
        },
        true,
        { editing = true },
        icon = AppIcons.Grid,
        iconTint = SettingTint.components,
    )
    if (editing) WidgetCutoutSettingsDialog { editing = false }
}
