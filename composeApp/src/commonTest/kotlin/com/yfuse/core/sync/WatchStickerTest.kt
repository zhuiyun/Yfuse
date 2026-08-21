package com.yfuse.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchStickerTest {
    private fun message(text: String) =
        WatchChatMessage(
            id = 1L,
            clientId = "c1",
            name = "用户",
            avatarId = 0,
            text = text,
            sentAtMs = 1L,
            isMine = false,
        )

    @Test
    fun expanded_catalogue_keeps_64_presets_across_every_category_and_motion_variety() {
        assertEquals(64, WatchStickers.presets.size)
        WatchStickerCategory.entries.forEach { category ->
            assertTrue(WatchStickers.inCategory(category).isNotEmpty(), "$category must not be empty")
        }
        val motions = WatchStickers.presets.map { it.motion }.toSet()
        assertTrue(WatchStickerMotion.Bounce in motions)
        assertTrue(WatchStickerMotion.Shake in motions)
        assertTrue(WatchStickerMotion.Spin in motions)
        assertTrue(WatchStickerMotion.Pulse in motions)
        assertTrue(WatchStickerMotion.Swing in motions)
        assertTrue(WatchStickerMotion.Wobble in motions)
        assertTrue(WatchStickerMotion.Float in motions)
        assertTrue(WatchStickerMotion.Jelly in motions)
        assertTrue(WatchStickerMotion.Flip in motions)
        assertTrue(WatchStickerMotion.Pop in motions)
        assertTrue(WatchStickerMotion.Heartbeat in motions)
        assertTrue(WatchStickerMotion.Orbit in motions)
        assertTrue(WatchStickerMotion.Sway in motions)
    }

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
            val validation = validateWatchChat(token)
            assertNull(validation.error, "token rejected by production chat validation: $token")
            assertEquals(token, validation.text)
        }
    }

    @Test
    fun production_chat_validation_enforces_its_shared_grapheme_limit() {
        assertNull(validateWatchChat("字".repeat(MAX_WATCH_CHAT_GRAPHEMES)).error)
        assertEquals(
            "每条消息最多 $MAX_WATCH_CHAT_GRAPHEMES 字",
            validateWatchChat("字".repeat(MAX_WATCH_CHAT_GRAPHEMES + 1)).error,
        )
    }

    @Test
    fun legacy_ids_keep_resolving_after_catalogue_expansion() {
        listOf(
            "laugh",
            "wow",
            "love",
            "cry",
            "clap",
            "fire",
            "think",
            "dead",
            "heart",
            "rofl",
            "cool",
            "shock",
            "sleep",
            "sweat",
            "angry",
            "blush",
            "thumb",
            "pray",
            "wave",
            "muscle",
            "eyes",
            "party",
            "star",
            "sparkle",
            "popcorn",
            "cheers",
            "rocket",
            "spin",
            "bomb",
            "snow",
            "cat",
            "ghost",
        ).forEach { id ->
            assertEquals(id, WatchStickers.byId(id)?.id)
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
