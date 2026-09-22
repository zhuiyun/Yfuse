package com.yfuse.tv.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.feature.profile.ProfileComponent

/**
 * Owner-only resource integrations. The settings root hides this page unless
 * `canUseMediaDiscovery()` allows it, so reaching it already implies the capability.
 */
@Composable
internal fun TvMediaDiscoverySettingsPage(
    component: ProfileComponent,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    firstRowRequester: FocusRequester,
) {
    val focusScope = "settings:discovery"
    val prefs = component.tgtoMediaPreferences
    val connection by prefs.connection.collectAsState()
    val pan123 by prefs.pan123Authorization.collectAsState()
    val homeEnabled by prefs.discoveryHomeEnabled.collectAsState()

    var endpoint by remember(connection.endpoint) { mutableStateOf(connection.endpoint) }
    var username by remember(connection.username) { mutableStateOf(connection.username) }
    var password by remember { mutableStateOf("") }
    var phone by remember(pan123.phone) { mutableStateOf(pan123.phone) }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    TvSettingsPageScaffold(page = TvSettingsPage.MediaDiscovery, status = status) {
        item(key = "discovery-home") {
            TvToggleRow(
                title = "在首页显示影视发现",
                checked = homeEnabled,
                stableId = "discovery:home",
                focusMemory = focusMemory,
                onToggle = prefs::setDiscoveryHomeEnabled,
                icon = AppIcons.Home,
                focusScope = focusScope,
                focusRequester = firstRowRequester,
                navigationRequester = navigationRequester,
            )
        }

        item(key = "discovery-section-tgto") { TvSettingsSectionTitle("资源站点") }
        item(key = "discovery-endpoint") {
            TvSettingsTextField(
                value = endpoint,
                label = "站点地址",
                stableId = "discovery:endpoint",
                focusScope = focusScope,
                focusMemory = focusMemory,
                onValueChange = { endpoint = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        item(key = "discovery-username") {
            TvSettingsTextField(
                value = username,
                label = "用户名",
                stableId = "discovery:username",
                focusScope = focusScope,
                focusMemory = focusMemory,
                onValueChange = { username = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        item(key = "discovery-password") {
            TvSettingsTextField(
                value = password,
                label = if (connection.hasPassword) "密码（留空则保持不变）" else "密码",
                stableId = "discovery:password",
                focusScope = focusScope,
                focusMemory = focusMemory,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                secret = true,
            )
        }
        item(key = "discovery-save") {
            TvSettingRow(
                title = "保存站点配置",
                value = "",
                stableId = "discovery:save",
                focusMemory = focusMemory,
                onClick = {
                    prefs.save(endpoint, username, password)
                    password = ""
                    status = "站点配置已保存"
                },
                icon = AppIcons.Check,
                focusScope = focusScope,
                navigationRequester = navigationRequester,
            )
        }
        if (connection.hasPassword) {
            item(key = "discovery-clear-password") {
                TvSettingRow(
                    title = "清除已保存的密码",
                    value = "",
                    stableId = "discovery:clear-password",
                    focusMemory = focusMemory,
                    onClick = {
                        prefs.clearPassword()
                        status = "站点密码已清除"
                    },
                    icon = AppIcons.Close,
                    focusScope = focusScope,
                    navigationRequester = navigationRequester,
                )
            }
        }

        item(key = "discovery-section-pan123") { TvSettingsSectionTitle("123 转存") }
        item(key = "discovery-phone") {
            TvSettingsTextField(
                value = phone,
                label = "账号",
                stableId = "discovery:phone",
                focusScope = focusScope,
                focusMemory = focusMemory,
                onValueChange = { phone = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        item(key = "discovery-token") {
            TvSettingsTextField(
                value = token,
                label = if (pan123.hasToken) "令牌（留空则保持不变）" else "令牌",
                stableId = "discovery:token",
                focusScope = focusScope,
                focusMemory = focusMemory,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                secret = true,
            )
        }
        item(key = "discovery-save-pan123") {
            TvSettingRow(
                title = "保存 123 授权",
                value = if (pan123.hasToken) "已授权" else "未授权",
                stableId = "discovery:save-pan123",
                focusMemory = focusMemory,
                onClick = {
                    status =
                        runCatching { prefs.savePan123Authorization(phone, token) }
                            .fold(
                                onSuccess = {
                                    token = ""
                                    "123 授权已保存"
                                },
                                onFailure = { it.message ?: "账号和令牌不能为空" },
                            )
                },
                icon = AppIcons.Cloud,
                focusScope = focusScope,
                enabled = phone.isNotBlank() && token.isNotBlank(),
                navigationRequester = navigationRequester,
            )
        }
        if (pan123.hasToken) {
            item(key = "discovery-clear-pan123") {
                TvSettingRow(
                    title = "撤销 123 授权",
                    value = "",
                    stableId = "discovery:clear-pan123",
                    focusMemory = focusMemory,
                    onClick = {
                        prefs.clearPan123Authorization()
                        status = "123 授权已撤销"
                    },
                    icon = AppIcons.Close,
                    focusScope = focusScope,
                    navigationRequester = navigationRequester,
                )
            }
        }
        item(key = "discovery-note") {
            TvSettingsNote("密码与令牌保存在系统加密存储里，不会写入诊断日志或云端同步。")
        }
    }
}
