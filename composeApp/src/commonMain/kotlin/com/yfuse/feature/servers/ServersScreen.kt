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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.OfficialNavDisplay
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.formDivider
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.semanticPrimaryButtonShadow
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget

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
    val accent = LocalAccentColors.current
    val primaryButtonShadow = semanticPrimaryButtonShadow()
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
                        .pressable(onClickLabel = "返回", onClick = component.onBack)
                        .touchTarget()
                        .size(36.dp)
                        .glass(
                            shape = CircleShape,
                            fill = palette.card,
                            border = palette.border,
                        )
                        .padding(10.dp),
                )
                Text("添加服务器", style = AppTypography.section.strong, color = palette.text, maxLines = 1)
            }
        }

        item {
            Column(Modifier.padding(horizontal = Dimens.pageHorizontal)) {
                Text(
                    "手动输入地址",
                    style = AppTypography.caption.strong.copy(letterSpacing = 0.5.sp),
                    color = palette.sub2,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                // `--pg-card` over 1px `--pg-border`, `radius:16px`, `padding:4px`.
                Column(Modifier.fillMaxWidth().glass(GlassShapes.card).padding(4.dp)) {
                    FormField(label = "协议", divider = true) {
                        Row(
                            modifier = Modifier.selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
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

                if (!form.https) {
                    Spacer(Modifier.height(10.dp))
                    Column(
                        Modifier.fillMaxWidth().glass(
                            AppShapes.control,
                            Color(0xFFFFA24A).copy(alpha = 0.11f),
                            Color(0xFFFFA24A).copy(alpha = 0.30f),
                        ).padding(12.dp),
                    ) {
                        Text(
                            "⚠ HTTP 连接未加密",
                            style = AppTypography.body.strong,
                            color = Color(0xFFD77922),
                        )
                        Text(
                            "仅建议在可信局域网使用；公网服务器优先使用 HTTPS。",
                            style = AppTypography.caption.regular,
                            color = palette.sub,
                        )
                    }
                }
                if (form.error != null) {
                    Spacer(Modifier.height(8.dp))
                    Column(
                        Modifier.fillMaxWidth().glass(
                            AppShapes.control,
                            palette.error.copy(alpha = 0.08f),
                            palette.error.copy(alpha = 0.26f),
                        ).padding(12.dp),
                    ) {
                        Text(
                            "连接失败",
                            style = AppTypography.body.strong,
                            color = palette.error,
                        )
                        Text(
                            "${form.error}。请检查地址、端口、协议和账号后重试。",
                            style = AppTypography.caption.regular,
                            color = palette.sub,
                        )
                    }
                }
            }
        }

        item {
            // `#3D64C9`, `radius:18px`, `padding:14px`, `700 14px`,
            // `0 10px 24px rgba(61,100,201,.3)`.
            val shape = AppShapes.control
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.pageHorizontal)
                    .pressable(enabled = form.canSubmit) { store.accept(ServersIntent.Submit) }
                    .shadow(primaryButtonShadow, shape)
                    .glass(
                        shape = shape,
                        fill = if (form.canSubmit) {
                            accent.accent
                        } else {
                            accent.container
                        },
                        border = accent.border.copy(alpha = if (form.canSubmit) 1f else 0.38f),
                    )
                    .padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (form.submitting) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = accent.onAccent,
                    )
                } else {
                    Text(
                        "连接到服务器",
                        style = AppTypography.body.strong,
                        color = if (form.canSubmit) accent.onAccent else accent.accent,
                    )
                }
            }
        }

        item {
            Text(
                "支持 HTTP / HTTPS · 登录后即可浏览媒体库",
                style = AppTypography.caption.regular.copy(lineHeight = 17.6.sp),
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
    val accent = LocalAccentColors.current
    val primaryButtonShadow = semanticPrimaryButtonShadow()
    var currentStep by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    var selectedUser by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }

    LaunchedEffect(currentStep) {
        if (currentStep == 1 && state.discovered.isEmpty() && !state.scanning) {
            onIntent(ServersIntent.Scan)
        }
    }

    fun back() {
        if (currentStep == 0) onBack() else currentStep--
    }

    OfficialNavDisplay(
        backStack = (0..currentStep).toList(),
        onBack = { currentStep-- },
        contentKey = { "server-onboarding-$it" },
        modifier = Modifier.fillMaxSize(),
    ) { step ->
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
                    .pressable(onClickLabel = "返回", onClick = ::back)
                    .touchTarget()
                    .size(34.dp)
                    .glass(AppShapes.control, palette.card, palette.border),
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
                                if (index == step) accent.accent else Color(0x4D788CB4),
                                AppShapes.track,
                            ),
                    )
                }
            }
            Text(
                "第 ${step + 1} / 4 步",
                style = AppTypography.caption.medium,
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
                        .shadow(primaryButtonShadow, AppShapes.sheet)
                        .background(
                            com.yfuse.core.designsystem.PrimaryGradient,
                            AppShapes.sheet,
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
                Text("欢迎使用", style = AppTypography.display.strong, color = palette.text)
                Spacer(Modifier.height(10.dp))
                Text(
                    "连接你自己的 Emby 服务器\n随时随地播放家中的影视收藏",
                    style = AppTypography.body.regular.copy(lineHeight = 21.5.sp),
                    color = palette.sub,
                    textAlign = TextAlign.Center,
                )
            }

            1 -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text("查找服务器", style = AppTypography.display.strong, color = palette.text)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "正在扫描同一局域网内的 Emby 服务器",
                        style = AppTypography.caption.regular,
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
                                    AppShapes.card,
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
                                    currentStep = 2
                                }
                                .glass(AppShapes.card, palette.card, palette.border)
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .background(
                                        com.yfuse.core.designsystem.PrimaryGradient,
                                        AppShapes.control,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    server.name.take(1),
                                    style = AppTypography.body.strong,
                                    color = Color.White,
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(server.name, style = AppTypography.body.strong, color = palette.text)
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    listOfNotNull(
                                        server.address,
                                        server.version?.let { "Emby $it" },
                                        when {
                                            server.address.startsWith("https://", ignoreCase = true) -> "HTTPS"
                                            server.address.startsWith("http://", ignoreCase = true) -> "HTTP"
                                            else -> "局域网"
                                        },
                                    ).joinToString(" · "),
                                    style = AppTypography.caption.regular,
                                    color = palette.sub2,
                                )
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
                        style = AppTypography.caption.regular,
                        color = palette.hint,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    )
                }
                Text(
                    "手动输入地址",
                    style = AppTypography.caption.strong,
                    color = accent.accent,
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
                    Text("登录", style = AppTypography.display.strong, color = palette.text)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "使用服务器上的 Emby 账号登录",
                        style = AppTypography.caption.regular,
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
                        Text(form.error, style = AppTypography.caption.medium, color = palette.error)
                    }
                }
            }

            else -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column {
                    Text("选择用户", style = AppTypography.display.strong, color = palette.text)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "每位用户有独立的观看记录与家长控制",
                        style = AppTypography.caption.regular,
                        color = palette.sub,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().selectableGroup(),
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
                                .pressable(role = Role.RadioButton) {
                                    selectedUser = index
                                    onIntent(ServersIntent.SelectPublicUser(name))
                                }
                                .semantics { selected = selectedUser == index }
                                .glass(
                                    shape = AppShapes.card,
                                    fill = if (selectedUser == index) {
                                        accent.container
                                    } else {
                                        palette.card2
                                    },
                                    border = if (selectedUser == index) {
                                        accent.border
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
                                Text(name.take(1), style = AppTypography.section.strong, color = Color.White)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                name,
                                style = AppTypography.caption.strong,
                                color = palette.text,
                                maxLines = 1,
                            )
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
                            currentStep = 1
                        }
                        1 -> onIntent(ServersIntent.Scan)
                        2 -> currentStep = 3
                        else -> onIntent(ServersIntent.Submit)
                    }
                }
                .glass(
                    shape = AppShapes.pill,
                    fill = if (enabled) accent.accent else accent.container,
                    border = accent.border.copy(alpha = if (enabled) 1f else 0.38f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (form.submitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = accent.onAccent,
                )
            } else {
                Text(
                    listOf("开始使用", "重新扫描", "登录", "进入媒体库")[step],
                    style = AppTypography.body.strong,
                    color = if (enabled) accent.onAccent else accent.accent,
                )
            }
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
    val focusManager = LocalFocusManager.current
    var revealPassword by rememberSaveable { mutableStateOf(false) }
    val accent = LocalAccentColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = AppTypography.caption.strong.copy(letterSpacing = 0.4.sp),
            color = palette.sub2,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .glass(AppShapes.control, palette.card, palette.border)
                .padding(horizontal = 15.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                Text(placeholder, style = AppTypography.body.regular, color = palette.hint)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = AppTypography.body.regular.copy(color = palette.text),
                    cursorBrush = SolidColor(accent.accent),
                    visualTransformation = if (password && !revealPassword) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = label },
                )
                if (password) {
                    Text(
                        if (revealPassword) "隐藏" else "显示",
                        style = AppTypography.caption.strong,
                        color = accent.accent,
                        modifier = Modifier
                            .pressable(
                                onClickLabel = if (revealPassword) "隐藏密码" else "显示密码",
                            ) { revealPassword = !revealPassword }
                            .touchTarget()
                            .padding(start = 8.dp),
                    )
                }
            }
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
            style = AppTypography.caption.regular,
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
    val focusManager = LocalFocusManager.current
    var revealPassword by rememberSaveable { mutableStateOf(false) }
    val accent = LocalAccentColors.current
    FormField(label = label, divider = divider, labelBottomPadding = 3.dp) {
        Box(contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty() && placeholder != null) {
                Text(placeholder, style = AppTypography.body.medium, color = palette.hint)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = AppTypography.body.medium.copy(color = palette.text),
                    cursorBrush = SolidColor(accent.accent),
                    visualTransformation = if (password && !revealPassword) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = if (password) ImeAction.Done else ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) },
                        onDone = { focusManager.clearFocus() },
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = label },
                )
                if (password) {
                    Text(
                        if (revealPassword) "隐藏" else "显示",
                        style = AppTypography.caption.strong,
                        color = accent.accent,
                        modifier = Modifier
                            .pressable(
                                onClickLabel = if (revealPassword) "隐藏密码" else "显示密码",
                            ) { revealPassword = !revealPassword }
                            .touchTarget()
                            .padding(start = 8.dp),
                    )
                }
            }
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
    val accent = LocalAccentColors.current
    Box(
        modifier
            .pressable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .touchTarget()
            .glass(
                shape = AppShapes.thumb,
                fill = if (selected) {
                    accent.container
                } else {
                    palette.card2
                },
                border = if (selected) {
                    accent.border
                } else {
                    palette.border.copy(alpha = 0.55f)
                },
            )
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = if (selected) AppTypography.caption.strong else AppTypography.caption.medium,
            color = if (selected) accent.accent else palette.sub2,
        )
    }
}
