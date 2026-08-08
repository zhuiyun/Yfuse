package com.yfuse.feature.servers

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.PlatformBackHandler
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.formDivider
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.sc
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.pressable

/**
 * 添加服务器 — `padding:52px 18px 24px; gap:20px`.
 *
 * The prototype's form has protocol / address / port only because it never signs in;
 * the username and password rows below reuse the same annotated row styling.
 */
@Composable
fun ServersScreen(component: ServersComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val store = component.store
    val form = state.form
    val palette = LocalPalette.current
    StatusBarIconStyle(darkIcons = !palette.isDark)
    var showOnboarding by rememberSaveable { mutableStateOf(true) }

    if (showOnboarding) {
        OnboardingScreen(
            state = state,
            form = form,
            onIntent = store::accept,
            onManual = { showOnboarding = false },
            onBack = component.onBack,
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(top = Dimens.contentTop, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            // Back chevron + title, `gap:12px`, title `800 19px`.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    AppIcons.ChevronLeft,
                    contentDescription = "返回",
                    tint = palette.sub,
                    modifier = Modifier
                        .size(36.dp)
                        .pressable(onClick = component.onBack)
                        .glass(
                            shape = CircleShape,
                            fill = palette.card,
                            border = palette.border,
                        )
                        .padding(10.dp),
                )
                Text("添加服务器", style = sc(19f, 800), color = palette.text, maxLines = 1)
            }
        }

        item {
            Column(Modifier.padding(horizontal = Dimens.pageHorizontal)) {
                Text(
                    "手动输入地址",
                    style = mr(11f, 600).copy(letterSpacing = 0.5.sp),
                    color = palette.sub2,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                // `--pg-card` over 1px `--pg-border`, `radius:16px`, `padding:4px`.
                Column(Modifier.fillMaxWidth().glass(GlassShapes.card).padding(4.dp)) {
                    FormField(label = "协议", divider = true) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ProtocolSegment(
                                label = "HTTPS",
                                selected = form.https,
                                modifier = Modifier.weight(1f),
                            ) { store.accept(ServersIntent.ProtocolChanged(true)) }
                            ProtocolSegment(
                                label = "HTTP",
                                selected = !form.https,
                                modifier = Modifier.weight(1f),
                            ) { store.accept(ServersIntent.ProtocolChanged(false)) }
                        }
                    }
                    FormInput(
                        label = "服务器地址",
                        value = form.host,
                        placeholder = "media.example.com",
                        enabled = !form.submitting,
                        keyboardType = KeyboardType.Uri,
                        divider = true,
                        onValueChange = { store.accept(ServersIntent.HostChanged(it)) },
                    )
                    FormInput(
                        label = "端口",
                        value = form.port,
                        enabled = !form.submitting,
                        keyboardType = KeyboardType.Number,
                        divider = true,
                        onValueChange = { store.accept(ServersIntent.PortChanged(it)) },
                    )
                    FormInput(
                        label = "用户名",
                        value = form.username,
                        enabled = !form.submitting,
                        divider = true,
                        onValueChange = { store.accept(ServersIntent.UsernameChanged(it)) },
                    )
                    FormInput(
                        label = "密码",
                        value = form.password,
                        enabled = !form.submitting,
                        password = true,
                        divider = false,
                        onValueChange = { store.accept(ServersIntent.PasswordChanged(it)) },
                    )
                }

                if (form.error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(form.error, style = mr(11f, 500), color = Brand.Danger)
                }
            }
        }

        item {
            // `#3D64C9`, `radius:18px`, `padding:14px`, `700 14px`,
            // `0 10px 24px rgba(61,100,201,.3)`.
            val shape = RoundedCornerShape(18.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.pageHorizontal)
                    .pressable(enabled = form.canSubmit) { store.accept(ServersIntent.Submit) }
                    .shadow(Shadows.primaryButton, shape)
                    .glass(
                        shape = shape,
                        fill = if (form.canSubmit) {
                            Brand.Primary.copy(alpha = 0.72f)
                        } else {
                            Brand.Primary.copy(alpha = 0.34f)
                        },
                        border = Color.White.copy(alpha = if (form.canSubmit) 0.36f else 0.20f),
                    )
                    .padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (form.submitting) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                } else {
                    Text("连接到服务器", style = sc(14f, 700), color = Color.White)
                }
            }
        }

        item {
            Text(
                "支持 HTTP / HTTPS · 登录后即可浏览媒体库",
                style = mr(11f, 400, lineHeight = 11f * 1.6f),
                color = palette.hint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal),
            )
        }
    }
}

