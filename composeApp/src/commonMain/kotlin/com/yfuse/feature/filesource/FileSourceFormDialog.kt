package com.yfuse.feature.filesource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.DialogPresence
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.liveStatus
import com.yfuse.core.filesource.FileSourceAddress
import com.yfuse.core.filesource.FileSourceDraft
import com.yfuse.core.filesource.FileSourceKind
import com.yfuse.core.filesource.defaultPort
import com.yfuse.core.filesource.resolveAddress
import com.yfuse.core.filesource.withPastedAddress
import com.yfuse.feature.servers.ServerFormInput
import com.yfuse.feature.servers.ServerFormRow
import com.yfuse.feature.servers.ServerProtocolSegment
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * The 文件来源 form, wherever it was opened from — the servers grid or a share whose login was
 * refused while browsing it. Held through its exit, as 添加服务器 is, so a save that succeeds does
 * not make the panel vanish in one frame.
 */
@Composable
internal fun FileSourceModals(controller: FileSourcesController) {
    val form by controller.form.collectAsState()
    DialogPresence(form) { shown ->
        FileSourceFormDialog(state = shown, controller = controller)
    }
}

/**
 * 添加文件来源 / 编辑文件来源: one panel, the same rows and fields as 添加服务器 so the two forms
 * read as one family. The address is tested — a folder listing with the login given — before
 * anything is saved, so a saved source is one that opened at least once.
 */
