package com.yfuse.feature.player

import android.app.AlertDialog
import android.content.Context
import com.yfuse.core.model.PlayerEngine
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

/** What a switch for one entry replaced, put back once the queue moves to another entry. */
internal data class ItemEngineOverride(
    val itemId: String,
    val selection: PlaybackEngineSelection,
    val kind: PlayerEngine,
    val core2Disabled: Boolean,
    /** The switch left YCore; going back means enabling it again rather than picking a strategy. */
    val leftCore2: Boolean,
)

/**
 * Leaves the current engine for the compatibility one, for [itemId] only. Returns what it replaced,
 * for [restoreItemEngine] once the queue moves to another entry.
 */
internal fun switchToCompatibilityForItem(
    itemId: String,
    leaveCore2: Boolean,
    build: PlayerEngineBuild,
    choices: PlayerViewerChoices,
    capturePlaybackHandover: () -> Unit,
    selectEngineStrategy: (PlaybackEngineSelection) -> Unit,
): ItemEngineOverride {
    val replaced =
        ItemEngineOverride(
            itemId = itemId,
            selection = choices.sessionEngineSelection,
            kind = build.kind,
            core2Disabled = build.core2DisabledForSession,
            leftCore2 = leaveCore2,
        )
    if (leaveCore2) {
        // Changing only `kind` would construct YCore again under Auto: it leaves the trial path too.
        capturePlaybackHandover()
        build.core2DisabledForSession = true
        choices.sessionEngineSelection = PlaybackEngineSelection.LockMpv
        build.kind = PlayerEngine.Mpv
        build.engineGeneration++
    } else {
        selectEngineStrategy(PlaybackEngineSelection.LockMpv)
    }
    return replaced
}

/** Puts back what [switchToCompatibilityForItem] replaced; the entry it was for is over. */
internal fun restoreItemEngine(
    replaced: ItemEngineOverride,
    build: PlayerEngineBuild,
    choices: PlayerViewerChoices,
    capturePlaybackHandover: () -> Unit,
    selectEngineStrategy: (PlaybackEngineSelection) -> Unit,
) {
    if (replaced.leftCore2) {
        capturePlaybackHandover()
        build.core2DisabledForSession = replaced.core2Disabled
        choices.sessionEngineSelection = replaced.selection
        build.kind = replaced.kind
        build.engineGeneration++
    } else {
        selectEngineStrategy(replaced.selection)
    }
}

/**
 * Asks before playback leaves its engine for a setting that engine cannot honour, and offers the
 * switch for the current entry only.
 *
 * A setting the viewer adjusted, or one remembered for the series, used to rebuild playback on the
 * compatibility engine without a word and keep the whole session there. Each entry and setting is
 * asked about once; keeping the engine resets the setting, so the panel never shows a value that is
 * not being applied.
 */
internal class EngineSwitchPrompt {
    private val asked = mutableSetOf<Pair<String?, UnsupportedPlaybackSetting>>()
    private var dialog: AlertDialog? = null

    fun ask(
        context: Context,
        itemId: String?,
        setting: UnsupportedPlaybackSetting,
        onSwitch: () -> Unit,
        onKeep: () -> Unit,
    ) {
        if (dialog?.isShowing == true || !asked.add(itemId to setting)) return
        var answered = false
        dialog =
            AlertDialog
                .Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("改用兼容内核播放本集？")
                .setMessage("当前内核无法应用「${setting.label}」。可以只为这一集改用兼容内核，下一集会回到原来的选择。")
                .setPositiveButton("本集改用") { _, _ ->
                    answered = true
                    onSwitch()
                }.setNegativeButton("保持当前内核") { _, _ ->
                    answered = true
                    onKeep()
                }.setOnDismissListener {
                    if (!answered) onKeep()
                    dialog = null
                }.show()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }
}
