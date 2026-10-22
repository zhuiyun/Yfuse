package com.yfuse.feature.stremio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OperationFeedback
import com.yfuse.core.designsystem.OperationTone
import com.yfuse.core.designsystem.YfFormField
import com.yfuse.core.designsystem.motionItem
import com.yfuse.core.designsystem.motionItems
import com.yfuse.core.stremio.StremioAddon
import com.yfuse.feature.widgets.Panel
import com.yfuse.feature.widgets.WidgetEmptyState
import com.yfuse.feature.widgets.WidgetGroupCaption
import com.yfuse.feature.widgets.WidgetMenuRow
import com.yfuse.feature.widgets.WidgetPrimaryButton
import com.yfuse.feature.widgets.WidgetSheet
import com.yfuse.feature.widgets.WidgetSwitch
import com.yfuse.feature.widgets.WidgetTextButton
import com.yfuse.core.designsystem.ThemeText as Text

internal fun LazyListScope.stremioHome(
    state: StremioScreenState,
    addons: List<StremioAddon>,
    controller: StremioController,
    catalogsSelected: Boolean,
    onInstall: () -> Unit,
) {
    if (catalogsSelected) {
        motionItem("catalog-heading") { StremioInset { WidgetGroupCaption("来自已启用的插件") } }
        motionItems(state.catalogs, key = { "catalog-${it.key}" }) { ref ->
            StremioInset {
                Panel {
                    WidgetMenuRow(
                        ref.catalog.name,
                        { controller.selectCatalog(ref) },
                        icon = if (ref.catalog.type == "series") AppIcons.Series else AppIcons.Movie,
                        subtitle = ref.addon.manifest.name,
                        value = stremioTypeName(ref.catalog.type),
                        enabled = !state.busy,
                    )
                }
            }
        }
        if (state.catalogs.isEmpty()) {
            motionItem("no-catalog") {
                StremioInset {
                    WidgetEmptyState(
                        AppIcons.Folder,
                        "还没有可浏览的目录",
                        "安装并启用目录插件后，电影与剧集会出现在这里。",
                        "安装插件",
                        onInstall,
                    )
                }
            }
        }
    } else {
        motionItem("addon-heading") { StremioInset { WidgetGroupCaption("此资料的插件") } }
        motionItems(
            addons,
            key = { "addon-${it.key}" },
        ) { addon -> StremioInstalledAddon(addon, state, controller) }
        if (addons.isEmpty()) {
            motionItem("no-addons") {
                StremioInset {
                    WidgetEmptyState(
                        AppIcons.Grid,
                        "添加你的第一个插件",
                        "连接电影目录、剧集和播放源。插件会保存在当前资料中。",
                        "安装插件",
                        onInstall,
                    )
                }
            }
        }
    }
}

@Composable
internal fun StremioInstaller(
    state: StremioScreenState,
    controller: StremioController,
    onDismiss: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var browserError by remember { mutableStateOf(false) }
    var showAddress by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var requested by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    LaunchedEffect(state.busy, state.candidate, submitted) {
        if (submitted && !state.busy && state.candidate == null) onDismiss()
    }
    WidgetSheet("安装插件", onDismiss = { if (!state.busy) onDismiss() }) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val manifest = state.candidate
            if (manifest == null) {
                YfFormField(
                    input,
                    {
                        input = it.take(4096)
                        controller.discardCandidate()
                        browserError = false
                        requested = false
                    },
                    "插件 URL",
                    enabled = !state.busy,
                    placeholder = "https://…/manifest.json",
                    visualTransformation =
                        if (showAddress) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                    trailingIcon = AppIcons.Eye,
                    trailingIconContentDescription = if (showAddress) "隐藏插件地址" else "显示插件地址",
                    onTrailingIconClick = { showAddress = !showAddress },
                )
                Text("安装地址可能包含个人配置，默认隐藏。", style = AppTypography.caption.regular, color = LocalPalette.current.sub2)
                WidgetTextButton({
                    input = "https://v3-cinemeta.strem.io/manifest.json"
                    requested = false
                    controller.discardCandidate()
                }, enabled = !state.busy) { Text("使用官方 Cinemeta 目录") }
            } else {
                Panel {
                    Text(manifest.name, style = AppTypography.section.strong, color = LocalPalette.current.text)
                    Text(
                        "v${manifest.version} · ${manifest.catalogs.size} 个目录",
                        style = AppTypography.caption.regular,
                        color = LocalPalette.current.sub2,
                    )
                    if (manifest.description.isNotBlank()) {
                        Text(
                            manifest.description.take(800),
                            style = AppTypography.body.regular,
                            color = LocalPalette.current.sub,
                        )
                    }
                }
                if (manifest.behaviorHints.configurationRequired) {
                    OperationFeedback("此插件需要先配置。完成后，请粘贴新的安装地址。")
                }
                if (manifest.behaviorHints.configurable || manifest.behaviorHints.configurationRequired) {
                    WidgetMenuRow("配置插件", {
                        browserError =
                            runCatching { controller.candidateConfigurationUrl()?.let(uri::openUri) }.isFailure
                    }, icon = AppIcons.Edit, subtitle = "在浏览器中打开配置页面", enabled = !state.busy)
                }
                WidgetTextButton({
                    submitted = false
                    requested = false
                    controller.discardCandidate()
                }, enabled = !state.busy) { Text("更换链接") }
            }
            if (state.busy) OperationFeedback(if (submitted) "正在安装…" else "正在读取插件…", OperationTone.Working)
            if (requested && (!submitted || state.candidate != null)) {
                state.message?.let { OperationFeedback(it, OperationTone.Error) }
            }
            state.failures.forEach { OperationFeedback(it, OperationTone.Error) }
            if (browserError) OperationFeedback("无法打开浏览器，请在浏览器中完成插件配置。", OperationTone.Error)
        }
        WidgetPrimaryButton(
            {
                requested = true
                if (state.candidate == null) {
                    controller.inspect(input)
                } else {
                    submitted = true
                    controller.install()
                }
            },
            Modifier.fillMaxWidth(),
            enabled =
                !state.busy &&
                    if (state.candidate ==
                        null
                    ) {
                        input.isNotBlank()
                    } else {
                        !state.candidate.behaviorHints.configurationRequired
                    },
        ) { Text(if (state.candidate == null) "读取插件" else "安装插件") }
    }
}

