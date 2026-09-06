package com.yfuse.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.DanmakuSource
import com.yfuse.core.data.SkipMode
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.data.activeOr
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.WatchAvatar
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.sync.WatchInvite
import com.yfuse.core.util.graphemeCount
import com.yfuse.core.util.takeGraphemes
import com.yfuse.core.util.takeGraphemesWithinUtf8Bytes
import com.yfuse.core.util.withoutControlCharacters
import com.yfuse.feature.watch.CopyableRoomCode
import com.yfuse.core.designsystem.flatGlass as glass

/**
 * 我的 — the option sheets.
 *
 * Every one of these is a form: a value, somewhere to type it, and two buttons. They were
 * living in `ProfileScreen.kt` alongside the page that opens them, which made a 2000-line
 * file where 40% of it was never on screen at the same time as the other 60%.
 */

@Composable
internal fun UserAgentDialog(
    current: String,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(current) { mutableStateOf(current) }
    val normalized = draft.trim()
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current

    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "自定义 User-Agent",
            subtitle = "应用于服务器 API 与视频取流请求；留空时使用应用默认值。",
            onClose = onDismiss,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .glass(AppShapes.control, palette.card2, palette.border)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Box(contentAlignment = Alignment.CenterStart) {
                if (draft.isBlank()) {
                    Text(
                        "例如：Yfuse/Android",
                        style = AppTypography.body.medium,
                        color = palette.hint,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { value ->
                        draft = value.replace("\r", "").replace("\n", "").take(512)
                    },
                    singleLine = true,
                    textStyle = AppTypography.body.medium.copy(color = palette.text),
                    cursorBrush = SolidColor(accent.accent),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "User-Agent" },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "修改后新发起的请求立即生效；正在播放的媒体需重新进入播放器。",
            style = AppTypography.caption.regular,
            color = palette.sub2,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (current.isNotBlank()) {
                OverlayButton(
                    label = "恢复默认",
                    onClick = onClear,
                    modifier = Modifier.weight(1f),
                    tone = OverlayButtonTone.Destructive,
                )
            } else {
                OverlayButton("取消", onDismiss, Modifier.weight(1f))
            }
            OverlayButton(
                label = "保存",
                onClick = { onSave(normalized) },
                modifier = Modifier.weight(1f),
                tone = OverlayButtonTone.Primary,
                enabled = normalized.isNotEmpty(),
            )
        }
    }
}

/**
 * 弹幕链接 — the list of servers, because one is rarely enough.
 *
 * Any of these can be a **弹幕服务器地址** (a dandanplay-compatible root, which the player
 * can search and match against) or a **模板链接** carrying `{id}` / `{title}` placeholders,
 * which resolves straight to one file per entry. The URL says which; nothing here has to be
 * declared.
 *
 * Two modes in one panel: the list, and the editor for one entry. A separate 添加 sheet
 * would be a second overlay over the first for a form with two fields in it.
 */
