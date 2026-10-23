package com.yfuse.feature.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.PlatformBackHandler
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.widget.InstalledWidget
import com.yfuse.core.widget.WidgetPage
import com.yfuse.core.widget.WidgetRepository
import com.yfuse.core.widget.downloadWidgetSource
import com.yfuse.core.widget.rememberWidgetBackupFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
fun WidgetWorkspaceScreen(
    repository: WidgetRepository,
    onClose: () -> Unit,
    initialSource: Pair<String, String>? = null,
    embedded: Boolean = false,
) {
    val database by repository.database.collectAsState()
    val storeError by repository.error.collectAsState()
    val bundleError by repository.bundleError.collectAsState()
    val scope = rememberCoroutineScope()
    var pending by remember(initialSource) { mutableStateOf(initialSource) }
    var page by rememberSaveable(stateSaver = widgetDraftSaver<WidgetPage?>()) { mutableStateOf(null) }
    var removing by remember { mutableStateOf<InstalledWidget?>(null) }
    var pageActions by remember { mutableStateOf<WidgetPage?>(null) }
    var widgetActions by remember { mutableStateOf<InstalledWidget?>(null) }
    var adding by remember { mutableStateOf(false) }
    var backups by remember { mutableStateOf(false) }
    var pagesSelected by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val backRouter = remember { WidgetBackRouter(onClose) }
    val backup = rememberWidgetBackupFiles(repository) { message = it }
    if (page == null) {
        SideEffect {
            backRouter.onBack = {
                when {
                    pending != null || adding -> {
                        pending = null
                        adding = false
                    }
                    widgetActions != null -> widgetActions = null
                    pageActions != null -> pageActions = null
                    backups -> backups = false
                    else -> onClose()
                }
            }
        }
    }
    if (embedded) PlatformBackHandler(enabled = true, onBack = { backRouter.onBack() })

    fun work(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            message = null
            try {
                block()
            } catch (
                e: CancellationException,
            ) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "操作失败"
            } finally {
                busy = false
            }
        }
    }

    val body: @Composable () -> Unit = {
        when {
            page != null ->
                WidgetPageEditor(
                    page!!,
                    repository,
                    onBack = { page = null },
                    onSaved = { page = null },
                    onBackChanged = { backRouter.onBack = it },
                )
            else ->
                WidgetFrame("组件管理", onClose, action = {
                    WidgetIconButton(AppIcons.More, "备份与恢复", { backups = true })
                    WidgetIconButton(AppIcons.Add, if (pagesSelected) "新建页面" else "安装组件", {
                        if (pagesSelected) page = repository.newPage("我的首页") else adding = true
                    })
                }) {
                    val palette = LocalPalette.current
                    WidgetSegmentedControl(
                        listOf("组件 ${database.widgets.size}", "页面 ${database.pages.size}"),
                        if (pagesSelected) 1 else 0,
                        { pagesSelected = it == 1 },
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        (message ?: storeError ?: bundleError)?.let { text ->
                            item { Text(text, color = palette.sub2, style = AppTypography.body.regular) }
                        }
                        if (busy) item { WidgetProgress(Modifier.fillMaxWidth()) }
                        if (pagesSelected) {
                            if (database.pages.isEmpty()) {
                                item {
                                    WidgetEmptyState(AppIcons.Home, "从一张空白页面开始", "把喜欢的栏目排成你的首页。", "新建页面") {
                                        page = repository.newPage("我的首页")
                                    }
                                }
                            }
                            items(database.pages, key = { it.id }) { item ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .glass(AppShapes.card, palette.card, palette.border)
                                        .pressable(onClick = { page = item })
                                        .padding(start = 14.dp, top = 10.dp, end = 6.dp, bottom = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    WidgetManagerGlyph(AppIcons.Home)
                                    Column(
                                        Modifier.weight(1f).padding(horizontal = 14.dp),
                                        verticalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Text(
                                            item.title,
                                            color = palette.text,
                                            style = AppTypography.body.strong,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "${item.sections.size} 个栏目${if (item.hidden) {
                                                " · 已隐藏"
                                            } else if (database.defaultPageId == item.id) {
                                                " · 默认页面"
                                            } else {
                                                ""
                                            }}",
                                            color = palette.sub2,
                                            style = AppTypography.caption.regular,
                                        )
                                    }
                                    WidgetIconButton(AppIcons.More, "${item.title}的更多操作", { pageActions = item })
                                }
                            }
                        } else {
                            if (database.widgets.isEmpty()) {
                                item {
                                    WidgetEmptyState(AppIcons.Grid, "添加你的第一个组件", "通过链接或本地文件，带来更多内容。", "添加组件") {
                                        adding =
                                            true
                                    }
                                }
                            }
                            items(database.widgets, key = { it.metadata.id }) { widget ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .glass(AppShapes.card, palette.card, palette.border)
                                        .pressable(onClick = { widgetActions = widget })
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    WidgetPublisherIcon(widget.metadata)
                                    Column(
                                        Modifier.weight(1f).padding(horizontal = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            widget.metadata.title,
                                            color = palette.text,
                                            style = AppTypography.body.strong,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            (if (widget.enabled) "" else "已停用 · ") +
                                                widget.metadata.description.ifBlank {
                                                    "v${widget.metadata.version} · ${widget.metadata.modules.size} 个栏目"
                                                },
                                            color = palette.sub2,
                                            style = AppTypography.caption.regular,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    WidgetSecondaryButton({ widgetActions = widget }) {
                                        Text("查看", style = AppTypography.caption.medium)
                                    }
                                }
                            }
                        }
                    }
                }
        }
        if (adding || pending != null) {
            WidgetInstallSheet(
                repository,
                pending,
                onSource = {
                    adding = true
                    pending = it
                },
                onDismiss = {
                    pending = null
                    adding = false
                },
                onInstalled = {
                    pending = null
                    adding = false
                    message = "组件已安装，在「页面」中添加栏目即可使用"
                },
            )
        }
        if (backups) {
            WidgetSheet("备份与恢复", onDismiss = { backups = false }) {
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "保存组件、页面和栏目设置，随时恢复你的首页。",
                        color = LocalPalette.current.sub2,
                        style = AppTypography.body.reading,
                    )
                    Panel {
                        WidgetMenuRow("导出备份", {
                            backups = false
                            backup.export()
                        }, icon = AppIcons.Share, subtitle = "保存到本地文件")
                        WidgetMenuRow("恢复备份", {
                            backups = false
                            backup.restore()
                        }, icon = AppIcons.Download, subtitle = "选择已有备份文件")
                    }
                }
            }
        }
        pageActions?.let { item ->
            val index = database.pages.indexOfFirst { it.id == item.id }
            WidgetSheet(item.title, onDismiss = { pageActions = null }) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Panel {
                        WidgetMenuRow("编辑页面", {
                            pageActions = null
                            page = item
                        }, icon = AppIcons.Edit)
                        WidgetMenuRow(
                            if (database.defaultPageId == item.id) "取消默认首页" else "设为默认首页",
                            {
                                pageActions = null
                                work { repository.setDefault(if (database.defaultPageId == item.id) null else item.id) }
                            },
                            icon = AppIcons.Home,
                            enabled = !busy && !item.hidden,
                            subtitle = if (item.hidden) "先在页面设置中开启显示" else null,
                        )
                        WidgetMenuRow("上移", {
                            pageActions = null
                            work { repository.movePage(item.id, -1) }
                        }, enabled = !busy && index > 0)
                        WidgetMenuRow("下移", {
                            pageActions = null
                            work { repository.movePage(item.id, 1) }
                        }, enabled = !busy && index >= 0 && index < database.pages.lastIndex)
                    }
                }
            }
        }
        widgetActions?.let { selectedWidget ->
            val widget = database.widgets.firstOrNull { it.metadata.id == selectedWidget.metadata.id } ?: selectedWidget
            WidgetSheet("组件详情", onDismiss = { widgetActions = null }) {
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    WidgetMetadataCard(widget.metadata, onError = { message = it })
                    Panel {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("启用组件", color = LocalPalette.current.text, style = AppTypography.body.strong)
                                Text(
                                    if (widget.enabled) "栏目可以加载内容" else "已暂停所有关联栏目",
                                    color = LocalPalette.current.sub2,
                                    style = AppTypography.caption.regular,
                                )
                            }
                            WidgetSwitch(widget.enabled) { enabled ->
                                if (!busy) work { repository.enable(widget.metadata.id, enabled) }
                            }
                        }
                    }
                    WidgetGroupCaption("提供 ${widget.metadata.modules.size} 个栏目")
                    Panel {
                        widget.metadata.modules.forEach { module ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    module.title,
                                    Modifier.weight(1f),
                                    color = LocalPalette.current.text,
                                    style = AppTypography.body.medium,
                                )
                                Text(
                                    if (module.params.isEmpty()) "即开即用" else "${module.params.size} 项设置",
                                    color = LocalPalette.current.sub2,
                                    style = AppTypography.caption.regular,
                                )
                            }
                        }
                    }
                    message?.let { Text(it, color = LocalPalette.current.sub2, style = AppTypography.body.regular) }
                    if (busy) WidgetProgress(Modifier.fillMaxWidth())
                    Panel {
                        if (com.yfuse.core.widget
                                .widgetWebLink(widget.origin)
                                .isNotBlank()
                        ) {
                            WidgetMenuRow("检查更新", {
                                work {
                                    pending = downloadWidgetSource(widget.origin) to widget.origin
                                    widgetActions = null
                                }
                            }, icon = AppIcons.Refresh, enabled = !busy)
                        }
                        WidgetMenuRow("卸载组件", {
                            widgetActions = null
                            removing = widget
                        }, icon = AppIcons.Remove, destructive = true, enabled = !busy)
                    }
                }
            }
        }
        removing?.let { widget ->
            ConfirmDialog(
                title = "卸载${widget.metadata.title}？",
                message = "页面布局会保留，此组件的栏目将暂停加载，重新安装后可继续使用。",
                confirmLabel = "卸载",
                onDismiss = { removing = null },
                onConfirm = {
                    removing = null
                    work { repository.uninstall(widget.metadata.id) }
                },
            )
        }
    }
    if (embedded) body() else FullWidgetDialog(onClose = { backRouter.onBack() }, content = body)
}
