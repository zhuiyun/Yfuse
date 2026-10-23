package com.yfuse.feature.widgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalMutedGlass
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.ThemeIcon
import com.yfuse.core.designsystem.YfFormField
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.widget.InstalledWidget
import com.yfuse.core.widget.WidgetMedia
import com.yfuse.core.widget.WidgetModule
import com.yfuse.core.widget.WidgetPage
import com.yfuse.core.widget.WidgetParameter
import com.yfuse.core.widget.WidgetRepository
import com.yfuse.core.widget.WidgetSection
import com.yfuse.core.widget.WidgetStyle
import com.yfuse.core.widget.parameterError
import com.yfuse.core.widget.parameterValues
import com.yfuse.core.widget.visible
import com.yfuse.core.widget.withBundledWidgetsFirst
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import com.yfuse.core.designsystem.GlassSlider as Slider
import com.yfuse.core.designsystem.ThemeText as Text
import com.yfuse.feature.widgets.WidgetFilterChip as FilterChip
import com.yfuse.feature.widgets.WidgetSwitch as Switch
import com.yfuse.feature.widgets.WidgetTextButton as TextButton

@Composable
internal fun WidgetPageEditor(
    initial: WidgetPage,
    repository: WidgetRepository,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onBackChanged: (() -> Unit) -> Unit,
) {
    var page by rememberSaveable(initial.id, stateSaver = widgetDraftSaver<WidgetPage>()) { mutableStateOf(initial) }
    var editing by rememberSaveable(stateSaver = widgetDraftSaver<WidgetSection?>()) { mutableStateOf(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var addingStyle by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var sectionActions by remember { mutableStateOf<WidgetSection?>(null) }
    var copying by remember { mutableStateOf<WidgetSection?>(null) }
    var discard by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val database by repository.database.collectAsState()
    val scope = rememberCoroutineScope()
    val latestPage by rememberUpdatedState(page)
    val dragStep = with(LocalDensity.current) { 100.dp.toPx() }
    val leave = { if (page != initial) discard = true else onBack() }
    if (editing == null) {
        SideEffect {
            onBackChanged {
                when {
                    renaming -> renaming = false
                    sectionActions != null -> sectionActions = null
                    copying != null -> copying = null
                    adding -> adding = false
                    addingStyle != null -> {
                        addingStyle = null
                        adding = true
                    }
                    else -> leave()
                }
            }
        }
    }

    fun move(
        id: String,
        delta: Int,
    ) {
        val sections = latestPage.sections.toMutableList()
        val index = sections.indexOfFirst { it.id == id }
        if (index >= 0) sections.add((index + delta).coerceIn(0, sections.lastIndex), sections.removeAt(index))
        page = latestPage.copy(sections = sections)
    }

    fun save(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            message = null
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "保存失败"
            } finally {
                busy = false
            }
        }
    }

    if (editing != null) {
        WidgetSectionEditor(
            editing!!,
            repository,
            onBack = { editing = null },
            onBackChanged = onBackChanged,
            onSave = { section ->
                page =
                    page.copy(
                        sections =
                            if (page.sections.any { it.id == section.id }) {
                                page.sections.map { if (it.id == section.id) section else it }
                            } else {
                                page.sections + section
                            },
                    )
                editing = null
            },
        )
    } else {
        WidgetFrame("编辑页面", leave, action = {
            TextButton(
                onClick = {
                    save {
                        repository.savePage(page.copy(title = page.title.trim()))
                        onSaved()
                    }
                },
                enabled = !busy && page.title.isNotBlank(),
            ) { Text(if (busy) "保存中…" else "完成") }
        }) {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item {
                    EditorGroup("页面设置") {
                        EditorSettingRow("页面名称", page.title.ifBlank { "未命名页面" }) { renaming = true }
                        EditorSwitchRow("在自定义首页显示", !page.hidden) { page = page.copy(hidden = !it) }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("内容栏目", style = AppTypography.body.strong, color = LocalPalette.current.text)
                            if (page.sections.isNotEmpty()) {
                                Text(
                                    "${page.sections.size} 个栏目 · 长按把手调整顺序",
                                    style = AppTypography.caption.regular,
                                    color = LocalPalette.current.sub2,
                                    modifier = Modifier.padding(top = 3.dp),
                                )
                            }
                        }
                        WidgetIconButton(AppIcons.Add, "添加栏目", { adding = true })
                    }
                }
                if (page.sections.isEmpty()) {
                    item {
                        WidgetEmptyState(
                            AppIcons.Grid,
                            "从一个栏目开始",
                            "选择喜欢的视图，再连接组件内容。",
                            "添加栏目",
                            { adding = true },
                        )
                    }
                }
                items(page.sections, key = { it.id }) { section ->
                    Panel {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clickable { editing = section }
                                    .padding(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    section.title,
                                    color = LocalPalette.current.text,
                                    style = AppTypography.body.strong,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    WidgetStyle.fromId(section.style).title + if (section.hidden) " · 已隐藏" else "",
                                    color = LocalPalette.current.sub2,
                                    style = AppTypography.caption.regular,
                                )
                            }
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .semantics { contentDescription = "长按拖动${section.title}，调整栏目顺序" }
                                    .pointerInput(section.id) {
                                        var distance = 0f
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { distance = 0f },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                distance += amount.y
                                                if (kotlin.math.abs(distance) >= dragStep) {
                                                    move(section.id, if (distance > 0) 1 else -1)
                                                    distance = 0f
                                                }
                                            },
                                        )
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                ThemeIcon(AppIcons.Menu, null, Modifier.size(20.dp), tint = LocalPalette.current.sub2)
                            }
                            WidgetIconButton(AppIcons.More, "${section.title}的更多操作", { sectionActions = section })
                        }
                        val style = WidgetStyle.fromId(section.style)
                        Box(Modifier.fillMaxWidth()) {
                            CompositionLocalProvider(LocalWidgetPreview provides true) {
                                WidgetCards(
                                    style,
                                    widgetPreviewItems.take(6),
                                    section.showMetadata,
                                    compact = true,
                                    onOpen = { editing = section },
                                )
                            }
                        }
                    }
                }
                if (page.sections.isNotEmpty()) {
                    item {
                        WidgetSecondaryButton({ adding = true }, Modifier.fillMaxWidth()) {
                            ThemeIcon(AppIcons.Add, null, Modifier.padding(end = 8.dp).size(18.dp))
                            Text("添加栏目")
                        }
                    }
                }
                message?.let {
                    item {
                        Text(
                            it,
                            color = LocalPalette.current.error,
                            style = AppTypography.caption.regular,
                        )
                    }
                }
                if (database.pages.any { it.id == page.id }) {
                    item {
                        TextButton({ deleting = true }, Modifier.fillMaxWidth(), enabled = !busy) {
                            Text("删除页面", color = LocalPalette.current.error)
                        }
                    }
                }
            }
        }
    }
    if (adding) {
        WidgetStylePicker("", emptyList(), onBack = { adding = false }, onSelect = {
            addingStyle = it.id
            adding = false
        })
    }
    addingStyle?.let { selectedStyle ->
        WidgetSourcePicker(database.widgets, onDismiss = {
            addingStyle = null
            adding = true
        }) { widget, module ->
            editing = repository.newSection(widget, module).copy(style = selectedStyle)
            addingStyle = null
        }
    }
    if (renaming) {
        EditorNameSheet("页面名称", page.title, 80, { renaming = false }) { page = page.copy(title = it) }
    }
    sectionActions?.let { section ->
        WidgetSheet(section.title, onDismiss = { sectionActions = null }) {
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                WidgetMenuRow("编辑栏目", {
                    sectionActions = null
                    editing = section
                }, icon = AppIcons.Edit)
                WidgetMenuRow("复制栏目", {
                    sectionActions = null
                    page = page.copy(sections = page.sections + repository.duplicate(section))
                }, icon = AppIcons.Grid)
                WidgetMenuRow("复制到其他页面", {
                    sectionActions = null
                    copying = section
                }, icon = AppIcons.Share)
                WidgetMenuRow(
                    "上移",
                    {
                        sectionActions = null
                        move(section.id, -1)
                    },
                    enabled =
                        page.sections.firstOrNull()?.id != section.id,
                )
                WidgetMenuRow(
                    "下移",
                    {
                        sectionActions = null
                        move(section.id, 1)
                    },
                    enabled =
                        page.sections.lastOrNull()?.id != section.id,
                )
                WidgetMenuRow("移除栏目", {
                    sectionActions = null
                    page = page.copy(sections = page.sections.filterNot { it.id == section.id })
                }, icon = AppIcons.Remove, destructive = true)
            }
        }
    }
    copying?.let { section ->
        WidgetSheet("复制到页面", onDismiss = { copying = null }) {
            message?.let { Text(it, color = LocalPalette.current.error, style = AppTypography.caption.regular) }
            if (database.pages.none { it.id != page.id }) {
                EmptyHint("还没有其他页面，创建页面后即可复制到这里。")
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(database.pages.filterNot { it.id == page.id }, key = { it.id }) { target ->
                        WidgetMenuRow(target.title, {
                            save {
                                repository.savePage(
                                    target.copy(
                                        sections =
                                            target.sections + repository.duplicate(section),
                                    ),
                                )
                                copying = null
                                message = "已复制到${target.title}"
                            }
                        }, icon = AppIcons.Home, subtitle = "${target.sections.size} 个栏目", enabled = !busy)
                    }
                }
            }
        }
    }
    if (discard) {
        ConfirmDialog(
            "放弃本次修改？",
            "尚未保存的页面调整会被丢弃。",
            confirmLabel = "放弃",
            onDismiss = { discard = false },
            onConfirm = onBack,
        )
    }
    if (deleting) {
        ConfirmDialog("删除这个页面？", "已安装的组件会保留。", confirmLabel = "删除", onDismiss = { deleting = false }, onConfirm = {
            deleting = false
            save {
                repository.removePage(page.id)
                onBack()
            }
        })
    }
}