@Composable
private fun StremioInstalledAddon(
    addon: StremioAddon,
    state: StremioScreenState,
    controller: StremioController,
) {
    val busy = state.busy
    var operationRequested by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    StremioInset {
        Panel {
            WidgetMenuRow(
                addon.manifest.name,
                {
                    operationRequested = false
                    details = true
                },
                icon = AppIcons.Grid,
                subtitle = "v${addon.manifest.version} · ${addon.manifest.catalogs.size} 个目录",
                value = if (addon.enabled) "查看" else "已停用",
            )
        }
    }
    if (details) {
        WidgetSheet(addon.manifest.name, { details = false }) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "v${addon.manifest.version}",
                    color = LocalPalette.current.sub2,
                    style = AppTypography.caption.regular,
                )
                if (addon.manifest.description.isNotBlank()) {
                    Text(
                        addon.manifest.description.take(1500),
                        color = LocalPalette.current.sub,
                        style = AppTypography.body.regular,
                    )
                }
                Panel {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("启用插件", color = LocalPalette.current.text, style = AppTypography.body.strong)
                            Text("在目录与播放源中使用", color = LocalPalette.current.sub2, style = AppTypography.caption.regular)
                        }
                        WidgetSwitch(addon.enabled) {
                            if (!busy) {
                                operationRequested = true
                                controller.enable(addon.key, it)
                            }
                        }
                    }
                    WidgetMenuRow("更新插件清单", {
                        operationRequested = true
                        controller.refresh(addon.key)
                    }, icon = AppIcons.Refresh, enabled = !busy)
                }
                if (operationRequested) {
                    if (busy) OperationFeedback("正在处理插件…", OperationTone.Working)
                    state.message?.let { OperationFeedback(it) }
                }
                if (addon.manifest.catalogs.isNotEmpty()) {
                    WidgetGroupCaption("提供的目录")
                    addon.manifest.catalogs.forEach { catalog ->
                        Text(
                            "${catalog.name} · ${stremioTypeName(catalog.type)}",
                            color = LocalPalette.current.sub,
                            style = AppTypography.body.regular,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
                WidgetMenuRow("移除插件", {
                    details = false
                    removing = true
                }, icon = AppIcons.Remove, destructive = true, enabled = !busy)
            }
        }
    }
    if (removing) {
        ConfirmDialog(
            title = "移除 ${addon.manifest.name}？",
            message = "将移除此资料保存的插件及配置地址。",
            confirmLabel = "移除",
            onDismiss = { removing = false },
            onConfirm = {
                if (!busy) {
                    controller.remove(addon.key)
                    removing = false
                }
            },
        )
    }
}

internal fun stremioTypeName(type: String): String =
    when (type) {
        "movie" -> "电影"
        "series" -> "剧集"
        "tv" -> "频道"
        else -> type
    }
