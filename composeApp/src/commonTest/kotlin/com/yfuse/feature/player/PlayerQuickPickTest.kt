package com.yfuse.feature.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.russhwolf.settings.MapSettings
import com.yfuse.core.cast.CastDevice
import com.yfuse.core.sync.WatchChatMessage
import com.yfuse.core.sync.WatchStickers
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerQuickPickTest {
    private var nextId = 0L

    private fun sticker(
        id: String,
        mine: Boolean,
    ) = WatchChatMessage(
        id = nextId++,
        clientId = if (mine) "me" else "them",
        name = "",
        avatarId = 0,
        text = WatchStickers.token(WatchStickers.byId(id)!!),
        sentAtMs = 0L,
        isMine = mine,
    )

    @Test
    fun the_fan_offers_what_this_viewer_sends_most_then_the_room_then_defaults() {
        val messages =
            listOf(
                sticker("cat", mine = true),
                sticker("dog", mine = true),
                sticker("dog", mine = true),
                sticker("ghost", mine = false),
                sticker("cat", mine = true),
                sticker("frog", mine = true),
            )
        // cat and dog twice each — cat more recently — then frog; the room's ghost; then defaults.
        assertEquals(
            listOf("cat", "dog", "frog", "ghost", "laugh", "wow"),
            quickStickers(messages).map { it.id },
        )
    }

    @Test
    fun a_quiet_room_gets_the_defaults_and_words_are_not_stickers() {
        val words = sticker("cat", mine = true).copy(text = "你好")
        assertEquals(listOf("laugh", "wow", "clap", "heart", "fire", "cry"), quickStickers(listOf(words)).map { it.id })
    }

    @Test
    fun the_fan_spreads_under_the_key_from_left_to_right() {
        val centers = fanCenters(Offset(500f, 40f), count = 6, radius = 100f)
        assertEquals(6, centers.size)
        assertTrue(centers.first().x < 500f && centers.last().x > 500f)
        assertTrue(centers.all { it.y > 40f })
        centers.forEach { assertTrue(abs((it - Offset(500f, 40f)).getDistance() - 100f) < 0.01f) }
        // Symmetric about the key.
        assertTrue(abs((centers[0].x - 500f) + (centers[5].x - 500f)) < 0.01f)
    }

    @Test
    fun a_fan_against_the_edge_slides_in_whole() {
        val bounds = Rect(0f, 0f, 800f, 400f)
        val centers = fanCenters(Offset(780f, 40f), count = 6, radius = 100f)
        val inside = keepInside(centers, itemRadius = 22f, bounds = bounds, margin = 8f)
        assertTrue(inside.all { it.x + 22f + 8f <= 800.01f })
        // Moved as one: the shape is unchanged.
        val before = centers[1] - centers[0]
        val after = inside[1] - inside[0]
        assertTrue(abs(before.x - after.x) < 0.01f && abs(before.y - after.y) < 0.01f)
        // Already inside: untouched.
        val middle = fanCenters(Offset(400f, 40f), count = 6, radius = 100f)
        assertEquals(middle, keepInside(middle, 22f, bounds, 8f))
    }

    @Test
    fun a_finger_picks_the_nearest_disc_within_reach() {
        val centers = listOf(Offset(0f, 0f), Offset(50f, 0f))
        assertEquals(0, discAt(Offset(20f, 0f), centers, hitRadius = 30f))
        assertEquals(1, discAt(Offset(30f, 0f), centers, hitRadius = 30f))
        assertNull(discAt(Offset(25f, 40f), centers, hitRadius = 30f))
    }

    @Test
    fun the_device_column_hangs_under_the_key_and_has_no_gaps_to_fall_through() {
        val bounds = Rect(0f, 0f, 800f, 400f)
        val rows = columnRects(Rect(700f, 10f, 744f, 54f), 3, rowWidth = 200f, rowHeight = 48f, gap = 6f, bounds, 12f)
        assertEquals(Rect(588f, 60f, 788f, 108f), rows[0])
        assertEquals(114f, rows[1].top)
        assertEquals(0, rowAt(Offset(600f, 110f), rows, gap = 6f, sideSlop = 16f))
        assertEquals(1, rowAt(Offset(600f, 112f), rows, gap = 6f, sideSlop = 16f))
        assertEquals(2, rowAt(Offset(795f, 170f), rows, gap = 6f, sideSlop = 16f))
        assertNull(rowAt(Offset(560f, 80f), rows, gap = 6f, sideSlop = 16f))
        assertNull(rowAt(Offset(600f, 30f), rows, gap = 6f, sideSlop = 16f))
    }

    @Test
    fun recent_devices_come_first_then_what_is_found_now() {
        val targets =
            quickCastTargets(
                recent =
                    listOf(
                        RecentCastTarget(QuickCastKind.Handoff, "tv", "客厅电视"),
                        RecentCastTarget(QuickCastKind.Dlna, "http://box/desc.xml", "盒子"),
                        RecentCastTarget(QuickCastKind.Handoff, "tablet", "平板"),
                    ),
                castDevices =
                    listOf(
                        CastDevice("chromecast:1", "卧室 · Chromecast"),
                        CastDevice("http://box/desc.xml", "客厅盒子"),
                    ),
                receivers = listOf(HandoffReceiver("tv", "客厅电视"), HandoffReceiver("pad", "书房平板")),
                activeCastId = "chromecast:1",
                handoffAllowed = true,
            )
        assertEquals(
            listOf(
                QuickCastTarget(QuickCastKind.Handoff, "tv", "客厅电视", found = true),
                // Its current name, now that the scan has found it again.
                QuickCastTarget(QuickCastKind.Dlna, "http://box/desc.xml", "客厅盒子", found = true),
                // The remembered tablet is offline, so it is left out.
                QuickCastTarget(QuickCastKind.Chromecast, "chromecast:1", "卧室", found = true, active = true),
                QuickCastTarget(QuickCastKind.Handoff, "pad", "书房平板", found = true),
            ),
            targets,
        )
    }

    @Test
    fun a_remembered_cast_device_waits_for_the_scan_and_a_room_rules_out_handoff() {
        val targets =
            quickCastTargets(
                recent =
                    listOf(
                        RecentCastTarget(QuickCastKind.Chromecast, "chromecast:9", "投影仪"),
                        RecentCastTarget(QuickCastKind.Handoff, "tv", "客厅电视"),
                    ),
                castDevices = emptyList(),
                receivers = listOf(HandoffReceiver("tv", "客厅电视")),
                activeCastId = null,
                handoffAllowed = false,
            )
        assertEquals(listOf(QuickCastTarget(QuickCastKind.Chromecast, "chromecast:9", "投影仪", found = false)), targets)
    }

    @Test
    fun the_list_is_capped() {
        val devices = (1..9).map { CastDevice("http://$it", "设备$it") }
        assertEquals(QUICK_CAST_LIMIT, quickCastTargets(emptyList(), devices, emptyList(), null, true).size)
    }

    @Test
    fun the_recent_record_keeps_the_latest_first_without_duplicates_and_survives_odd_names() {
        val store = RecentCastTargets(MapSettings())
        store.record(RecentCastTarget(QuickCastKind.Dlna, "http://a", "A"))
        store.record(RecentCastTarget(QuickCastKind.Handoff, "tv", "电\t视"))
        val after = store.record(RecentCastTarget(QuickCastKind.Dlna, "http://a", "A 改名"))
        assertEquals(
            listOf(
                RecentCastTarget(QuickCastKind.Dlna, "http://a", "A 改名"),
                RecentCastTarget(QuickCastKind.Handoff, "tv", "电 视"),
            ),
            after,
        )
        assertEquals(after, store.load())
        repeat(12) { store.record(RecentCastTarget(QuickCastKind.Dlna, "http://$it", "$it")) }
        assertEquals(RecentCastTargets.RECENT_CAST_LIMIT, store.load().size)
        assertEquals(emptyList(), decodeRecentCastTargets("garbage\nDlna\t\tname"))
    }
}