@Composable
private fun WidgetSectionEditor(
    initial: WidgetSection,
    repository: WidgetRepository,
    onBack: () -> Unit,
    onBackChanged: (() -> Unit) -> Unit,
    onSave: (WidgetSection) -> Unit,
) {
    var section by rememberSaveable(
        initial.id,
        stateSaver = widgetDraftSaver<WidgetSection>(),
    ) { mutableStateOf(initial) }
    var chooseStyle by rememberSaveable { mutableStateOf(false) }
    var chooseSource by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<List<WidgetMedia>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var previewRequest by remember { mutableStateOf(0) }
    var editingParameter by remember { mutableStateOf<WidgetParameter?>(null) }
    val database by repository.database.collectAsState()
    val widget = database.widgets.firstOrNull { it.metadata.id == section.widgetId }
    val module = widget?.metadata?.modules?.firstOrNull { it.id == section.moduleId }
    val params = module?.parameterValues(section.params) ?: section.params
    val parameterIssue = module?.parameterError(section.params)
    val leave = { if (section != initial) discard = true else onBack() }
    SideEffect {
        onBackChanged {
            when {
                editingParameter != null -> editingParameter = null
                renaming -> renaming = false
                chooseStyle -> chooseStyle = false
                chooseSource -> chooseSource = false
                else -> leave()
            }
        }
    }
    LaunchedEffect(section.widgetId, section.moduleId, section.params, previewRequest) {
        preview = emptyList()
        error = null
        refreshing = false
        if (previewRequest > 0 && module != null && parameterIssue == null) {
            refreshing = true
            try {
                kotlinx.coroutines.delay(300)
                preview = repository.load(section, refresh = true).take(12)
                if (preview.isEmpty()) error = "当前条件暂无内容，正在展示样式示例。"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "预览加载失败"
            } finally {
                refreshing = false
            }
        }
    }
    val style = WidgetStyle.fromId(section.style)
    WidgetFrame("配置栏目", leave, action = {
        TextButton(
            { onSave(section.copy(title = section.title.trim())) },
            enabled = section.title.isNotBlank() && module != null && parameterIssue == null,
        ) { Text("完成") }
    }) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(key = "preview") {
                Panel {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                section.title.ifBlank { "栏目预览" },
                                color = LocalPalette.current.text,
                                style = AppTypography.body.strong,
                            )
                            Text(
                                if (refreshing) {
                                    "正在加载组件内容…"
                                } else if (preview.isEmpty()) {
                                    "横向预览 · 示例内容"
                                } else {
                                    "横向预览 · 组件内容"
                                },
                                color = LocalPalette.current.sub2,
                                style = AppTypography.caption.regular,
                            )
                        }
                        if (refreshing) {
                            WidgetProgress(Modifier.size(36.dp, 3.dp))
                        } else if (error != null && module != null && parameterIssue == null) {
                            WidgetRetryButton({ previewRequest++ })
                        }
                    }
                    Box(Modifier.fillMaxWidth()) {
                        CompositionLocalProvider(LocalWidgetPreview provides true) {
                            WidgetCards(
                                style,
                                preview.ifEmpty { widgetPreviewItems }.take(6),
                                section.showMetadata,
                                section.compact,
                                onOpen = { chooseStyle = true },
                            )
                        }
                    }
                    error?.let { Text(it, color = LocalPalette.current.error, style = AppTypography.caption.regular) }
                }
            }
            item {
                EditorGroup("标题与样式") {
                    EditorSettingRow("视图样式", style.title) { chooseStyle = true }
                    EditorSettingRow("自定义标题", section.title.ifBlank { "未设置" }) { renaming = true }
                }
            }
            item {
                EditorGroup("内容来源") {
                    EditorSettingRow("组件", widget?.metadata?.title ?: "组件缺失") { chooseSource = true }
                    EditorSettingRow("内容模块", module?.title ?: "模块缺失") { chooseSource = true }
                }
            }
            module
                ?.params
                ?.filter { it.type != "page" && it.visible(params) }
                ?.groupBy { parameter ->
                    if (Regex("sort|order|filter|genre|rating|played|排序|筛选|评分|流派|观看", RegexOption.IGNORE_CASE)
                            .containsMatchIn(parameter.name + parameter.label)
                    ) {
                        "排序与筛选"
                    } else {
                        "数据源参数"
                    }
                }?.forEach { (title, parameters) ->
                    item(key = title) {
                        EditorGroup(title) {
                            parameters.forEach { parameter ->
                                val value = params[parameter.name] ?: parameter.defaultValue
                                if (parameter.type in setOf("boolean", "bool", "switch")) {
                                    EditorSwitchRow(
                                        parameter.label,
                                        (value as? JsonPrimitive)?.booleanOrNull ?: false,
                                    ) { changed ->
                                        section =
                                            section.copy(
                                                params =
                                                    JsonObject(
                                                        section.params + (parameter.name to JsonPrimitive(changed)),
                                                    ),
                                            )
                                    }
                                } else {
                                    val raw = (value as? JsonPrimitive)?.contentOrNull.orEmpty()
                                    val summary =
                                        if (parameter.type == "password" && raw.isNotEmpty()) {
                                            "已设置"
                                        } else {
                                            parameter.options.firstOrNull { it.value == value }?.title
                                                ?: raw.ifBlank { "未设置" }
                                        }
                                    EditorSettingRow(parameter.label + if (parameter.required) " *" else "", summary) {
                                        editingParameter =
                                            parameter
                                    }
                                }
                            }
                        }
                    }
                }
            parameterIssue?.let {
                item {
                    Text(
                        it,
                        color = LocalPalette.current.error,
                        style = AppTypography.caption.regular,
                    )
                }
            }
            item {
                EditorGroup("显示选项") {
                    EditorSwitchRow("显示栏目", !section.hidden) { section = section.copy(hidden = !it) }
                    EditorSwitchRow("年份、评分与简介", section.showMetadata) { section = section.copy(showMetadata = it) }
                    EditorSwitchRow("紧凑布局", section.compact) { section = section.copy(compact = it) }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "首次展示条目数",
                            Modifier.weight(1f),
                            color = LocalPalette.current.text,
                            style = AppTypography.body.regular,
                        )
                        Text(
                            "${section.limit}",
                            color = LocalAccentColors.current.accent,
                            style = AppTypography.body.strong,
                        )
                    }
                    Slider(
                        section.limit.toFloat(),
                        { section = section.copy(limit = it.toInt()) },
                        valueRange = 1f..50f,
                        steps = 48,
                    )
                    Text(
                        "所有内容均在单行内横向浏览，更多条目不会增加栏目高度。",
                        color = LocalPalette.current.sub2,
                        style = AppTypography.caption.regular,
                    )
                }
            }
        }
    }
    if (chooseStyle) {
        WidgetStylePicker(section.style, preview, onBack = { chooseStyle = false }, onSelect = {
            section = section.copy(style = it.id)
            chooseStyle = false
        })
    }
    if (chooseSource) {
        WidgetSourcePicker(database.widgets, section.widgetId, section.moduleId, {
            chooseSource = false
        }) { selectedWidget, selectedModule ->
            section =
                section.copy(
                    widgetId = selectedWidget.metadata.id,
                    moduleId = selectedModule.id,
                    params = JsonObject(emptyMap()),
                )
            preview = emptyList()
            chooseSource = false
        }
    }
    if (renaming) {
        EditorNameSheet("自定义标题", section.title, 120, { renaming = false }) { section = section.copy(title = it) }
    }
    editingParameter?.let { parameter ->
        WidgetSheet(parameter.label, onDismiss = { editingParameter = null }) {
            WidgetParameterField(parameter, params[parameter.name] ?: parameter.defaultValue) { value ->
                section = section.copy(params = JsonObject(section.params + (parameter.name to value)))
            }
            Spacer(Modifier.height(16.dp))
            WidgetPrimaryButton({ editingParameter = null }, Modifier.fillMaxWidth()) { Text("完成") }
        }
    }
    if (discard) {
        ConfirmDialog(
            "放弃栏目修改？",
            "本次栏目调整尚未应用到页面。",
            confirmLabel = "放弃",
            onDismiss = { discard = false },
            onConfirm = onBack,
        )
    }
}

