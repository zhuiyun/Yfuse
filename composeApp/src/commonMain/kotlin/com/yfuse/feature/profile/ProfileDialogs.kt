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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.DanmakuSource
import com.yfuse.core.data.SkipMode
import com.yfuse.core.data.SkipTimes
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.data.activeOr
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.Palette
import com.yfuse.core.designsystem.WatchAvatar
import com.yfuse.core.designsystem.flatGlass as glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.sc
import com.yfuse.core.sync.WatchInvite
import com.yfuse.core.util.graphemeCount
import com.yfuse.core.util.takeGraphemes
import com.yfuse.core.util.takeGraphemesWithinUtf8Bytes
import com.yfuse.core.util.withoutControlCharacters

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

    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "自定义 User-Agent",
            subtitle = "应用于服务器 API 与视频取流请求；留空时使用系统默认值。",
            onClose = onDismiss,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(13.dp), palette.card2, palette.border)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Box(contentAlignment = Alignment.CenterStart) {
                if (draft.isBlank()) {
                    Text(
                        "例如：Yfuse/Android",
                        style = mr(12f, 500),
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
                    textStyle = mr(12f, 500).copy(color = palette.text),
                    cursorBrush = SolidColor(Brand.Primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "修改后新发起的请求立即生效；正在播放的媒体需重新进入播放器。",
            style = mr(10.5f, 400),
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
            subtitle = if (draft == null) {
                "可以配置多个，选中的那个负责搜索和匹配，播放器里也能切换。"
            } else {
                "填服务器地址即可搜索；带 {id} 等占位符的链接按模板直接取。"
            },
            onClose = onDismiss,
        )

        val editing = draft
        if (editing == null) {
            Column(
                Modifier.fillMaxWidth(),
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
                        style = mr(11f, 400),
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
                style = mr(10.5f, 400),
                color = palette.sub2,
            )
            if (normalizedUrl.isNotEmpty() && !valid) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "链接必须以 http:// 或 https:// 开头",
                    style = sc(10.5f, 500),
                    color = Brand.Danger,
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
                            .glass(RoundedCornerShape(13.dp), palette.card2, palette.border)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            word,
                            style = sc(12f, 600),
                            color = palette.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "移除",
                            style = sc(11f, 600),
                            color = Brand.Danger,
                            modifier = Modifier
                                .pressable(onClick = { onRemove(word) })
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
private data class DanmakuSourceDraft(val id: String?, val name: String, val url: String)

@Composable
private fun DanmakuSourceRow(
    source: DanmakuSource,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .glass(
                RoundedCornerShape(13.dp),
                if (selected) Brand.Primary.copy(alpha = 0.12f) else palette.card2,
                if (selected) Brand.Primary.copy(alpha = 0.35f) else palette.border,
            )
            .pressable(onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                source.name,
                style = sc(12.5f, 700),
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                // Templates and servers behave differently enough that the row says which.
                if (source.isTemplate) "模板链接 · ${source.url}" else source.url,
                style = mr(10f, 400),
                color = palette.sub2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                AppIcons.Check,
                contentDescription = "使用中",
                tint = Brand.Primary,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(
            "编辑",
            style = sc(11f, 600),
            color = Brand.Primary,
            modifier = Modifier.pressable(onClick = onEdit).padding(horizontal = 6.dp, vertical = 4.dp),
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
    Box(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(13.dp), palette.card2, palette.border)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isBlank()) {
            Text(
                placeholder,
                style = mr(12f, 500),
                color = palette.hint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = mr(12f, 500).copy(color = palette.text),
            cursorBrush = SolidColor(Brand.Primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 片头片尾 — what happens at a boundary, and the numeric editor for times already captured.
 *
 * Entries are *created* in the player, where a boundary can be set from wherever playback
 * already is; a series that has never been played has nothing here to name it. What this
 * screen adds is the precise pass afterwards — nudging a captured 89 to 90 — plus one place
 * to see and drop everything that has accumulated.
 */
@Composable
internal fun SkipSegmentDialog(
    bySeries: Map<String, SkipTimes>,
    skipMode: SkipMode,
    onSelectSkipMode: (SkipMode) -> Unit,
    onSave: (String, SkipTimes) -> Unit,
    onClear: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<String?>(null) }
    val palette = LocalPalette.current

    GlassDialog(onDismiss = onDismiss) {
        val target = editing?.let { id -> bySeries[id]?.let { id to it } }
        if (target == null) {
            OverlayHeader(
                title = "片头片尾",
                subtitle = "按剧保存。在播放器的「更多」里点按设为当前进度，再点「取消」撤销。",
                onClose = onDismiss,
            )
            // 关闭 keeps the times and stops offering them, which is a different request
            // from deleting a show's boundaries — and the only one that used to be
            // impossible without throwing the work away.
            SkipMode.entries.forEach { mode ->
                OverlayOptionRow(
                    mode.label,
                    mode == skipMode,
                    onClick = { onSelectSkipMode(mode) },
                )
            }
            if (bySeries.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "还没有设置过。播放某一集时打开「更多」→「片头片尾」，点按即可把当前进度设为边界；" +
                        "片尾记的是距离结束还有多少秒，所以同一部剧每集时长不同也适用。",
                    style = mr(10.5f, 400),
                    color = palette.sub2,
                )
            } else {
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    bySeries.forEach { (seriesId, times) ->
                        SeriesSkipRow(
                            times = times,
                            palette = palette,
                            onEdit = { editing = seriesId },
                            onClear = { onClear(seriesId) },
                        )
                    }
                }
            }
            OverlayButton(
                label = "完成",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                tone = OverlayButtonTone.Primary,
            )
        } else {
            val (seriesId, times) = target
            SeriesSkipEditor(
                times = times,
                palette = palette,
                onSave = { updated ->
                    onSave(seriesId, updated)
                    editing = null
                },
                onBack = { editing = null },
            )
        }
    }
}

@Composable
private fun SeriesSkipRow(
    times: SkipTimes,
    palette: Palette,
    onEdit: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(13.dp), palette.card2, palette.border)
            .pressable(onClick = onEdit)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                times.seriesName.ifBlank { "未命名剧集" },
                style = sc(12.5f, 700),
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            val summary = buildList {
                if (times.hasIntro) {
                    add("片头 ${times.introStartSeconds}–${times.introEndSeconds} 秒")
                }
                if (times.hasCredits) {
                    add("片尾 最后 ${times.creditsLeadSeconds} 秒")
                }
            }
            Text(
                // A half-entered intro is kept but skips nothing, so say so rather than
                // leaving a blank line that reads as "configured, working".
                summary.ifEmpty { listOf("片头只填了一半，尚未生效") }.joinToString(" · "),
                style = mr(10f, 400),
                color = if (summary.isEmpty()) Brand.Danger else palette.sub2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "清除",
            style = mr(11f, 600),
            color = Brand.Danger,
            modifier = Modifier.pressable(onClick = onClear).padding(4.dp),
        )
    }
}

@Composable
private fun SeriesSkipEditor(
    times: SkipTimes,
    palette: Palette,
    onSave: (SkipTimes) -> Unit,
    onBack: () -> Unit,
) {
    fun initial(seconds: Long) = if (seconds > 0L) seconds.toString() else ""
    var introStart by remember(times) { mutableStateOf(initial(times.introStartSeconds)) }
    var introEnd by remember(times) { mutableStateOf(initial(times.introEndSeconds)) }
    var creditsLead by remember(times) { mutableStateOf(initial(times.creditsLeadSeconds)) }

    val parsedIntroStart = introStart.toLongOrNull() ?: 0L
    val parsedIntroEnd = introEnd.toLongOrNull() ?: 0L
    val parsedCreditsLead = creditsLead.toLongOrNull() ?: 0L
    // A start without an end describes no interval, so it can't be saved on its own; the
    // reverse (an end alone) is treated as "opening runs from 0", which is the common case.
    val problem = when {
        parsedIntroEnd > 0L && parsedIntroEnd <= parsedIntroStart -> "片头结束时间要晚于开始时间"
        introEnd.isBlank() && introStart.isNotBlank() -> "填了片头开始，也要填片头结束"
        else -> null
    }

    OverlayHeader(
        title = times.seriesName.ifBlank { "未命名剧集" },
        subtitle = "填秒数，留空或填 0 表示取消这一项，改回跟随服务器。",
        onClose = onBack,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondsField("片头开始", "0", introStart, palette) { introStart = it }
        SecondsField("片头结束", "90", introEnd, palette) { introEnd = it }
        // Counted back from the end, not forward from the start: episodes of one show
        // differ in runtime by a minute or two, and it is the tail that stays put.
        SecondsField("片尾 · 距结束", "120", creditsLead, palette) { creditsLead = it }
    }
    if (problem != null) {
        Spacer(Modifier.height(6.dp))
        Text(problem, style = sc(10.5f, 500), color = Brand.Danger)
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OverlayButton("返回", onBack, Modifier.weight(1f))
        OverlayButton(
            label = "保存",
            onClick = {
                onSave(
                    times.copy(
                        introStartSeconds = parsedIntroStart,
                        introEndSeconds = parsedIntroEnd,
                        creditsLeadSeconds = parsedCreditsLead,
                    ),
                )
            },
            modifier = Modifier.weight(1f),
            tone = OverlayButtonTone.Primary,
            enabled = problem == null,
        )
    }
}

@Composable
private fun SecondsField(
    label: String,
    hint: String,
    value: String,
    palette: Palette,
    onValueChange: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(13.dp), palette.card2, palette.border)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = mr(12f, 500), color = palette.sub, modifier = Modifier.weight(1f))
        Box(contentAlignment = Alignment.CenterEnd) {
            if (value.isBlank()) {
                Text(hint, style = mr(12f, 500), color = palette.hint, maxLines = 1)
            }
            BasicTextField(
                value = value,
                // Digits only: rejecting anything else as it is typed is clearer than
                // failing at 保存, and it keeps the field parseable by definition.
                onValueChange = { raw -> onValueChange(raw.filter(Char::isDigit).take(5)) },
                singleLine = true,
                textStyle = mr(12f, 500).copy(color = palette.text, textAlign = TextAlign.End),
                cursorBrush = SolidColor(Brand.Primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(72.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text("秒", style = mr(11f, 500), color = palette.sub2)
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
    val palette = LocalPalette.current

    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = if (connected) "一起看" else "加入一起看",
            subtitle = if (connected) {
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
                    .glass(RoundedCornerShape(13.dp), palette.card2, palette.border)
                    .padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(roomCode.orEmpty(), style = sc(22f, 800), color = Brand.Primary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "$participantCount 人在线",
                    style = mr(10.5f, 500),
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
                Text(it, style = sc(10.5f, 500), color = Brand.Danger)
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
                    .glass(RoundedCornerShape(13.dp), palette.card2, palette.border)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Box(contentAlignment = Alignment.CenterStart) {
                    if (draft.isBlank()) {
                        Text(
                            "房间码或邀请链接",
                            style = mr(12f, 500),
                            color = palette.hint,
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it.take(300) },
                        singleLine = true,
                        textStyle = mr(12f, 500).copy(color = palette.text),
                        cursorBrush = SolidColor(Brand.Primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (code.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "将加入房间 $code",
                    style = mr(10.5f, 500),
                    color = palette.sub2,
                )
            }
            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = sc(10.5f, 500), color = Brand.Danger)
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
                    enabled = !connecting && WatchInvite.isCompleteCode(code),
                )
            }
        }
    }
}

/** Relay address — infrastructure, so it lives in settings rather than in the player. */
@Composable
internal fun WatchEndpointDialog(
    current: String,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(current) { mutableStateOf(current) }
    val normalized = draft.trim().trimEnd('/')
    val valid = listOf("http://", "https://", "ws://", "wss://").any { normalized.startsWith(it) }
    val isDefault = current.trimEnd('/') ==
        WatchTogetherPreferences.DEFAULT_ENDPOINT.trimEnd('/')
    val palette = LocalPalette.current

    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "一起看服务器",
            subtitle = "只转发房间状态，不经过视频。留空或恢复默认即使用内置地址。",
            onClose = onDismiss,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(13.dp), palette.card2, palette.border)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Box(contentAlignment = Alignment.CenterStart) {
                if (draft.isBlank()) {
                    Text(
                        "https://watch.example.com",
                        style = mr(12f, 500),
                        color = palette.hint,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it.take(300) },
                    singleLine = true,
                    textStyle = mr(12f, 500).copy(color = palette.text),
                    cursorBrush = SolidColor(Brand.Primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (normalized.isNotEmpty() && !valid) {
            Spacer(Modifier.height(6.dp))
            Text(
                "地址必须以 http://、https://、ws:// 或 wss:// 开头",
                style = sc(10.5f, 500),
                color = Brand.Danger,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (isDefault) {
                OverlayButton("取消", onDismiss, Modifier.weight(1f))
            } else {
                OverlayButton(
                    label = "恢复默认",
                    onClick = onReset,
                    modifier = Modifier.weight(1f),
                    tone = OverlayButtonTone.Destructive,
                )
            }
            OverlayButton(
                label = "保存",
                onClick = { onSave(normalized) },
                modifier = Modifier.weight(1f),
                tone = OverlayButtonTone.Primary,
                enabled = valid,
            )
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
                    .glass(RoundedCornerShape(13.dp), palette.card2, palette.border)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                Box(contentAlignment = Alignment.CenterStart) {
                    if (draft.isBlank()) {
                        Text(
                            WatchTogetherPreferences.DEFAULT_NICKNAME,
                            style = mr(12f, 500),
                            color = palette.hint,
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = { value ->
                            draft = value.replace('\r', ' ')
                                .replace('\n', ' ')
                                .withoutControlCharacters()
                                .takeGraphemes(WatchTogetherPreferences.MAX_NICKNAME_GRAPHEMES)
                                .takeGraphemesWithinUtf8Bytes(
                                    WatchTogetherPreferences.MAX_NICKNAME_BYTES,
                                )
                        },
                        singleLine = true,
                        textStyle = mr(12f, 500).copy(color = palette.text),
                        cursorBrush = SolidColor(Brand.Primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    "${draft.graphemeCount()}/${WatchTogetherPreferences.MAX_NICKNAME_GRAPHEMES}",
                    style = mr(9f, 500),
                    color = palette.hint,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("选择头像", style = sc(11f, 700), color = palette.sub2)
        Column(
            Modifier.fillMaxWidth().padding(top = 9.dp),
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
                            modifier = Modifier.pressable { selectedAvatar = avatarId },
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
