package com.yfuse.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.DEFAULT_123_CHANNELS
import com.yfuse.core.data.TgtoDirectoryItem
import com.yfuse.core.data.TgtoEmbyUpdate
import com.yfuse.core.data.TgtoMediaPreferences
import com.yfuse.core.data.TgtoMediaRepository
import com.yfuse.core.data.TgtoSettings
import com.yfuse.core.data.TgtoSettingsUpdate
import com.yfuse.core.data.TgtoTarget
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.Semantic
import com.yfuse.core.designsystem.flatGlass
import com.yfuse.core.designsystem.pressable
import kotlinx.coroutines.launch

@Composable
internal fun MediaDiscoverySettingsScreen(
    repository: TgtoMediaRepository,
    preferences: TgtoMediaPreferences,
    onBack: () -> Unit,
) {
    val connection by preferences.connection.collectAsState()
    val pan123Authorization by preferences.pan123Authorization.collectAsState()
    val scope = rememberCoroutineScope()
    var endpoint by remember(connection.endpoint) { mutableStateOf(connection.endpoint) }
    var username by remember(connection.username) { mutableStateOf(connection.username) }
    var password by remember { mutableStateOf("") }
    var embyEnabled by remember { mutableStateOf(true) }
    var embyUrl by remember { mutableStateOf("") }
    var embyApiKey by remember { mutableStateOf("") }
    var channelsText by remember { mutableStateOf(DEFAULT_123_CHANNELS.joinToString("\n")) }
    var settings by remember { mutableStateOf<TgtoSettings?>(null) }
    var target123 by remember { mutableStateOf(TgtoTarget()) }
    var directoryPickerOpen by remember { mutableStateOf(false) }
    var pan123Phone by remember(pan123Authorization.phone) { mutableStateOf(pan123Authorization.phone) }
    var pan123Password by remember { mutableStateOf("") }
    var pan123Checking by remember { mutableStateOf(false) }
    var pan123Authorizing by remember { mutableStateOf(false) }
    var pan123RefreshVersion by remember { mutableStateOf(0) }
    var pan123Feedback by remember { mutableStateOf<ConnectionFeedback?>(null) }
    var pageLoading by remember { mutableStateOf(false) }
    var tgTesting by remember { mutableStateOf(false) }
    var embyTesting by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var tgFeedback by remember { mutableStateOf<ConnectionFeedback?>(null) }
    var embyFeedback by remember { mutableStateOf<ConnectionFeedback?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun applySettings(value: TgtoSettings) {
        settings = value
        embyEnabled = value.mediaEmby.enabled
        embyUrl = value.mediaEmby.serverUrl
        target123 = value.mediaTransferTargets["123"] ?: TgtoTarget()
        channelsText =
            value.tgResourceChannels["123"]
                .orEmpty()
                .ifEmpty { DEFAULT_123_CHANNELS }
                .joinToString("\n")
    }

    LaunchedEffect(connection.hasPassword) {
        if (!connection.hasPassword) return@LaunchedEffect
        pageLoading = true
        repository
            .settings()
            .onSuccess(::applySettings)
            .onFailure { error = it.message ?: "读取影视发现配置失败" }
        pageLoading = false
    }

    LaunchedEffect(pan123Authorization.hasToken, pan123RefreshVersion) {
        if (!pan123Authorization.hasToken) {
            pan123Feedback = ConnectionFeedback("尚未登录 123 云盘", isError = true)
            return@LaunchedEffect
        }
        pan123Checking = true
        repository.list123Directories("0").fold(
            onSuccess = {
                pan123Feedback = ConnectionFeedback("授权可用 · 已读取 ${it.items.size} 个 123 根目录")
            },
            onFailure = {
                pan123Feedback = ConnectionFeedback(it.message ?: "123 授权不可用", isError = true)
            },
        )
        pan123Checking = false
    }

    SettingsPage(
        title = "影视发现",
        subtitle = "榜单、日历、123 资源与转存",
        onBack = onBack,
    ) {
        item {
            Section(title = "TgtoDrive 连接") {
                SettingsCard {
                    DiscoveryField(
                        "服务地址",
                        endpoint,
                        {
                            endpoint = it
                            tgFeedback = null
                        },
                        placeholder = "http://host:port",
                    )
                    SettingsDivider()
                    DiscoveryField(
                        "账号",
                        username,
                        {
                            username = it
                            tgFeedback = null
                        },
                    )
                    SettingsDivider()
                    DiscoveryField(
                        label = "密码",
                        value = password,
                        onValueChange = {
                            password = it
                            tgFeedback = null
                        },
                        placeholder = if (connection.hasPassword) "已安全保存；留空保持不变" else "输入登录密码",
                        secret = true,
                    )
                }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OverlayButton(
                        label = "测试 Tgto / TG 连接",
                        onClick = {
                            scope.launch {
                                tgTesting = true
                                tgFeedback = null
                                val effectivePassword = password.ifBlank { preferences.password() }
                                repository
                                    .testConnection(endpoint, username, effectivePassword)
                                    .onSuccess {
                                        tgFeedback = ConnectionFeedback("连接成功 · 已读取影视发现与 TG 频道配置")
                                    }.onFailure {
                                        tgFeedback = ConnectionFeedback(it.message ?: "TgtoDrive 连接失败", isError = true)
                                    }
                                tgTesting = false
                            }
                        },
                        enabled =
                            endpoint.isNotBlank() &&
                                username.isNotBlank() &&
                                (password.isNotBlank() || connection.hasPassword),
                        loading = tgTesting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    tgFeedback?.let { ConnectionFeedbackRow(it) }
                }
            }
        }
        item {
            Section(title = "Emby 媒体库") {
                SettingsCard {
                    SwitchRow(
                        title = "显示入库、连载和缺集状态",
                        checked = embyEnabled,
                        embedded = true,
                        onChange = {
                            embyEnabled = it
                            embyFeedback = null
                        },
                    )
                    SettingsDivider()
                    DiscoveryField(
                        "Emby 服务器地址",
                        embyUrl,
                        {
                            embyUrl = it
                            embyFeedback = null
                        },
                        placeholder = "https://emby.example.com",
                    )
                    SettingsDivider()
                    DiscoveryField(
                        label = "Emby API Key",
                        value = embyApiKey,
                        onValueChange = {
                            embyApiKey = it
                            embyFeedback = null
                        },
                        placeholder =
                            if (settings?.mediaEmby?.apiKeyConfigured == true) {
                                "已安全保存在服务端；留空保持不变"
                            } else {
                                "输入 Emby API Key"
                            },
                        secret = true,
                    )
                }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OverlayButton(
                        label = "测试 Emby 连接",
                        onClick = {
                            scope.launch {
                                embyTesting = true
                                embyFeedback = null
                                repository
                                    .testEmby(TgtoEmbyUpdate(embyEnabled, embyUrl.trim(), embyApiKey.trim()))
                                    .onSuccess {
                                        embyFeedback = ConnectionFeedback("连接成功 · 已识别 ${it.total} 个电影或剧集项目")
                                    }.onFailure {
                                        embyFeedback = ConnectionFeedback(it.message ?: "Emby 连接失败", isError = true)
                                    }
                                embyTesting = false
                            }
                        },
                        enabled = connection.hasPassword && embyUrl.isNotBlank(),
                        loading = embyTesting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    embyFeedback?.let { ConnectionFeedbackRow(it) }
                }
            }
        }
        item {
            Section(title = "123 公开频道") {
                SettingsCard {
                    DiscoveryField(
                        label = "每行一个频道",
                        value = channelsText,
                        onValueChange = { channelsText = it },
                        placeholder = "https://t.me/channel 或 @channel",
                        multiline = true,
                    )
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal, vertical = 8.dp)) {
                    OverlayButton(
                        label = "恢复默认频道",
                        onClick = { channelsText = DEFAULT_123_CHANNELS.joinToString("\n") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        item {
            Section(title = "123 授权") {
                SettingsCard {
                    SettingRow(
                        title = "授权状态",
                        value =
                            when {
                                pan123Checking -> "检查中"
                                pan123Feedback?.isError == false -> "可用"
                                pan123Authorization.hasToken -> "已保存"
                                else -> "需要登录"
                            },
                        embedded = true,
                    )
                    SettingsDivider()
                    DiscoveryField(
                        label = "123 登录手机号",
                        value = pan123Phone,
                        onValueChange = {
                            pan123Phone = it
                            pan123Feedback = null
                        },
                        placeholder = "输入 123 云盘手机号",
                        keyboardType = KeyboardType.Phone,
                    )
                    SettingsDivider()
                    DiscoveryField(
                        label = "123 登录密码",
                        value = pan123Password,
                        onValueChange = {
                            pan123Password = it
                            pan123Feedback = null
                        },
                        placeholder = "输入 123 云盘登录密码",
                        secret = true,
                    )
                }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "登录令牌由 App 加密保存，重新打开 App 后会自动复用；密码不会保存。",
                        style = AppTypography.caption.regular,
                        color = LocalPalette.current.sub2,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OverlayButton(
                            label = "检查授权",
                            onClick = { pan123RefreshVersion += 1 },
                            enabled = pan123Authorization.hasToken && !pan123Authorizing,
                            loading = pan123Checking,
                            modifier = Modifier.weight(1f),
                        )
                        OverlayButton(
                            label = "保存并授权",
                            onClick = {
                                scope.launch {
                                    pan123Authorizing = true
                                    pan123Feedback = null
                                    repository.authorizePan123(pan123Phone, pan123Password).fold(
                                        onSuccess = {
                                            pan123Password = ""
                                            pan123Feedback =
                                                ConnectionFeedback("授权成功 · 已读取 ${it.items.size} 个 123 根目录")
                                            message = "123 授权已由 App 加密保存"
                                        },
                                        onFailure = {
                                            pan123Feedback =
                                                ConnectionFeedback(it.message ?: "123 授权失败", isError = true)
                                        },
                                    )
                                    pan123Authorizing = false
                                }
                            },
                            tone = OverlayButtonTone.Primary,
                            enabled =
                                pan123Phone.isNotBlank() &&
                                    pan123Password.isNotBlank() &&
                                    !pan123Checking,
                            loading = pan123Authorizing,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pan123Authorization.hasToken) {
                        OverlayButton(
                            label = "清除 123 授权",
                            onClick = {
                                repository.clearPan123Authorization()
                                pan123Password = ""
                                pan123Feedback = ConnectionFeedback("123 授权已清除", isError = true)
                            },
                            enabled = !pan123Checking && !pan123Authorizing,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    pan123Feedback?.let { ConnectionFeedbackRow(it) }
                }
            }
        }
        item {
            Section(title = "123 转存") {
                SettingsCard {
                    SettingRow(
                        title = "保存目录",
                        value =
                            if (target123.configured) {
                                target123.folderName.ifBlank { "目录 ID ${target123.folderId}" }
                            } else {
                                "尚未选择"
                            },
                        embedded = true,
                    )
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal, vertical = 8.dp)) {
                    OverlayButton(
                        label = if (target123.configured) "更换 123 保存目录" else "选择 123 保存目录",
                        onClick = { directoryPickerOpen = true },
                        enabled = pan123Authorization.hasToken,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (message != null || error != null) {
            item {
                val palette = LocalPalette.current
                Text(
                    text = error ?: message.orEmpty(),
                    style = AppTypography.body.regular,
                    color = if (error != null) palette.error else palette.sub,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal),
                )
            }
        }
        item {
            Box(Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal)) {
                OverlayButton(
                    label = "保存影视发现配置",
                    onClick = {
                        scope.launch {
                            saving = true
                            error = null
                            message = null
                            val effectivePassword = password.ifBlank { preferences.password() }
                            val normalizedChannels = normalize123Channels(channelsText)
                            val connectionResult = repository.testConnection(endpoint, username, effectivePassword)
                            if (connectionResult.isFailure) {
                                error = connectionResult.exceptionOrNull()?.message ?: "TgtoDrive 连接失败"
                                tgFeedback = ConnectionFeedback(error.orEmpty(), isError = true)
                                saving = false
                                return@launch
                            }
                            tgFeedback = ConnectionFeedback("连接成功 · 配置已验证")
                            preferences.save(endpoint, username, password)
                            repository
                                .saveSettings(
                                    TgtoSettingsUpdate(
                                        tgResourceChannels = mapOf("123" to normalizedChannels),
                                        mediaTransferTargets = mapOf("123" to target123),
                                        mediaEmby = TgtoEmbyUpdate(embyEnabled, embyUrl.trim(), embyApiKey.trim()),
                                    ),
                                ).onSuccess {
                                    applySettings(it)
                                    password = ""
                                    embyApiKey = ""
                                    message = "影视发现配置已保存"
                                }.onFailure { error = it.message ?: "保存失败" }
                            saving = false
                        }
                    },
                    tone = OverlayButtonTone.Primary,
                    enabled =
                        endpoint.isNotBlank() &&
                            username.isNotBlank() &&
                            (password.isNotBlank() || connection.hasPassword),
                    loading = saving || pageLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (directoryPickerOpen) {
        Directory123PickerDialog(
            repository = repository,
            onSelect = { selected ->
                target123 = TgtoTarget(configured = true, folderId = selected.id, folderName = selected.name)
                directoryPickerOpen = false
                message = "已选择 123 目录“${selected.name}”，保存配置后生效"
            },
            onDismiss = { directoryPickerOpen = false },
        )
    }
}

private data class ConnectionFeedback(
    val message: String,
    val isError: Boolean = false,
)

@Composable
private fun ConnectionFeedbackRow(feedback: ConnectionFeedback) {
    val palette = LocalPalette.current
    val tone = if (feedback.isError) palette.error else Semantic.Success
    Text(
        text = feedback.message,
        style = AppTypography.caption.strong,
        color = tone,
        modifier =
            Modifier
                .fillMaxWidth()
                .flatGlass(AppShapes.control, palette.card2, tone.copy(alpha = 0.42f))
                .padding(horizontal = 13.dp, vertical = 10.dp),
    )
}

@Composable
private fun Directory123PickerDialog(
    repository: TgtoMediaRepository,
    onSelect: (TgtoDirectoryItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val root = remember { TgtoDirectoryItem(id = "0", name = "根目录") }
    var stack by remember { mutableStateOf(listOf(root)) }
    var directories by remember { mutableStateOf<List<TgtoDirectoryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshVersion by remember { mutableStateOf(0) }
    val current = stack.last()
    val palette = LocalPalette.current

    LaunchedEffect(current.id, refreshVersion) {
        loading = true
        error = null
        repository.list123Directories(current.id).fold(
            onSuccess = { directories = it.items.sortedBy(TgtoDirectoryItem::name) },
            onFailure = {
                directories = emptyList()
                error = it.message ?: "读取 123 云盘目录失败"
            },
        )
        loading = false
    }

    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "选择 123 保存目录",
            subtitle = "逐级浏览云盘文件夹，选择后回到设置页保存",
            onClose = onDismiss,
        )
        Text(
            text = stack.joinToString(" / ") { it.name.ifBlank { it.id } },
            style = AppTypography.caption.strong,
            color = palette.sub,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .flatGlass(AppShapes.control, palette.card2, palette.border)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OverlayButton(
                label = "返回上一级",
                onClick = { stack = stack.dropLast(1) },
                enabled = stack.size > 1 && !loading,
                modifier = Modifier.weight(1f),
            )
            OverlayButton(
                label = "刷新",
                onClick = { refreshVersion += 1 },
                enabled = !loading,
                loading = loading,
                modifier = Modifier.weight(1f),
            )
        }
        error?.let { ConnectionFeedbackRow(ConnectionFeedback(it, isError = true)) }
        if (!loading && directories.isEmpty() && error == null) {
            Text(
                "当前目录没有子目录，可直接选择当前目录",
                style = AppTypography.body.regular,
                color = palette.sub2,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            )
        }
        directories.forEach { directory ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .flatGlass(AppShapes.control, palette.card2, palette.border)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .pressable { stack = stack + directory },
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        directory.name.ifBlank { "目录 ${directory.id}" },
                        style = AppTypography.body.strong,
                        color = palette.text,
                    )
                    Text(
                        "ID ${directory.id} · 点按进入",
                        style = AppTypography.caption.regular,
                        color = palette.sub2,
                    )
                }
                OverlayButton(label = "选择", onClick = { onSelect(directory) })
            }
        }
        Spacer(Modifier.height(12.dp))
        OverlayButton(
            label = "使用当前目录：${current.name.ifBlank { current.id }}",
            onClick = { onSelect(current) },
            tone = OverlayButtonTone.Primary,
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DiscoveryField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    secret: Boolean = false,
    multiline: Boolean = false,
    keyboardType: KeyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
) {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(label, style = AppTypography.caption.strong, color = palette.sub2)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = !multiline,
            minLines = if (multiline) 5 else 1,
            maxLines = if (multiline) 8 else 1,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = AppTypography.body.regular.copy(color = palette.text),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (multiline) 132.dp else 46.dp)
                    .flatGlass(AppShapes.control, palette.card3, palette.border)
                    .padding(horizontal = 13.dp, vertical = 12.dp),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                    if (value.isBlank()) Text(placeholder, style = AppTypography.body.regular, color = palette.hint)
                    inner()
                }
            },
        )
    }
}

internal fun normalize123Channels(raw: String): List<String> =
    raw
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .filter { value ->
            value.matches(Regex("^https://t\\.me/[A-Za-z0-9_]+/?$")) ||
                value.matches(Regex("^@[A-Za-z0-9_]+$"))
        }.map { it.trimEnd('/') }
        .distinct()
        .toList()