@Composable
private fun EditorNameSheet(
    title: String,
    value: String,
    maxLength: Int,
    onDismiss: () -> Unit,
    onChange: (String) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf(value) }
    WidgetSheet(title, onDismiss) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            YfFormField(draft, { draft = it.take(maxLength) }, title)
            Spacer(Modifier.height(12.dp))
            WidgetPrimaryButton({
                onChange(draft.trim())
                onDismiss()
            }, Modifier.fillMaxWidth(), enabled = draft.isNotBlank()) { Text("完成") }
        }
    }
}

@Composable
private fun WidgetSourcePicker(
    widgets: List<InstalledWidget>,
    selectedWidgetId: String? = null,
    selectedModuleId: String? = null,
    onDismiss: () -> Unit,
    onSelect: (InstalledWidget, WidgetModule) -> Unit,
) {
    WidgetSheet("选择内容来源", onDismiss, Modifier.fillMaxHeight(0.72f)) {
        val enabled = widgets.filter { it.enabled }.withBundledWidgetsFirst()
        if (enabled.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                WidgetEmptyState(AppIcons.Grid, "还没有可用组件", "请先安装组件，或在组件详情中启用已有组件。")
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                enabled.forEach { widget ->
                    item(key = "group-${widget.metadata.id}") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            WidgetGroupCaption(widget.metadata.title)
                            Panel {
                                widget.metadata.modules.filter { it.type != "stream" }.forEach { module ->
                                    WidgetMenuRow(
                                        module.title,
                                        { onSelect(widget, module) },
                                        icon = if (module.type == "category") AppIcons.Folder else AppIcons.Movie,
                                        subtitle =
                                            if (module.params.isEmpty()) {
                                                "直接添加"
                                            } else {
                                                "${module.params.count {
                                                    it.type != "page"
                                                }} 项可配置参数"
                                            },
                                        selected =
                                            widget.metadata.id == selectedWidgetId && module.id == selectedModuleId,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        WidgetGroupCaption(title)
        Panel(content = content)
    }
}

@Composable
private fun EditorSettingRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    WidgetMenuRow(label, onClick, value = value)
}

@Composable
private fun EditorSwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            Modifier.weight(1f).padding(end = 12.dp),
            color = LocalPalette.current.text,
            style = AppTypography.body.regular,
        )
        Switch(checked, onChange)
    }
}

