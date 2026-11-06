package com.yfuse.feature.player

import android.app.AlertDialog
import android.content.Context
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.AudioEnhancementMode
import com.yfuse.core.playback.PlaybackEngineSelection

/** A playback setting the engine on screen cannot honour, named the way the panel names it. */
internal enum class UnsupportedPlaybackSetting(
    val label: String,
) {
    SubtitleOffset("字幕偏移"),
    AudioDelay("音频延迟"),
    AudioEnhancement("音频增强"),
    SubtitleScale("字幕字号"),
    SubtitleBrightness("字幕亮度"),
    SubtitlePosition("字幕位置"),
    SubtitleAppearance("字幕样式"),
}

/** The setting's neutral value: what is left when the viewer keeps the engine instead. */
internal fun UnsupportedPlaybackSetting.reset(choices: PlayerViewerChoices) {
    when (this) {
        UnsupportedPlaybackSetting.SubtitleOffset ->
            choices.subtitleControls = choices.subtitleControls.copy(offsetMs = 0L)
        UnsupportedPlaybackSetting.AudioDelay -> choices.audioControls = choices.audioControls.copy(delayMs = 0L)
        UnsupportedPlaybackSetting.AudioEnhancement ->
            choices.audioControls = choices.audioControls.copy(enhancement = AudioEnhancementMode.Off)
        UnsupportedPlaybackSetting.SubtitleScale -> choices.subtitleControls = choices.subtitleControls.copy(scale = 1f)
        UnsupportedPlaybackSetting.SubtitleBrightness ->
            choices.subtitleControls = choices.subtitleControls.copy(brightness = 1f)
        UnsupportedPlaybackSetting.SubtitlePosition ->
            choices.subtitleControls = choices.subtitleControls.copy(position = DEFAULT_SUBTITLE_POSITION)
        UnsupportedPlaybackSetting.SubtitleAppearance ->
            choices.subtitleControls = choices.subtitleControls.copy(appearance = SubtitleAppearance())
    }
}

/** Original user choice restored when a manually overridden queue entry ends. */
internal data class ItemEngineOverride(
    val itemId: String,
    val selection: PlaybackEngineSelection,
    val kind: PlayerEngine,
)

/** Explicitly tries YCore for the current SenPlayer entry; never activates SenPlayer implicitly. */
internal fun switchToYCoreForItem(
    itemId: String,
    build: PlayerEngineBuild,
    choices: PlayerViewerChoices,
    capturePlaybackHandover: () -> Unit,
): ItemEngineOverride {
    check(build.kind == PlayerEngine.SenPlayer) { "An item override can only switch SenPlayer to YCore" }
    val replaced = ItemEngineOverride(itemId, choices.sessionEngineSelection, build.kind)
    capturePlaybackHandover()
    choices.sessionEngineSelection = PlaybackEngineSelection.LockYCore
    build.kind = PlayerEngine.YCore
    build.engineGeneration++
    return replaced
}

internal fun restoreItemEngine(
    replaced: ItemEngineOverride,
    build: PlayerEngineBuild,
    choices: PlayerViewerChoices,
    capturePlaybackHandover: () -> Unit,
) {
    capturePlaybackHandover()
    choices.sessionEngineSelection = replaced.selection
    build.kind = replaced.kind
    build.engineGeneration++
}

/**
 * Asks before playback leaves its engine for a setting that engine cannot honour, and offers the
 * switch for the current entry only.
 *
 * A setting the viewer adjusted, or one remembered for the series, used to rebuild playback on the
 * another engine without a word and keep the whole session there. Concurrent unsupported
 * settings share one decision; keeping the engine resets every setting named by that decision.
 */
internal class EngineSwitchPrompt {
    private val requests = EngineSwitchRequests()
    private var shown: PendingEngineSwitch? = null
    private var dialog: AlertDialog? = null

    fun ask(
        context: Context,
        currentEngine: PlayerEngine,
        itemId: String?,
        setting: UnsupportedPlaybackSetting,
        onSwitch: () -> Unit,
        onKeep: () -> Unit,
    ) {
        if (currentEngine != PlayerEngine.SenPlayer) {
            dismiss()
            onKeep()
            PlayerNotices.show("当前 YCore 管线不支持「${setting.label}」，已恢复此设置")
            return
        }
        val pending = requests.offer(itemId, setting, onSwitch, onKeep)
        val message = "当前内核无法应用「${pending.settings.joinToString(
            "、",
        ) { it.label }}」。可以只为这一集尝试 YCore；具体功能以实际输出为准，下一集会回到原来的选择。"
        if (shown === pending && dialog?.isShowing == true) {
            dialog?.setMessage(message)
            return
        }
        dialog?.dismiss()
        shown = pending
        dialog =
            AlertDialog
                .Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("本集尝试 YCore？")
                .setMessage(message)
                .setPositiveButton("本集改用") { _, _ ->
                    requests.resolve(pending, switch = true)
                }.setNegativeButton("保持当前内核") { _, _ ->
                    requests.resolve(pending, switch = false)
                }.setOnDismissListener {
                    requests.resolve(pending, switch = false)
                    if (shown === pending) {
                        shown = null
                        dialog = null
                    }
                }.show()
    }

    fun dismiss() {
        shown?.let { requests.resolve(it, switch = false) }
        dialog?.dismiss()
        shown = null
        dialog = null
    }
}

/** Only unresolved choices are retained; a later adjustment is a new user decision. */
internal class EngineSwitchRequests {
    private var pending: PendingEngineSwitch? = null

    fun offer(
        itemId: String?,
        setting: UnsupportedPlaybackSetting,
        onSwitch: () -> Unit,
        onKeep: () -> Unit,
    ): PendingEngineSwitch {
        pending?.takeIf { it.itemId != itemId }?.let { resolve(it, switch = false) }
        val current = pending ?: PendingEngineSwitch(itemId, onSwitch).also { pending = it }
        current.onSwitch = onSwitch
        current.onKeep[setting] = onKeep
        return current
    }

    fun resolve(
        request: PendingEngineSwitch,
        switch: Boolean,
    ) {
        if (pending !== request) return
        pending = null
        if (switch) {
            request.onSwitch()
        } else {
            request.onKeep.values
                .toList()
                .forEach { it() }
        }
    }
}

internal class PendingEngineSwitch(
    val itemId: String?,
    internal var onSwitch: () -> Unit,
) {
    internal val onKeep = linkedMapOf<UnsupportedPlaybackSetting, () -> Unit>()
    val settings: List<UnsupportedPlaybackSetting> get() = onKeep.keys.toList()
}
