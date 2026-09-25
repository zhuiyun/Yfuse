package com.yfuse.feature.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.formDivider
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.designsystem.ThemeText as Text

/*
 * Shared with `feature/profile/AddServerDialog.kt` — 添加/编辑服务器's modal — and the old
 * full-page manual-entry route (`ServersScreen`, removed once the 服务器 tab moved to a grid
 * with this modal as its only add/edit surface), which had each grown their own copy of the
 * same row, text field and protocol pill. The two had drifted: only the full-page form's text
 * field gained a password show/hide toggle and IME next/done plus focus-advance (`imeAction` +
 * `KeyboardActions`) while the modal's stayed on the default IME action with no way to reveal
 * a password. Both now get all three.
 */

/** Form row — label above content, optional hairline divider below. */
@Composable
internal fun ServerFormRow(
    label: String,
    divider: Boolean,
    labelBottomPadding: Dp = 3.dp,
    content: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
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

/**
 * A [ServerFormRow] with a single-line text input. Password fields get a "显示/隐藏" toggle
 * next to the field rather than a permanently-obscured value with no way to check it, and
 * every field advances focus to the next one on IME "next" so a five-field form does not
 * need the keyboard dismissed and reopened between each.
 *
 * A password field also gets a password keyboard with autocorrect off: on a plain text keyboard
 * the IME suggested, corrected, and could learn the server's password or Plex token as a word.
 */
@Composable
internal fun ServerFormInput(
    label: String,
    value: String,
    divider: Boolean,
    enabled: Boolean = true,
    placeholder: String? = null,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    /** What a password manager may fill in here; a password keyboard already implies a password. */
    autofillType: ContentType? = null,
    /** The keyboard's 完成 on this field sends the form, as the button would, instead of only closing. */
    onSubmit: (() -> Unit)? = null,
    onValueChange: (String) -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val focusManager = LocalFocusManager.current
    var revealPassword by rememberSaveable { mutableStateOf(false) }
    ServerFormRow(label = label, divider = divider) {
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
                    visualTransformation =
                        if (password && !revealPassword) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                    keyboardOptions =
                        KeyboardOptions(
                            autoCorrectEnabled = if (password) false else null,
                            keyboardType = if (password) secretKeyboardType(keyboardType) else keyboardType,
                            imeAction = if (password) ImeAction.Done else ImeAction.Next,
                        ),
                    keyboardActions =
                        KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) },
                            onDone = {
                                focusManager.clearFocus()
                                onSubmit?.invoke()
                            },
                        ),
                    modifier =
                        Modifier
                            .weight(1f)
                            .semantics {
                                contentDescription = label
                                if (autofillType != null) contentType = autofillType
                            },
                )
                if (password) {
                    Text(
                        if (revealPassword) "隐藏" else "显示",
                        style = AppTypography.caption.strong,
                        color = accent.accent,
                        modifier =
                            Modifier
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

/** The keyboard for a secret: one that neither suggests nor learns what is typed — a PIN stays numeric. */
private fun secretKeyboardType(requested: KeyboardType): KeyboardType =
    if (requested == KeyboardType.Number) KeyboardType.NumberPassword else KeyboardType.Password

/**
 * Protocol/provider segment — a `RadioButton`-role pill inside a [androidx.compose.foundation.selection.selectableGroup].
 */
@Composable
internal fun ServerProtocolSegment(
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
                fill = if (selected) accent.container else palette.card2,
                border =
                    if (selected) {
                        accent.border
                    } else {
                        palette.border.copy(alpha = 0.55f)
                    },
            ).padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = if (selected) AppTypography.caption.strong else AppTypography.caption.medium,
            color = if (selected) accent.accent else palette.sub2,
        )
    }
}

/** [ServerProtocolSegment] keyed off which [MediaServerKind] is currently selected. */
@Composable
internal fun ServerProviderSegment(
    label: String,
    kind: MediaServerKind,
    selectedKind: MediaServerKind,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    ServerProtocolSegment(
        label = label,
        selected = kind == selectedKind,
        modifier = modifier,
        onClick = onClick,
    )
}