@Composable
private fun ColumnScope.WidgetParameterField(
    parameter: WidgetParameter,
    value: JsonElement,
    onChange: (JsonElement) -> Unit,
) {
    val label = parameter.label + if (parameter.required) " *" else ""
    if (parameter.options.isNotEmpty() && parameter.type !in setOf("boolean", "bool", "switch")) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 336.dp)) {
            items(parameter.options) { option ->
                WidgetMenuRow(option.title, { onChange(option.value) }, selected = option.value == value)
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            if (parameter.type in setOf("boolean", "bool", "switch")) {
                EditorSwitchRow(
                    label,
                    (value as? JsonPrimitive)?.booleanOrNull ?: false,
                ) { onChange(JsonPrimitive(it)) }
            } else {
                YfFormField(
                    value = (value as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    label = label,
                    onValueChange = { text ->
                        if (parameter.type in setOf("number", "integer")) {
                            onChange(text.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(text.take(2000)))
                        } else {
                            onChange(JsonPrimitive(text.take(2000)))
                        }
                    },
                    visualTransformation =
                        if (parameter.type ==
                            "password"
                        ) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                )
            }
        }
    }
}

@Composable
private fun WidgetStylePicker(
    selected: String,
    preview: List<WidgetMedia>,
    onBack: () -> Unit,
    onSelect: (WidgetStyle) -> Unit,
) {
    WidgetSheet("选择视图", onBack, Modifier.fillMaxHeight()) {
        var category by rememberSaveable { mutableStateOf<String?>(null) }
        val groups = WidgetStyle.entries.groupBy { it.group }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(category == null, { category = null }, { Text("全部 ${WidgetStyle.entries.size}") }) }
            items(groups.keys.toList()) { group ->
                FilterChip(category == group, { category = group }, { Text(group) })
            }
        }
        Text(
            "所有视图均为单行横向浏览",
            Modifier.padding(top = 12.dp, bottom = 16.dp),
            color = LocalPalette.current.sub2,
            style = AppTypography.caption.regular,
        )
        key(category) {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                groups.filterKeys { category == null || it == category }.forEach { (group, styles) ->
                    item(key = "group-$group") { WidgetGroupCaption(group) }
                    items(styles, key = { it.id }) { style ->
                        // Title and preview taps share one exit-completed handoff to the next sheet.
                        val selectStyle = overlayAction { onSelect(style) }
                        Column(
                            Modifier.fillMaxWidth().clickable(onClick = selectStyle),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    style.title,
                                    Modifier.weight(1f),
                                    color = LocalPalette.current.text,
                                    style = AppTypography.body.strong,
                                )
                                if (style.id == selected) {
                                    ThemeIcon(
                                        AppIcons.Check,
                                        "已选择",
                                        Modifier.size(18.dp),
                                        tint = LocalAccentColors.current.accent,
                                    )
                                }
                            }
                            Text(
                                style.description,
                                color = LocalPalette.current.sub2,
                                style = AppTypography.caption.regular,
                            )
                            Spacer(Modifier.height(2.dp))
                            Box(Modifier.fillMaxWidth()) {
                                CompositionLocalProvider(
                                    LocalMutedGlass provides false,
                                    LocalWidgetPreview provides true,
                                ) {
                                    WidgetCards(
                                        style,
                                        preview.ifEmpty { widgetPreviewItems }.take(6),
                                        compact = true,
                                        onOpen = { selectStyle() },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
