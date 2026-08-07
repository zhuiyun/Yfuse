package com.yfuse.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.flatGlass as glass
import com.yfuse.core.designsystem.formDivider
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.sc
import com.yfuse.feature.servers.ServersIntent
import com.yfuse.feature.servers.ServersState

/**
 * 添加服务器.
 *
 * This used to be a four-step full-screen wizard reached by a route push, which is a
 * lot of ceremony for "type an address and sign in" — and it buried the LAN scan two
 * steps deep. Everything now lives in one modal: discovered servers on top for the
 * common case, the manual form below for the rest.
 */
@Composable
fun AddServerDialog(
    state: ServersState,
    onIntent: (ServersIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val form = state.form
    val editing = state.editingServerId != null

    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = if (editing) "编辑服务器" else "添加服务器",
            subtitle = if (editing) {
                "名称可直接修改；连接信息变更后需重新登录"
            } else {
                "连接你自己的 Emby 服务器"
            },
            onClose = onDismiss,
        )

        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FieldLabel("局域网发现") {
                Row(
                    Modifier
                        .pressable(enabled = !state.scanning) {
                            onIntent(ServersIntent.Scan)
                        }
                        .glass(GlassShapes.thumb, palette.card2, palette.border)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.scanning) {
                        CircularProgressIndicator(
                            Modifier.size(10.dp),
                            color = Brand.Primary,
                            strokeWidth = 1.5.dp,
                        )
                    }
                    Text(
                        if (state.scanning) "扫描中" else "扫描",
                        style = mr(10.5f, 600),
                        color = Brand.Primary,
                    )
                }
            }

            when {
                state.discovered.isNotEmpty() -> state.discovered.forEach { server ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .pressable { onIntent(ServersIntent.SelectDiscovered(server)) }
                            .background(palette.card2)
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .background(Brand.Primary, RoundedCornerShape(9.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                server.name.take(1).uppercase(),
                                style = mr(11f, 700),
                                color = Color.White,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                server.name,
                                style = sc(12f, 700),
                                color = palette.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                server.address,
                                style = mr(10f, 400),
                                color = palette.sub2,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            AppIcons.ChevronRight,
                            null,
                            tint = palette.sub2,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }

                !state.scanning -> Text(
                    "点击扫描查找同一网络下的服务器，或在下方手动填写。",
                    style = mr(10.5f, 400, lineHeight = 10.5f * 1.6f),
                    color = palette.hint,
                )
            }

            Spacer(Modifier.height(4.dp))
            FieldLabel("服务器信息")
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(palette.card2),
            ) {
                FormInput(
                    label = "显示名称",
                    value = form.serverName,
                    placeholder = if (editing) "输入服务器名称" else "留空使用服务器名称",
                    enabled = !form.submitting,
                    divider = true,
                ) { onIntent(ServersIntent.ServerNameChanged(it)) }
                FormRow(label = "协议", divider = true, labelBottomPadding = 6.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ProtocolSegment("HTTPS", form.https, Modifier.weight(1f)) {
                            onIntent(ServersIntent.ProtocolChanged(true))
                        }
                        ProtocolSegment("HTTP", !form.https, Modifier.weight(1f)) {
                            onIntent(ServersIntent.ProtocolChanged(false))
                        }
                    }
                }
                FormInput(
                    label = "地址",
                    value = form.host,
                    placeholder = "media.example.com",
                    enabled = !form.submitting,
                    keyboardType = KeyboardType.Uri,
                    divider = true,
                ) { onIntent(ServersIntent.HostChanged(it)) }
                FormInput(
                    label = "端口",
                    value = form.port,
                    enabled = !form.submitting,
                    keyboardType = KeyboardType.Number,
                    divider = false,
                ) { onIntent(ServersIntent.PortChanged(it)) }
            }

            Spacer(Modifier.height(4.dp))
            FieldLabel("账号")
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(palette.card2),
            ) {
                FormInput(
                    label = "用户名",
                    value = form.username,
                    placeholder = "输入用户名",
                    enabled = !form.submitting,
                    divider = true,
                ) { onIntent(ServersIntent.UsernameChanged(it)) }
                FormInput(
                    label = "密码",
                    value = form.password,
                    placeholder = if (editing) "仅修改名称时无需填写" else "留空表示无密码",
                    enabled = !form.submitting,
                    password = true,
                    divider = false,
                ) { onIntent(ServersIntent.PasswordChanged(it)) }
            }

            // Picking a discovered server loads its public users; offer them as one tap
            // instead of making the name be typed from memory.
            if (state.publicUsers.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.publicUsers
                        .map { it.Name }
                        .filter(String::isNotBlank)
                        .take(4)
                        .forEach { name ->
                            val selected = name == form.username
                            Text(
                                name,
                                style = sc(11f, if (selected) 700 else 500),
                                color = if (selected) Brand.Primary else palette.body,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .pressable { onIntent(ServersIntent.SelectPublicUser(name)) }
                                    .glass(
                                        shape = GlassShapes.thumb,
                                        fill = if (selected) {
                                            Brand.Primary.copy(alpha = 0.13f)
                                        } else {
                                            palette.card2
                                        },
                                        border = if (selected) {
                                            Brand.Primary.copy(alpha = 0.28f)
                                        } else {
                                            palette.border
                                        },
                                    )
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                }
            }

        }

        // Outside the scrolling form, immediately above the button that produced it. As the
        // form's last child it was drawn past the bottom of a 400dp scroll box that the
        // fields already overflow, so a failed connection looked like the button simply
        // stopped spinning — the one moment the dialog has something to say and it was the
        // one thing the user could not see.
        if (form.error != null) {
            Text(
                form.error,
                style = mr(10.5f, 500),
                color = Brand.Danger,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }

        OverlayButton(
            label = if (editing) "保存修改" else "连接到服务器",
            onClick = { onIntent(ServersIntent.Submit) },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            tone = OverlayButtonTone.Primary,
            enabled = form.canSubmit && (!editing || form.serverName.isNotBlank()),
            loading = form.submitting,
        )
    }
}

/** Group label, optionally with a trailing control on the same baseline. */
@Composable
private fun FieldLabel(text: String, trailing: @Composable (() -> Unit)? = null) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = mr(10.5f, 600).copy(letterSpacing = 0.4.sp),
            color = palette.sub2,
        )
        trailing?.invoke()
    }
}

/** Form row — `padding:11px 14px`, hairline `rgba(0,0,0,.06)` between rows. */
@Composable
private fun FormRow(
    label: String,
    divider: Boolean,
    labelBottomPadding: androidx.compose.ui.unit.Dp = 3.dp,
    content: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
        Text(
            label,
            style = mr(9.5f, 400),
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
    divider: Boolean,
    enabled: Boolean = true,
    placeholder: String? = null,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit,
) {
    val palette = LocalPalette.current
    FormRow(label = label, divider = divider) {
        Box(contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty() && placeholder != null) {
                Text(placeholder, style = mr(12.5f, 500), color = palette.hint)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = mr(12.5f, 500).copy(color = palette.text),
                cursorBrush = SolidColor(Brand.Primary),
                visualTransformation = if (password) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
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
                fill = if (selected) Brand.Primary.copy(alpha = 0.13f) else palette.card3,
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