@Composable
private fun FileSourceFormDialog(
    state: FileSourceFormState,
    controller: FileSourcesController,
) {
    val palette = LocalPalette.current
    val draft = state.draft
    val connect = rememberFileSourceConnection(controller::showNotice)
    val openedDraft = remember(state.editingId) { draft }
    val holdsInput = draft != openedDraft
    var confirmDiscard by remember { mutableStateOf(false) }
    val address = draft.resolveAddress()
    val submit = {
        if (!state.submitting) {
            val origin = (address as? FileSourceAddress.Valid)?.origin
            if (origin == null) controller.submit() else connect(origin, controller::submit)
        }
    }

    GlassDialog(
        onDismiss = controller::dismissForm,
        scrollable = false,
        dragToDismiss = !holdsInput,
        confirmDismiss = {
            if (holdsInput) confirmDiscard = true
            !holdsInput
        },
    ) {
        OverlayHeader(
            title = if (state.editing) "编辑文件来源" else "添加文件来源",
            subtitle =
                if (state.editing) {
                    "改名直接保存；地址或账号变了会先重新连接"
                } else {
                    "WebDAV、SMB 共享，或经 Alist / OpenList 接入的网盘"
                },
            onClose = controller::dismissForm,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FormLabel("来源信息")
            Column(Modifier.fillMaxWidth().glass(AppShapes.card, palette.card2, palette.border)) {
                ServerFormRow(label = "类型", divider = true, labelBottomPadding = 6.dp) {
                    Row(modifier = Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FileSourceKind.entries.forEach { kind ->
                            ServerProtocolSegment(
                                label = kind.segmentLabel,
                                selected = draft.kind == kind,
                                modifier = Modifier.weight(1f),
                            ) { controller.editDraft { it.copy(kind = kind) } }
                        }
                    }
                }
                if (draft.kind != FileSourceKind.Smb) {
                    ServerFormRow(label = "协议", divider = true, labelBottomPadding = 6.dp) {
                        Row(modifier = Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ServerProtocolSegment("HTTPS", draft.https, Modifier.weight(1f)) {
                                controller.editDraft { it.copy(https = true) }
                            }
                            ServerProtocolSegment("HTTP", !draft.https, Modifier.weight(1f)) {
                                controller.editDraft { it.copy(https = false) }
                            }
                        }
                    }
                }
                ServerFormInput(
                    label = "地址",
                    value = draft.host,
                    placeholder =
                        if (draft.kind ==
                            FileSourceKind.Smb
                        ) {
                            "192.168.1.2 或 nas.local"
                        } else {
                            "nas.local，也可粘贴完整链接"
                        },
                    enabled = !state.submitting,
                    keyboardType = KeyboardType.Uri,
                    divider = true,
                ) { host -> controller.editDraft { it.copy(host = host).withPastedAddressFields() } }
                ServerFormInput(
                    label = "端口",
                    value = draft.port,
                    placeholder = "默认 ${draft.kind.defaultPort(draft.https)}",
                    enabled = !state.submitting,
                    keyboardType = KeyboardType.Number,
                    divider = true,
                ) { port -> controller.editDraft { it.copy(port = port.filter(Char::isDigit).take(5)) } }
                ServerFormInput(
                    label = if (draft.kind == FileSourceKind.Smb) "共享与路径" else "路径",
                    value = draft.path,
                    placeholder = draft.kind.pathPlaceholder,
                    enabled = !state.submitting,
                    divider = true,
                ) { path -> controller.editDraft { it.copy(path = path) } }
                ServerFormInput(
                    label = "显示名称",
                    value = draft.name,
                    placeholder = "留空按地址命名",
                    enabled = !state.submitting,
                    divider = false,
                ) { name -> controller.editDraft { it.copy(name = name) } }
            }
            Spacer(Modifier.height(4.dp))
            FormLabel("账号")
            Column(Modifier.fillMaxWidth().glass(AppShapes.card, palette.card2, palette.border)) {
                ServerFormInput(
                    label = "用户名",
                    value = draft.username,
                    placeholder = if (draft.kind == FileSourceKind.Smb) "留空以访客身份连接" else "输入用户名",
                    enabled = !state.submitting,
                    divider = true,
                    autofillType = ContentType.Username,
                ) { username -> controller.editDraft { it.copy(username = username) } }
                ServerFormInput(
                    label = "密码",
                    value = draft.password,
                    placeholder = if (state.passwordStored) "不修改则留空" else "输入密码",
                    enabled = !state.submitting,
                    password = true,
                    divider = false,
                    autofillType = ContentType.Password,
                    onSubmit = submit,
                ) { password -> controller.editDraft { it.copy(password = password) } }
            }
            Text(
                draft.hint(),
                style = AppTypography.caption.regular.copy(lineHeight = 16.8.sp),
                color = palette.hint,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        // Outside the scrolling fields, right above the button, so a failed connection is read
        // where the eye already is — the lesson 添加服务器 learned the hard way.
        state.error?.let { error ->
            Text(
                error,
                style = AppTypography.caption.medium,
                color = palette.error,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).liveStatus(assertive = true),
            )
        }

        OverlayButton(
            label = if (state.editing) "保存修改" else "连接并添加",
            onClick = submit,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            tone = OverlayButtonTone.Primary,
            enabled = draft.host.isNotBlank(),
            loading = state.submitting,
        )

        if (confirmDiscard) {
            ConfirmDialog(
                title = "放弃更改？",
                message = if (state.editing) "对这个文件来源的修改还没有保存。" else "已填写的文件来源还没有保存。",
                confirmLabel = "放弃",
                dismissLabel = "继续编辑",
                destructive = true,
                onConfirm = {
                    confirmDiscard = false
                    controller.dismissForm()
                },
                onDismiss = { confirmDiscard = false },
            )
        }
    }
}

@Composable
private fun FormLabel(text: String) {
    Text(
        text,
        style = AppTypography.caption.strong.copy(letterSpacing = 0.4.sp),
        color = LocalPalette.current.sub2,
    )
}

/**
 * A whole address pasted into 地址 is split into the fields at once, so what is shown is what will
 * be saved — scheme, port, path and login each in its own row.
 */
private fun FileSourceDraft.withPastedAddressFields(): FileSourceDraft =
    if ("://" in
        host
    ) {
        withPastedAddress()
    } else {
        this
    }

private val FileSourceKind.segmentLabel: String
    get() =
        when (this) {
            FileSourceKind.WebDav -> "WebDAV"
            FileSourceKind.Smb -> "SMB"
            FileSourceKind.Alist -> "Alist"
        }

private val FileSourceKind.pathPlaceholder: String
    get() =
        when (this) {
            FileSourceKind.WebDav -> "可选，如 /dav 或 /webdav"
            FileSourceKind.Smb -> "共享名/文件夹，留空列出全部共享"
            FileSourceKind.Alist -> "可选，如 /阿里云盘；默认 /dav"
        }

/** One line under the form that says what this kind needs, or what a choice costs. */
private fun FileSourceDraft.hint(): String =
    when {
        kind == FileSourceKind.Alist ->
            "在 Alist / OpenList 后台为该用户开启 WebDAV 读取权限；网盘只经它的 /dav 接入，不直连网盘接口。"
        kind == FileSourceKind.Smb ->
            "支持 SMB2 与 SMB3，Windows 共享、群晖与威联通 NAS 均可。域账号可写作 WORKGROUP\\用户名。"
        !https -> "HTTP 会以明文发送密码，建议只在家里的局域网使用。"
        else -> "群晖、威联通、Nextcloud 与 rclone 等 WebDAV 服务均可。"
    }
