package com.yfuse.feature.player

import com.yfuse.core.sync.WatchChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerChromeStateTest {
    private val chrome = PlayerChromeState()

    private fun message(id: Long) =
        WatchChatMessage(
            id = id,
            clientId = "c$id",
            name = "观众",
            avatarId = 0,
            text = "第 $id 条",
            sentAtMs = id,
            isMine = false,
        )

    /** Everything a panel opening is expected to close, open at once. */
    private fun openEverything() {
        chrome.settingsPanelKind = SettingsPanelKind.Danmaku
        chrome.quickPopup = QuickPopup.Speed
        chrome.drawerOpen = true
        chrome.watchChatOpen = true
        chrome.danmakuSearchOpen = true
        chrome.danmakuSendOpen = true
        chrome.watchDialogOpen = true
    }

    @Test
    fun theControlsStartUpWithNothingOpenAndNothingLocked() {
        assertTrue(chrome.visible)
        assertFalse(chrome.locked)
        assertEquals(0, chrome.interactions)
        assertNull(chrome.settingsPanelKind)
        assertNull(chrome.quickPopup)
        assertEquals(TrackPanelMode.Subtitle, chrome.trackPanelMode)
        assertFalse(chrome.volumeSliderVisible)
    }

    @Test
    fun aPokeBringsTheControlsUpAndRestartsTheirTimer() {
        chrome.visible = false
        chrome.poke()
        assertTrue(chrome.visible)
        assertEquals(1, chrome.interactions)
        chrome.poke()
        assertEquals(2, chrome.interactions)
    }

    @Test
    fun aSettingsPanelReplacesEveryOtherPanelAndRemembersItsTrackMode() {
        openEverything()
        chrome.visible = false
        chrome.openSettingsPanel(SettingsPanelKind.Tracks, TrackPanelMode.Audio)
        assertEquals(SettingsPanelKind.Tracks, chrome.settingsPanelKind)
        assertEquals(TrackPanelMode.Audio, chrome.trackPanelMode)
        assertNull(chrome.quickPopup)
        assertFalse(chrome.drawerOpen)
        assertFalse(chrome.watchChatOpen)
        assertFalse(chrome.danmakuSearchOpen)
        assertFalse(chrome.danmakuSendOpen)
        assertFalse(chrome.watchDialogOpen)
        assertTrue(chrome.visible)
        assertEquals(1, chrome.interactions)

        chrome.openSettingsPanel(SettingsPanelKind.More)
        assertEquals(TrackPanelMode.Subtitle, chrome.trackPanelMode)
    }

    @Test
    fun aQuickPopupAndTheEpisodeDrawerEachCloseEverythingElse() {
        openEverything()
        chrome.openQuickPopup(QuickPopup.Source)
        assertEquals(QuickPopup.Source, chrome.quickPopup)
        assertNull(chrome.settingsPanelKind)
        assertFalse(chrome.drawerOpen)
        assertFalse(chrome.watchChatOpen)
        assertFalse(chrome.danmakuSearchOpen)
        assertFalse(chrome.danmakuSendOpen)
        assertFalse(chrome.watchDialogOpen)

        openEverything()
        chrome.openEpisodeDrawer()
        assertTrue(chrome.drawerOpen)
        assertNull(chrome.settingsPanelKind)
        assertNull(chrome.quickPopup)
        assertFalse(chrome.watchChatOpen)
        assertFalse(chrome.danmakuSearchOpen)
        assertFalse(chrome.danmakuSendOpen)
        assertFalse(chrome.watchDialogOpen)
        assertEquals(2, chrome.interactions)
    }

    @Test
    fun openingTheChatReadsTheTranscriptAndPutsThePreviewAway() {
        openEverything()
        chrome.watchChatOpen = false
        chrome.chatPreviewVisible = true
        chrome.openWatchChat(listOf(message(1), message(7)))
        assertTrue(chrome.watchChatOpen)
        assertEquals(7L, chrome.lastReadChatId)
        assertFalse(chrome.chatPreviewVisible)
        assertNull(chrome.settingsPanelKind)
        assertNull(chrome.quickPopup)
        assertFalse(chrome.drawerOpen)
        assertFalse(chrome.danmakuSearchOpen)
        assertFalse(chrome.danmakuSendOpen)
        assertFalse(chrome.watchDialogOpen)
        assertEquals(1, chrome.interactions)

        chrome.closeWatchChat(listOf(message(1), message(7), message(9)))
        assertFalse(chrome.watchChatOpen)
        assertEquals(9L, chrome.lastReadChatId)

        chrome.openWatchChat(emptyList())
        assertNull(chrome.lastReadChatId)
    }

    @Test
    fun theLockClosesWhatItCoversAndKeepsTheControlsUpWithoutCountingATouch() {
        openEverything()
        chrome.visible = false
        chrome.lockScreen()
        assertTrue(chrome.locked)
        assertTrue(chrome.visible)
        assertNull(chrome.settingsPanelKind)
        assertNull(chrome.quickPopup)
        assertFalse(chrome.drawerOpen)
        // The lock leaves the chat, 弹幕 sheets and the room dialog as they were.
        assertTrue(chrome.watchChatOpen)
        assertTrue(chrome.danmakuSearchOpen)
        assertTrue(chrome.watchDialogOpen)
        assertEquals(0, chrome.interactions)
    }

    @Test
    fun revealingTheLockRestartsItsMomentAndOnlyARefusalExplainsIt() {
        chrome.revealLock(explain = false)
        assertEquals(1, chrome.lockedRevealRevision)
        assertFalse(chrome.lockedExplained)
        chrome.revealLock(explain = true)
        assertEquals(2, chrome.lockedRevealRevision)
        assertTrue(chrome.lockedExplained)
        chrome.revealLock(explain = false)
        assertTrue(chrome.lockedExplained)
    }
}