@Composable
private fun OnboardingScreen(
    state: ServersState,
    form: LoginForm,
    onIntent: (ServersIntent) -> Unit,
    onManual: () -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalPalette.current
    var step by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    var selectedUser by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }

    PlatformBackHandler(enabled = step > 0) { step-- }

    LaunchedEffect(step) {
        if (step == 1 && state.discovered.isEmpty() && !state.scanning) {
            onIntent(ServersIntent.Scan)
        }
    }

    fun back() {
        if (step == 0) onBack() else step--
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(start = 24.dp, top = 20.dp, end = 24.dp, bottom = 30.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 30.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .pressable(onClick = ::back)
                    .glass(RoundedCornerShape(16.dp), palette.card, palette.border),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.ChevronLeft,
                    "返回",
                    tint = palette.text,
                    modifier = Modifier.size(15.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(4) { index ->
                    Box(
                        Modifier
                            .width(if (index == step) 18.dp else 6.dp)
                            .height(6.dp)
                            .background(
                                if (index == step) Brand.Primary else Color(0x4D788CB4),
                                RoundedCornerShape(3.dp),
                            ),
                    )
                }
            }
            Text(
                "第 ${step + 1} / 4 步",
                style = mr(10.5f, 500),
                color = palette.sub2,
            )
        }

        when (step) {
            0 -> Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier
                        .size(76.dp)
                        .shadow(Shadows.primaryButton, RoundedCornerShape(26.dp))
                        .background(
                            com.yfuse.core.designsystem.PrimaryGradient,
                            RoundedCornerShape(26.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.Server,
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Spacer(Modifier.height(18.dp))
                Text("欢迎使用", style = sc(25f, 800), color = palette.text)
                Spacer(Modifier.height(10.dp))
                Text(
                    "连接你自己的 Emby 服务器\n随时随地播放家中的影视收藏",
                    style = sc(12.5f, 400, lineHeight = 21.5f),
                    color = palette.sub,
                    textAlign = TextAlign.Center,
                )
            }

            1 -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text("查找服务器", style = sc(22f, 800), color = palette.text)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "正在扫描同一局域网内的 Emby 服务器",
                        style = sc(11.5f, 400),
                        color = palette.sub,
                    )
                }
                if (state.scanning) {
                    repeat(2) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .background(
                                    palette.card2,
                                    RoundedCornerShape(18.dp),
                                ),
                        )
                    }
                } else if (state.discovered.isNotEmpty()) {
                    state.discovered.forEach { server ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .pressable {
                                    onIntent(ServersIntent.SelectDiscovered(server))
                                    step = 2
                                }
                                .glass(RoundedCornerShape(18.dp), palette.card, palette.border)
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .background(
                                        com.yfuse.core.designsystem.PrimaryGradient,
                                        RoundedCornerShape(14.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    server.name.take(1),
                                    style = sc(14f, 700),
                                    color = Color.White,
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(server.name, style = sc(13f, 700), color = palette.text)
                                Spacer(Modifier.height(3.dp))
                                Text(server.address, style = mr(10.5f, 400), color = palette.sub2)
                            }
                            Icon(
                                AppIcons.ChevronRight,
                                null,
                                tint = palette.sub2,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                } else {
                    Text(
                        "没有发现服务器，可以手动输入地址",
                        style = sc(11.5f, 400),
                        color = palette.hint,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    )
                }
                Text(
                    "手动输入地址",
                    style = sc(11.5f, 600),
                    color = Brand.Primary,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .pressable(onClick = onManual)
                        .glass(
                            shape = GlassShapes.chip,
                            fill = palette.card2,
                            border = palette.border,
                        )
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                )
            }

            2 -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Column {
                    Text("登录", style = sc(22f, 800), color = palette.text)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "使用服务器上的 Emby 账号登录",
                        style = sc(11.5f, 400),
                        color = palette.sub,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OnboardInput(
                        label = "用户名",
                        value = form.username,
                        placeholder = "输入用户名",
                        onValueChange = { onIntent(ServersIntent.UsernameChanged(it)) },
                    )
                    OnboardInput(
                        label = "密码",
                        value = form.password,
                        placeholder = "输入密码",
                        password = true,
                        onValueChange = { onIntent(ServersIntent.PasswordChanged(it)) },
                    )
                    if (form.error != null) {
                        Text(form.error, style = sc(11f, 500), color = Brand.Danger)
                    }
                }
            }

            else -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column {
                    Text("选择用户", style = sc(22f, 800), color = palette.text)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "每位用户有独立的观看记录与家长控制",
                        style = sc(11.5f, 400),
                        color = palette.sub,
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val users = state.publicUsers
                        .map { it.Name }
                        .filter(String::isNotBlank)
                        .ifEmpty { listOf(form.username.ifBlank { "用户" }) }
                    users.forEachIndexed { index, name ->
                        Column(
                            Modifier
                                .weight(1f)
                                .pressable {
                                    selectedUser = index
                                    onIntent(ServersIntent.SelectPublicUser(name))
                                }
                                .glass(
                                    shape = RoundedCornerShape(20.dp),
                                    fill = if (selectedUser == index) {
                                        Brand.Primary.copy(alpha = 0.09f)
                                    } else {
                                        palette.card2
                                    },
                                    border = if (selectedUser == index) {
                                        Brand.Primary.copy(alpha = 0.28f)
                                    } else {
                                        palette.border
                                    },
                                )
                                .padding(horizontal = 6.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier
                                    .size(56.dp)
                                    .background(
                                        com.yfuse.core.designsystem.PrimaryGradient,
                                        CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(name.take(1), style = sc(19f, 700), color = Color.White)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(name, style = sc(11.5f, 600), color = palette.text, maxLines = 1)
                        }
                    }
                }
            }
        }

        val enabled = when (step) {
            2 -> form.username.isNotBlank()
            3 -> form.canSubmit
            else -> true
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .pressable(enabled = enabled) {
                    when (step) {
                        0 -> {
                            step = 1
                        }
                        1 -> onIntent(ServersIntent.Scan)
                        2 -> step = 3
                        else -> onIntent(ServersIntent.Submit)
                    }
                }
                .glass(
                    shape = RoundedCornerShape(25.dp),
                    fill = Brand.Primary.copy(alpha = if (enabled) 0.72f else 0.34f),
                    border = Color.White.copy(alpha = if (enabled) 0.36f else 0.20f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (form.submitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            } else {
                Text(
                    listOf("开始使用", "重新扫描", "登录", "进入媒体库")[step],
                    style = sc(13.5f, 700),
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun OnboardInput(
    label: String,
    value: String,
    placeholder: String,
    password: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = mr(10.5f, 600).copy(letterSpacing = 0.4.sp),
            color = palette.sub2,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .glass(RoundedCornerShape(16.dp), palette.card, palette.border)
                .padding(horizontal = 15.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                Text(placeholder, style = mr(13f, 400), color = palette.hint)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = mr(13f, 400).copy(color = palette.text),
                cursorBrush = SolidColor(Brand.Primary),
                visualTransformation = if (password) {
                    PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Form row — `padding:11px 14px`, hairline `rgba(0,0,0,.06)` between rows. */
@Composable
private fun FormField(
    label: String,
    divider: Boolean,
    labelBottomPadding: androidx.compose.ui.unit.Dp = 6.dp,
    content: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp)) {
        Text(
            label,
            style = mr(10f, 400),
            color = palette.sub2,
            modifier = Modifier.padding(bottom = labelBottomPadding),
        )
        content()
    }
    if (divider) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(formDivider()))
    }
}

/** Same row with a `500 13px Manrope` text input; label sits 3px above it. */
@Composable
private fun FormInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    divider: Boolean,
    enabled: Boolean = true,
    placeholder: String? = null,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val palette = LocalPalette.current
    FormField(label = label, divider = divider, labelBottomPadding = 3.dp) {
        Box(contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty() && placeholder != null) {
                Text(placeholder, style = mr(13f, 500), color = palette.hint)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = mr(13f, 500).copy(color = palette.text),
                cursorBrush = SolidColor(Brand.Primary),
                visualTransformation = if (password) {
                    PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Protocol segment — `padding:6px 0`, `radius:9px`; selected is
 * `700 11.5px Manrope` `#3D64C9` on `rgba(61,100,201,.12)`, otherwise `500` `--pg-sub2`.
 */
@Composable
private fun ProtocolSegment(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Box(
        modifier
            .pressable(onClick = onClick)
            .glass(
                shape = RoundedCornerShape(9.dp),
                fill = if (selected) {
                    Brand.Primary.copy(alpha = 0.13f)
                } else {
                    palette.card2
                },
                border = if (selected) {
                    Brand.Primary.copy(alpha = 0.24f)
                } else {
                    palette.border.copy(alpha = 0.55f)
                },
            )
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = mr(11.5f, if (selected) 700 else 500),
            color = if (selected) Brand.Primary else palette.sub2,
        )
    }
}
