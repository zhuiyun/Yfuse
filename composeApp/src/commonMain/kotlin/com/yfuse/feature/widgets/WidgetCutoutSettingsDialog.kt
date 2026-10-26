package com.yfuse.feature.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.SwitchRow
import com.yfuse.core.designsystem.YfFormField
import com.yfuse.core.widget.widgetCutoutPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun WidgetCutoutSettingsDialog(onDismiss: () -> Unit) {
    val preferences = remember { widgetCutoutPreferences() }
    val configuration by preferences.configuration.collectAsState()
    val status by preferences.status.collectAsState()
    var enabled by remember { mutableStateOf(configuration.enabled) }
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    GlassDialog(onDismiss = { if (!saving) onDismiss() }, dismissEnabled = !saving) {
        val palette = LocalPalette.current
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("海报抠图", color = palette.text, style = AppTypography.section.medium)
            SwitchRow("启用 Poof", enabled, !saving, onChange = { enabled = it })
            Text("首次生成会上传海报图片到 Poof，成功结果保存在本机，重启后继续复用。", color = palette.sub, style = AppTypography.body.regular)
            YfFormField(
                key,
                { key = it },
                "Poof API Key",
                enabled = !saving,
                placeholder = if (configuration.configured) "已保存，留空保留原密钥" else "输入 Poof API Key",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                visualTransformation = PasswordVisualTransformation(),
            )
            Text(error ?: status, color = palette.sub, style = AppTypography.caption.regular)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OverlayButton("取消", onClick = onDismiss, enabled = !saving)
                OverlayButton("保存", onClick = {
                    saving = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.Default) { preferences.save(enabled, key) }
                            key = ""
                            onDismiss()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = "保存失败，请检查密钥格式后重试"
                        } finally {
                            saving = false
                        }
                    }
                }, enabled = !saving)
            }
        }
    }
}