@Composable
internal fun DanmakuSourceDialog(
    sources: List<DanmakuSource>,
    activeSourceId: String?,
    onSelect: (String) -> Unit,
    onAdd: (String, String) -> Unit,
    onUpdate: (String, String, String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    // Non-null while one entry is being written. Its id is null for a new source.
    var draft by remember { mutableStateOf<DanmakuSourceDraft?>(null) }
    val active = sources.activeOr(activeSourceId)

    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = if (draft == null) "弹幕链接" else "编辑弹幕源",
            subtitle =
                if (draft == null) {
                    "可以配置多个，选中的那个负责搜索和匹配，播放器里也能切换。"
                } else {
                    "填服务器地址即可搜索；带 {id} 等占位符的链接按模板直接取。"
                },
            onClose = onDismiss,
        )

        val editing = draft
        if (editing == null) {
            Column(
                Modifier.fillMaxWidth().selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sources.forEach { source ->
                    DanmakuSourceRow(
                        source = source,
                        selected = source.id == active?.id,
                        onSelect = { onSelect(source.id) },
                        onEdit = {
                            draft = DanmakuSourceDraft(source.id, source.name, source.url)
                        },
                    )
                }
                if (sources.isEmpty()) {
                    Text(
                        "还没有弹幕源。",
                        style = AppTypography.caption.regular,
                        color = palette.sub2,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OverlayButton("完成", onDismiss, Modifier.weight(1f))
                OverlayButton(
                    label = "添加",
                    onClick = { draft = DanmakuSourceDraft(null, "", "") },
                    modifier = Modifier.weight(1f),
                    tone = OverlayButtonTone.Primary,
                )
            }
        } else {
            val normalizedUrl = editing.url.trim()
            val valid = normalizedUrl.startsWith("http://") || normalizedUrl.startsWith("https://")
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DanmakuField(
                    value = editing.name,
                    placeholder = "名称，例如 夏天",
                    keyboardType = KeyboardType.Text,
                    onValueChange = { draft = editing.copy(name = it) },
                )
                DanmakuField(
                    value = editing.url,
                    placeholder = "https://danmaku.example.com",
                    keyboardType = KeyboardType.Uri,
                    onValueChange = { draft = editing.copy(url = it) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "占位符：{id} 媒体 ID · {title} 标题 · {season} 季号 · {episode} 集号 · {serverId} 服务器 ID",
                style = AppTypography.caption.regular,
                color = palette.sub2,
            )
            if (normalizedUrl.isNotEmpty() && !valid) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "链接必须以 http:// 或 https:// 开头",
                    style = AppTypography.caption.medium,
                    color = palette.error,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (editing.id == null) {
                    OverlayButton("取消", { draft = null }, Modifier.weight(1f))
                } else {
                    OverlayButton(
                        label = "删除",
                        onClick = {
                            onRemove(editing.id)
                            draft = null
                        },
                        modifier = Modifier.weight(1f),
                        tone = OverlayButtonTone.Destructive,
                    )
                }
                OverlayButton(
                    label = "保存",
                    onClick = {
                        if (editing.id == null) {
                            onAdd(editing.name, normalizedUrl)
                        } else {
                            onUpdate(editing.id, editing.name, normalizedUrl)
                        }
                        draft = null
                    },
                    modifier = Modifier.weight(1f),
                    tone = OverlayButtonTone.Primary,
                    enabled = valid,
                )
            }
        }
    }
}

/**
 * 弹幕屏蔽词 — the lines never to draw.
 *
 * Substring matching, case-insensitive, on the comment text. Blunt on purpose: anything
 * cleverer needs the user to describe a pattern, and the thing being blocked is usually a
 * spoiler, a nickname or a bot's signature — all of which are just a word.
 *
 * Kept here rather than in the player because typing a word is a settings act, not a
 * watching one; the player has the switch that matters mid-film (合并重复) and nothing else.
 */
@Composable
internal fun DanmakuBlockedDialog(
    words: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    var draft by remember { mutableStateOf("") }

    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "弹幕屏蔽词",
            subtitle = "含有这些词的弹幕不会显示。区分不了大小写，按包含匹配。",
            onClose = onDismiss,
        )
        DanmakuField(
            value = draft,
            placeholder = "输入一个词后按添加",
            keyboardType = KeyboardType.Text,
            onValueChange = { draft = it },
        )
        if (words.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                words.forEach { word ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .glass(AppShapes.control, palette.card2, palette.border)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            word,
                            style = AppTypography.body.strong,
                            color = palette.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "移除",
                            style = AppTypography.caption.strong,
                            color = palette.error,
                            modifier =
                                Modifier
                                    .pressable(onClick = { onRemove(word) })
                                    .touchTarget()
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OverlayButton("完成", onDismiss, Modifier.weight(1f))
            OverlayButton(
                label = "添加",
                onClick = {
                    onAdd(draft)
                    draft = ""
                },
                modifier = Modifier.weight(1f),
                tone = OverlayButtonTone.Primary,
                enabled = draft.isNotBlank(),
            )
        }
    }
}

/** One entry mid-edit. A null [id] is a source that does not exist yet. */
private data class DanmakuSourceDraft(
    val id: String?,
    val name: String,
    val url: String,
)

