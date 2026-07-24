package com.yfuse.feature.servers

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.formDivider
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.sc
import com.yfuse.core.designsystem.shadow

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
                    modifier = Modifier.size(16.dp).clickable(onClick = component.onBack),
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
                    .shadow(Shadows.primaryButton, shape)
                    .background(
                        if (form.canSubmit) Brand.Primary else Brand.Primary.copy(alpha = 0.45f),
                        shape,
                    )
                    .clickable(enabled = form.canSubmit) { store.accept(ServersIntent.Submit) }
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
            .background(
                if (selected) Brand.Primary.copy(alpha = 0.12f) else Color.Transparent,
                RoundedCornerShape(9.dp),
            )
            .clickable(onClick = onClick)
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
