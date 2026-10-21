package com.yfuse.feature.stremio

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.PageHint
import com.yfuse.core.designsystem.SettingRow
import com.yfuse.core.designsystem.YfButton
import com.yfuse.core.designsystem.YfButtonTone
import com.yfuse.core.designsystem.YfFormField
import com.yfuse.core.designsystem.motionItem
import com.yfuse.core.designsystem.motionItems
import com.yfuse.core.stremio.StremioAddon

internal fun LazyListScope.stremioHome(
    state: StremioScreenState,
    addons: List<StremioAddon>,
    controller: StremioController,
) {
    motionItem("install") { StremioInstaller(state, controller) }
    motionItem("help") {
        StremioInset { PageHint("插件按当前资料单独保存。安装目录插件和播放源插件后，可以从目录进入详情、选集并选择播放源。支持 HTTP／HLS／DASH；其他资源会说明原因。") }
    }
    motionItems(addons, key = { "addon-${it.key}" }) { addon -> StremioInstalledAddon(addon, state.busy, controller) }
    motionItems(state.catalogs, key = { "catalog-${it.key}" }) { ref ->
        StremioInset {
            SettingRow(ref.label, ref.catalog.type, onClick = { controller.selectCatalog(ref) }, enabled = !state.busy)
        }
    }
    if (addons.isNotEmpty() && state.catalogs.isEmpty()) {
        motionItem("no-catalog") {
            StremioInset { PageHint("当前没有启用的目录。仅提供播放源的插件需配合目录插件使用。") }
        }
    }
}

@Composable
private fun StremioInstaller(
    state: StremioScreenState,
    controller: StremioController,
) {
    var input by remember { mutableStateOf("") }
    var browserError by remember { mutableStateOf(false) }
    var showAddress by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    StremioInset {
        YfFormField(
            input,
            {
                input = it
                controller.discardCandidate()
            },
            "插件安装地址",
            enabled = !state.busy,
            placeholder = "https://…/manifest.json",
            visualTransformation = if (showAddress) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = AppIcons.Eye,
            trailingIconContentDescription = if (showAddress) "隐藏插件地址" else "显示插件地址",
            onTrailingIconClick = { showAddress = !showAddress },
        )
        YfButton("检查插件", { controller.inspect(input) }, enabled = input.isNotBlank() && !state.busy)
        YfButton(
            "填入官方 Cinemeta 目录",
            {
                input = "https://v3-cinemeta.strem.io/manifest.json"
                controller.discardCandidate()
            },
            enabled = !state.busy,
            tone = YfButtonTone.Secondary,
        )
        state.candidate?.let { manifest ->
            PageHint("${manifest.name} · ${manifest.version}\n${manifest.description.take(800)}")
            if (manifest.behaviorHints.configurationRequired) PageHint("此插件需要先配置，请完成配置后粘贴新的安装地址。")
            if (manifest.behaviorHints.configurable || manifest.behaviorHints.configurationRequired) {
                YfButton("打开插件配置网页", {
                    browserError = runCatching { controller.candidateConfigurationUrl()?.let(uri::openUri) }.isFailure
                }, enabled = !state.busy, tone = YfButtonTone.Secondary)
            }
            YfButton(
                "安装 ${manifest.name}",
                controller::install,
                enabled =
                    !state.busy && !manifest.behaviorHints.configurationRequired,
            )
        }
        if (browserError) PageHint("无法打开浏览器，请在浏览器中完成插件配置。")
    }
}

@Composable
private fun StremioInstalledAddon(
    addon: StremioAddon,
    busy: Boolean,
    controller: StremioController,
) {
    var removing by remember { mutableStateOf(false) }
    StremioInset {
        SettingRow(
            addon.manifest.name,
            "${addon.manifest.version} · ${if (addon.enabled) "已启用" else "已停用"}",
            onClick = { controller.enable(addon.key, !addon.enabled) },
            enabled = !busy,
        )
        YfButton("移除 ${addon.manifest.name}", { removing = true }, enabled = !busy, tone = YfButtonTone.Secondary)
        YfButton("更新插件清单", { controller.refresh(addon.key) }, enabled = !busy, tone = YfButtonTone.Secondary)
    }
    if (removing) {
        GlassDialog(onDismiss = { removing = false }) {
            OverlayHeader("移除插件", addon.manifest.name, onClose = { removing = false })
            PageHint("将移除此资料保存的插件及配置地址。")
            YfButton("确认移除", {
                controller.remove(addon.key)
                removing = false
            }, enabled = !busy, tone = YfButtonTone.Destructive)
        }
    }
}