@Composable
private fun DanmakuSourceRow(
    source: DanmakuSource,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(role = Role.RadioButton, onClick = onSelect)
            .semantics { this.selected = selected }
            .glass(
                AppShapes.control,
                if (selected) accent.container else palette.card2,
                if (selected) accent.border else palette.border,
            ).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                source.name,
                style = AppTypography.body.strong,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                // Templates and servers behave differently enough that the row says which.
                if (source.isTemplate) "模板链接 · ${source.url}" else source.url,
                style = AppTypography.caption.regular,
                color = palette.sub2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                AppIcons.Check,
                contentDescription = "使用中",
                tint = accent.accent,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(
            "编辑",
            style = AppTypography.caption.strong,
            color = accent.accent,
            modifier =
                Modifier
                    .pressable(onClickLabel = "编辑弹幕源", onClick = onEdit)
                    .touchTarget()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun DanmakuField(
    value: String,
    placeholder: String,
    keyboardType: KeyboardType,
    onValueChange: (String) -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .glass(AppShapes.control, palette.card2, palette.border)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isBlank()) {
            Text(
                placeholder,
                style = AppTypography.body.medium,
                color = palette.hint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = AppTypography.body.medium.copy(color = palette.text),
            cursorBrush = SolidColor(accent.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = placeholder },
        )
    }
}

/** Global 片头片尾 behavior; per-title boundaries stay in the player that owns them. */
@Composable
internal fun SkipSegmentDialog(
    skipMode: SkipMode,
    onSelectSkipMode: (SkipMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current

    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "片头片尾",
            subtitle = "设置所有影视的跳过方式",
            onClose = onDismiss,
        )
        // 关闭 keeps every title's saved boundaries while globally disabling the prompt.
        SkipMode.entries.forEach { mode ->
            OverlayOptionRow(
                mode.label,
                mode == skipMode,
                onClick = { onSelectSkipMode(mode) },
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "具体片头片尾边界只在播放对应影视时设置；个人中心不展示剧名、集数或时间。",
            style = AppTypography.caption.regular,
            color = palette.sub2,
        )
        OverlayButton(
            label = "完成",
            onClick = com.yfuse.core.designsystem.overlayDismiss(onDismiss),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            tone = OverlayButtonTone.Primary,
        )
    }
}

/**
 * Manual join-by-code. The invite link is the primary path (it resolves the title on the
 * joiner's own servers and needs no typing at all); this exists for when a messenger
 * refuses to linkify a custom scheme, or the code arrives by voice.
 *
 * Pasted text is accepted as-is: [WatchInvite.parseFromText] pulls a code out of a whole
 * forwarded invite block, so people don't have to trim it down to six characters.
 */
@Composable
internal fun WatchJoinDialog(
    connected: Boolean,
    connecting: Boolean,
    roomCode: String?,
    participantCount: Int,
    error: String?,
    onJoin: (String) -> Unit,
    onEnter: () -> Unit,
    onLeave: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val parsed = remember(draft) { WatchInvite.parseFromText(draft) }
    val code = parsed?.roomCode ?: WatchInvite.normalizeCode(draft)
    val unsupportedEndpoint = parsed?.unsupportedEndpoint
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current

    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = if (connected) "一起看" else "加入一起看",
            subtitle =
                if (connected) {
                    "已在房间中。退出播放界面不会离开房间，随时可以再进去。"
                } else {
                    "粘贴邀请或输入 6 位房间码。"
                },
            onClose = onDismiss,
        )
        if (connected) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .glass(AppShapes.control, palette.card2, palette.border)
                    .padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CopyableRoomCode(
                    roomCode = roomCode.orEmpty(),
                    style = AppTypography.display.strong,
                    color = accent.accent,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "$participantCount 人在线",
                    style = AppTypography.caption.medium,
                    color = palette.sub2,
                )
            }
            // The room outlives the player, so leaving the film is not leaving the room —
            // but until this button there was nothing that said so, and nothing that could
            // act on it once the mini player was gone.
            OverlayButton(
                label = "进入房间",
                onClick = onEnter,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                tone = OverlayButtonTone.Primary,
            )
            // Reported by the entry above when the room is playing something this library
            // does not have, or has not started at all — the connected branch used to drop
            // this on the floor, which is fine for a state with no actions in it and not
            // fine now that it has one that can fail.
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = AppTypography.caption.medium, color = palette.error)
            }
            OverlayButton(
                label = "退出房间",
                onClick = onLeave,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                tone = OverlayButtonTone.Destructive,
            )
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .glass(AppShapes.control, palette.card2, palette.border)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Box(contentAlignment = Alignment.CenterStart) {
                    if (draft.isBlank()) {
                        Text(
                            "房间码或邀请链接",
                            style = AppTypography.body.medium,
                            color = palette.hint,
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it.take(300) },
                        singleLine = true,
                        textStyle = AppTypography.body.medium.copy(color = palette.text),
                        cursorBrush = SolidColor(accent.accent),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "房间码或邀请链接" },
                    )
                }
            }
            if (unsupportedEndpoint != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "无法加入：这条旧邀请指向非官方服务器 $unsupportedEndpoint。一起看协议 v5 只连接 Yfuse 账号服务的官方安全地址，请让邀请者重新分享。",
                    style = AppTypography.caption.medium,
                    color = palette.error,
                )
            } else if (code.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "将加入房间 $code",
                    style = AppTypography.caption.medium,
                    color = palette.sub2,
                )
            }
            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = AppTypography.caption.medium, color = palette.error)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OverlayButton("取消", onDismiss, Modifier.weight(1f))
                OverlayButton(
                    label = if (connecting) "连接中…" else "加入",
                    onClick = { onJoin(code) },
                    modifier = Modifier.weight(1f),
                    tone = OverlayButtonTone.Primary,
                    enabled =
                        !connecting &&
                            unsupportedEndpoint == null &&
                            WatchInvite.isCompleteCode(code),
                )
            }
        }
    }
}

