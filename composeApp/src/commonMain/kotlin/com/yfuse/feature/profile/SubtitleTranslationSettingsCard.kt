package com.yfuse.feature.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.yfuse.core.subtitletranslation.SubtitleTranslationSettings
import com.yfuse.core.subtitletranslation.createSubtitleTranslationSettingsStore

@Composable
internal fun SubtitleTranslationSettingsCard() {
    val store = remember { createSubtitleTranslationSettingsStore() }
    var open by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(SubtitleTranslationSettings()) }
    var message by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = {
        try {
            settings = store.load()
            message = null
            open = true
        } catch (_: Exception) {
            message = "翻译设置暂时无法读取"
        }
    }) { Text("字幕翻译 · OpenAI 兼容接口") }
    message?.let { Text(it) }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("字幕翻译设置") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("播放中打开字幕面板，选择“翻译字幕文件”。仅在确认翻译后发送字幕文字到此地址，可能产生服务费用。HTTP 地址可用。")
                    OutlinedTextField(settings.endpoint, {
                        settings = settings.copy(endpoint = it)
                    }, label = { Text("接口地址（如 https://服务地址/v1）") }, singleLine = true)
                    OutlinedTextField(
                        settings.model,
                        { settings = settings.copy(model = it) },
                        label = { Text("模型") },
                        singleLine = true,
                    )
                    OutlinedTextField(settings.apiKey, {
                        settings = settings.copy(apiKey = it)
                    }, label = {
                        Text(
                            "API 密钥（加密保存）",
                        )
                    }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    OutlinedTextField(settings.targetLanguage, {
                        settings = settings.copy(targetLanguage = it)
                    }, label = { Text("目标语言") }, singleLine = true)
                    message?.let { Text(it) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        store.save(settings.copy(endpoint = settings.endpoint.trim(), model = settings.model.trim()))
                        open =
                            false
                        message = "翻译设置已保存"
                    } catch (_: Exception) {
                        message = "无法保存，请检查 HTTP/HTTPS 地址、模型和目标语言，地址不能含账号或查询参数"
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = {
                    try {
                        store.clear()
                        settings = SubtitleTranslationSettings()
                        open = false
                        message = "已清除翻译设置和密钥"
                    } catch (_: Exception) {
                        message = "清除失败，请稍后重试"
                    }
                }) { Text("清除配置") }
            },
        )
    }
}
