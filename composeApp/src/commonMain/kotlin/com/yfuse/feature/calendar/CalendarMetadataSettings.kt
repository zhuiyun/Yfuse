package com.yfuse.feature.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.YfButton
import com.yfuse.core.designsystem.YfButtonTone
import com.yfuse.core.designsystem.YfFormField
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun CalendarMetadataSettings(component: CalendarComponent) {
    val preferences = component.metadataPreferences ?: return
    val palette = LocalPalette.current
    var todbEnabled by remember { mutableStateOf(preferences.todbEnabled) }
    var tvdbConfigured by remember { mutableStateOf(preferences.tvdbCredentials() != null) }
    var editingTvdb by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("排期数据源", style = AppTypography.body.strong, color = palette.text)
        Text("TMDB · 已启用", style = AppTypography.body.medium, color = palette.text)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("TheOtherDB", style = AppTypography.body.medium, color = palette.text)
                Text("补充已公布的分集日期", style = AppTypography.caption.regular, color = palette.sub)
            }
            Switch(
                modifier =
                    Modifier.semantics {
                        contentDescription = "TheOtherDB 排期"
                    },
                checked = todbEnabled,
                onCheckedChange = {
                    preferences.todbEnabled = it
                    todbEnabled = it
                    component.store.accept(CalendarIntent.Refresh)
                },
            )
        }
        YfButton(
            label = if (tvdbConfigured) "TVDB · 管理配置" else "TVDB · 配置访问密钥",
            tone = YfButtonTone.Secondary,
            onClick = { editingTvdb = true },
        )
        Text(
            "优先保留可信排期；补充来源只使用已公布日期，没有具体时间时显示时间待定。",
            style = AppTypography.caption.regular,
            color = palette.sub,
        )
    }
    if (editingTvdb) {
        var apiKey by remember { mutableStateOf("") }
        var pin by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        GlassDialog(onDismiss = { editingTvdb = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("TVDB 排期", style = AppTypography.section.strong, color = palette.text)
                Text(
                    "使用你自己的 TVDB API Key。订阅型密钥还需填写 PIN；凭据仅加密保存在本机。",
                    style = AppTypography.body.regular,
                    color = palette.sub,
                )
                YfFormField(
                    value = apiKey,
                    onValueChange = { apiKey = it.take(512) },
                    label = if (tvdbConfigured) "新的 API Key" else "API Key",
                    visualTransformation = PasswordVisualTransformation(),
                )
                YfFormField(
                    value = pin,
                    onValueChange = { pin = it.take(128) },
                    label = "订阅 PIN（可选）",
                    visualTransformation = PasswordVisualTransformation(),
                )
                error?.let { Text(it, style = AppTypography.caption.regular, color = palette.error) }
                YfButton("保存配置", enabled = apiKey.isNotBlank(), onClick = {
                    runCatching { preferences.saveTvdb(apiKey, pin) }
                        .onSuccess {
                            tvdbConfigured = true
                            editingTvdb = false
                            component.store.accept(CalendarIntent.Refresh)
                        }.onFailure { error = "保存失败，请检查输入或稍后重试" }
                })
                if (tvdbConfigured) {
                    YfButton("移除 TVDB 配置", tone = YfButtonTone.Secondary, onClick = {
                        runCatching { preferences.clearTvdb() }
                            .onSuccess {
                                tvdbConfigured = false
                                editingTvdb = false
                                component.store.accept(CalendarIntent.Refresh)
                            }.onFailure { error = "暂时无法移除配置，请重试" }
                    })
                }
            }
        }
    }
}