/** Device-local identity shown only inside watch-together rooms. */
@Composable
internal fun WatchProfileDialog(
    currentName: String,
    currentAvatarId: Int,
    onSave: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(currentName) { mutableStateOf(currentName) }
    var selectedAvatar by remember(currentAvatarId) { mutableStateOf(currentAvatarId) }
    val normalized = draft.replace('\r', ' ').replace('\n', ' ').trim()
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current

    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "一起看资料",
            subtitle = "昵称和头像只会显示给同一房间的成员，并保存在当前设备。",
            onClose = onDismiss,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WatchAvatar(selectedAvatar, 48.dp)
            Column(
                Modifier
                    .weight(1f)
                    .glass(AppShapes.control, palette.card2, palette.border)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                Box(contentAlignment = Alignment.CenterStart) {
                    if (draft.isBlank()) {
                        Text(
                            WatchTogetherPreferences.DEFAULT_NICKNAME,
                            style = AppTypography.body.medium,
                            color = palette.hint,
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = { value ->
                            draft =
                                value
                                    .replace('\r', ' ')
                                    .replace('\n', ' ')
                                    .withoutControlCharacters()
                                    .takeGraphemes(WatchTogetherPreferences.MAX_NICKNAME_GRAPHEMES)
                                    .takeGraphemesWithinUtf8Bytes(
                                        WatchTogetherPreferences.MAX_NICKNAME_BYTES,
                                    )
                        },
                        singleLine = true,
                        textStyle = AppTypography.body.medium.copy(color = palette.text),
                        cursorBrush = SolidColor(accent.accent),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "昵称" },
                    )
                }
                Text(
                    "${draft.graphemeCount()}/${WatchTogetherPreferences.MAX_NICKNAME_GRAPHEMES}",
                    style = AppTypography.caption.medium,
                    color = palette.hint,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("选择头像", style = AppTypography.caption.strong, color = palette.sub2)
        Column(
            Modifier.fillMaxWidth().padding(top = 9.dp).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            (0 until WatchTogetherPreferences.AVATAR_COUNT).chunked(4).forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    row.forEach { avatarId ->
                        WatchAvatar(
                            avatarId = avatarId,
                            size = 42.dp,
                            selected = avatarId == selectedAvatar,
                            modifier =
                                Modifier
                                    .pressable(role = Role.RadioButton) { selectedAvatar = avatarId }
                                    .semantics {
                                        this.selected = avatarId == selectedAvatar
                                        contentDescription = "头像 ${avatarId + 1}"
                                    }.touchTarget(),
                        )
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OverlayButton("取消", onDismiss, Modifier.weight(1f))
            OverlayButton(
                label = "保存",
                onClick = { onSave(normalized, selectedAvatar) },
                modifier = Modifier.weight(1f),
                tone = OverlayButtonTone.Primary,
                enabled = normalized.isNotEmpty(),
            )
        }
    }
}
