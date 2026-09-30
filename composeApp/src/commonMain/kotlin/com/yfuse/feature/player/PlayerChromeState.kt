package com.yfuse.feature.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yfuse.core.sync.WatchChatMessage

/** Single-purpose popups opened by their own playback-page buttons. */
internal enum class QuickPopup {
    Source,
    Speed,
}

/**
 * What the player chrome has up: the controls themselves, the lock and its moment on screen, the
 * one panel, popup, sheet or dialog open at a time, 一起看's chat and how far it has been read, and
 * the volume slider.
 *
 * These were twenty-one locals in PlayerControls, written from the pointer detectors, the
 * remote's commands, the effects and nearly every layer of the chrome. Held here and remembered
 * once, the openings and closings that touch several of them at a time are the methods below, and a
 * layer drawn outside PlayerControls takes this one holder rather than a setter for each flag it
 * closes. The timers that take things down again stay with PlayerControls, keyed as they were.
 *
 * Snapshot state throughout: the chrome composes from every field. Touched on the main thread only —
 * the pointer detectors, the remote's collectors, the effects and the layers' callbacks.
 */
@Stable
internal class PlayerChromeState {
    /** The controls are up. */
    var visible by mutableStateOf(true)

    /** Bumped by every interaction so the auto-hide timer restarts. */
    var interactions by mutableIntStateOf(0)

    var locked by mutableStateOf(false)

    // The lock's own chrome — the circle and 长按解锁 — comes up for a moment after locking and after
    // each touch on the picture, then leaves it alone. [lockedRevealRevision] restarts that moment.
    var lockedControlsVisible by mutableStateOf(false)
    var lockedRevealRevision by mutableIntStateOf(0)

    // Someone has tried to act through the lock: the circle says how to undo it until it fades.
    var lockedExplained by mutableStateOf(false)

    var settingsPanelKind by mutableStateOf<SettingsPanelKind?>(null)
    var trackPanelMode by mutableStateOf(TrackPanelMode.Subtitle)
    var quickPopup by mutableStateOf<QuickPopup?>(null)
    var drawerOpen by mutableStateOf(false)
    var gestureHelpOpen by mutableStateOf(false)
    var watchDialogOpen by mutableStateOf(false)
    var watchChatOpen by mutableStateOf(false)
    var chatPreviewVisible by mutableStateOf(false)
    var previewRoomCode by mutableStateOf<String?>(null)
    var lastPreviewedChatId by mutableStateOf<Long?>(null)
    var lastReadChatId by mutableStateOf<Long?>(null)
    var danmakuSearchOpen by mutableStateOf(false)
    var danmakuSendOpen by mutableStateOf(false)

    // The volume rocker raises the slider; touching the slider keeps it up. Counted
    // separately from `interactions` so that tapping anywhere else on the picture doesn't
    // silently extend an overlay the user is done with.
    var volumeSliderTouches by mutableIntStateOf(0)
    var volumeSliderVisible by mutableStateOf(false)

    /** Brings the controls up and restarts their hide timer: a touch, a key or a remote's press. */
    fun poke() {
        interactions++
        visible = true
    }

    /** Brings the lock's own chrome up again; [explain] makes it say how to undo the lock. */
    fun revealLock(explain: Boolean) {
        if (explain) lockedExplained = true
        lockedRevealRevision++
    }

    /** 聊天, over whatever else was open; [transcript] counts as read. */
    fun openWatchChat(transcript: List<WatchChatMessage>) {
        settingsPanelKind = null
        quickPopup = null
        drawerOpen = false
        danmakuSearchOpen = false
        danmakuSendOpen = false
        watchDialogOpen = false
        watchChatOpen = true
        lastReadChatId = transcript.lastOrNull()?.id
        chatPreviewVisible = false
        poke()
    }

    /** Closes 聊天 with [transcript] read. */
    fun closeWatchChat(transcript: List<WatchChatMessage>) {
        watchChatOpen = false
        lastReadChatId = transcript.lastOrNull()?.id
    }

    fun openSettingsPanel(
        kind: SettingsPanelKind,
        trackMode: TrackPanelMode = TrackPanelMode.Subtitle,
    ) {
        quickPopup = null
        drawerOpen = false
        watchChatOpen = false
        danmakuSearchOpen = false
        danmakuSendOpen = false
        watchDialogOpen = false
        trackPanelMode = trackMode
        settingsPanelKind = kind
        poke()
    }

    fun openQuickPopup(popup: QuickPopup) {
        settingsPanelKind = null
        drawerOpen = false
        watchChatOpen = false
        danmakuSearchOpen = false
        danmakuSendOpen = false
        watchDialogOpen = false
        quickPopup = popup
        poke()
    }

    fun openEpisodeDrawer() {
        settingsPanelKind = null
        quickPopup = null
        watchChatOpen = false
        danmakuSearchOpen = false
        danmakuSendOpen = false
        watchDialogOpen = false
        drawerOpen = true
        poke()
    }

    /** 锁定, from the left-edge key or from 更多: whatever was open closes under the lock. */
    fun lockScreen() {
        settingsPanelKind = null
        quickPopup = null
        drawerOpen = false
        locked = true
        visible = true
    }
}
