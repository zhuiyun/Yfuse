package com.yfuse.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchStickerTest {

    /**
     * What [WatchTogetherClient] will accept in one message. Its own copy is private, so this
     * is a transcription — if that limit ever drops, this test is what notices that half the
     * tray has quietly become unsendable.
     */
    private val maxChatGraphemes = 30

    private fun message(text: String) = WatchChatMessage(
        id = 1L,
        clientId = "c1",
        name = "用户",
        avatarId = 0,
        text = text,
        sentAtMs = 1L,
        isMine = false,
    )

    @Test
    fun every_preset_round_trips_through_the_wire_token() {
        WatchStickers.presets.forEach { sticker ->
            assertEquals(sticker, WatchStickers.parse(WatchStickers.token(sticker)))
        }
    }

    @Test
    fun preset_ids_are_unique_and_the_token_fits_a_chat_message() {
        val ids = WatchStickers.presets.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "sticker ids must be unique")
        WatchStickers.presets.forEach { sticker ->
            // A token longer than a message is a sticker that cannot be sent, and the tray
            // would offer it anyway — the failure would only show up on tap.
            val token = WatchStickers.token(sticker)
            assertTrue(token.length <= maxChatGraphemes, "token too long to send: $token")
        }
    }

    @Test
    fun only_a_whole_token_is_a_sticker() {
        assertNull(WatchStickers.parse("看看这个 [sticker:laugh]"))
        assertNull(WatchStickers.parse("[sticker:laugh] 哈哈"))
        assertNull(WatchStickers.parse("[sticker:]"))
        assertNull(WatchStickers.parse("laugh"))
        // An id this build has never heard of stays text rather than becoming a blank bubble.
        assertNull(WatchStickers.parse("[sticker:not-a-real-preset]"))
    }

    @Test
    fun surrounding_whitespace_survives_the_trip() {
        assertEquals(WatchStickers.byId("laugh"), WatchStickers.parse("  [sticker:laugh] "))
    }

    @Test
    fun a_message_reports_the_sticker_it_carries() {
        val sticker = WatchStickers.byId("party")!!
        assertEquals(sticker, message(WatchStickers.token(sticker)).sticker)
        assertNull(message("晚点再看").sticker)
    }

    @Test
    fun one_line_text_draws_something_for_both_kinds() {
        val sticker = WatchStickers.byId("party")!!
        assertEquals("${sticker.glyph} ${sticker.label}", message(WatchStickers.token(sticker)).oneLineText)
        assertEquals("晚点再看", message("晚点再看").oneLineText)
    }
}
