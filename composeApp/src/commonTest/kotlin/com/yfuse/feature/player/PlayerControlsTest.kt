package com.yfuse.feature.player

import com.yfuse.core.sync.WatchChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerControlsTest {
    @Test
    fun aDoubleTapBeforeTheDurationIsKnownGoesNowhere() {
        // Opening at a 23:00 resume point: clamped to 0..0, the tap used to restart the episode.
        assertNull(doubleTapSeekTarget(positionMs = 0L, durationMs = 0L, deltaMs = 10_000L))
        assertNull(doubleTapSeekTarget(positionMs = 1_380_000L, durationMs = 0L, deltaMs = -10_000L))
        assertNull(doubleTapSeekTarget(positionMs = 5_000L, durationMs = -1L, deltaMs = 10_000L))
    }

    @Test
    fun aDoubleTapStaysWithinTheItem() {
        assertEquals(1_390_000L, doubleTapSeekTarget(1_380_000L, 2_700_000L, 10_000L))
        assertEquals(0L, doubleTapSeekTarget(4_000L, 2_700_000L, -10_000L))
        assertEquals(2_700_000L, doubleTapSeekTarget(2_695_000L, 2_700_000L, 10_000L))
    }

    @Test
    fun theKeysInTheMiddleTakeOnlyThePlayAndPauseWords() {
        assertNull(gestureHudLine("暂停", centreKeysShown = true))
        assertNull(gestureHudLine("播放", centreKeysShown = true))
        assertEquals("音量 40%", gestureHudLine("音量 40%", centreKeysShown = true))
        assertEquals("亮度 70%", gestureHudLine("亮度 70%", centreKeysShown = true))
        assertEquals("房主控制播放", gestureHudLine("房主控制播放", centreKeysShown = true))
        assertEquals("字幕 · 中文", gestureHudLine("字幕 · 中文", centreKeysShown = true))
        assertEquals("暂停", gestureHudLine("暂停", centreKeysShown = false))
        assertNull(gestureHudLine(null, centreKeysShown = true))
    }

    @Test
    fun aDragThatSetsOffUpwardsStaysBrightnessOrVolumeWhateverItDoesAfter() {
        val axis = PictureDragAxis()
        // Up past slop, then most of the way back down with a little drift to the side: by the
        // release, the running totals used to call this a seek.
        assertFalse(axis.follow(dx = 3f, dy = -14f))
        assertFalse(axis.follow(dx = 40f, dy = -150f))
        assertFalse(axis.follow(dx = 40f, dy = -30f))
        assertEquals(false, axis.sideways)
    }

    @Test
    fun aScrubThatComesBackNearWhereItStartedIsStillAScrub() {
        val axis = PictureDragAxis()
        assertTrue(axis.follow(dx = 15f, dy = 2f))
        assertTrue(axis.follow(dx = 300f, dy = 20f))
        assertTrue(axis.follow(dx = 4f, dy = 25f))
        assertEquals(true, axis.sideways)
    }

    @Test
    fun eachDragDecidesItsOwnAxis() {
        val axis = PictureDragAxis()
        assertNull(axis.sideways)
        axis.follow(dx = 15f, dy = 2f)
        axis.reset()
        assertNull(axis.sideways)
        assertFalse(axis.follow(dx = 2f, dy = 15f))
    }

    @Test
    fun aSendIsThroughOnceTheSourceStopsSendingWithoutAnError() {
        assertEquals(DanmakuSendProgress.InFlight, danmakuSendProgress(sending = true, error = null))
        assertEquals(DanmakuSendProgress.Failed, danmakuSendProgress(sending = false, error = "发送失败"))
        // Over before the dialog looked again: a send never seen in flight has still gone through.
        assertEquals(DanmakuSendProgress.Sent, danmakuSendProgress(sending = false, error = null))
        // An earlier failure's reason does not end a send that is still under way.
        assertEquals(DanmakuSendProgress.InFlight, danmakuSendProgress(sending = true, error = "发送失败"))
    }

    @Test
    fun yourOwnLinesNeverCountAsNew() {
        val read = listOf(message(50, mine = false))
        val readMark = chatReadMark(read)
        assertEquals(50L, readMark)
        // A sticker you send: pending under a negative local id, then echoed back under a new one.
        assertFalse(hasUnreadChat(read + message(-1, mine = true), readMark))
        assertFalse(hasUnreadChat(read + message(58, mine = true), readMark))
        assertTrue(hasUnreadChat(read + message(58, mine = true) + message(59, mine = false), readMark))
    }

    @Test
    fun readingIsMeasuredFromTheNewestLineSomeoneElseWrote() {
        // Closed with your own pending line last: the mark is still theirs, so their line is not new
        // again measured against a negative id.
        val closedOn = listOf(message(50, mine = false), message(-1, mine = true))
        assertEquals(50L, chatReadMark(closedOn))
        assertFalse(hasUnreadChat(closedOn, chatReadMark(closedOn)))
        assertNull(chatReadMark(listOf(message(-1, mine = true))))
        assertTrue(hasUnreadChat(listOf(message(7, mine = false)), readMark = null))
        assertFalse(hasUnreadChat(listOf(message(-1, mine = true)), readMark = null))
        assertFalse(hasUnreadChat(emptyList(), readMark = null))
    }

    private fun message(
        id: Long,
        mine: Boolean,
    ) = WatchChatMessage(
        id = id,
        clientId = if (mine) "me" else "c$id",
        name = if (mine) "我" else "用户$id",
        avatarId = 0,
        text = "消息$id",
        sentAtMs = id,
        isMine = mine,
    )
}
