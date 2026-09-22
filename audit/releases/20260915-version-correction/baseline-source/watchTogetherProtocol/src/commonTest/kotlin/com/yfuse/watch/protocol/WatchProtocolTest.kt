package com.yfuse.watch.protocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchProtocolTest {
    @Test
    fun authenticated_v5_and_current_v6_wire_versions_are_supported() {
        assertTrue(WatchProtocol.isSupportedVersion(WatchProtocol.VERSION))
        assertTrue(WatchProtocol.isSupportedVersion(WatchProtocol.VERSION - 1))
        assertFalse(WatchProtocol.isSupportedVersion(WatchProtocol.VERSION - 2))
        assertFalse(WatchProtocol.isSupportedVersion(WatchProtocol.VERSION + 1))
        assertFalse(WatchProtocol.isSupportedVersion(null))
        assertTrue(WatchProtocol.CAPABILITY_VERSION_RANGE in WatchProtocol.SERVER_CAPABILITIES)
    }

    @Test
    fun capabilities_are_fixed_length_base64url_values() {
        assertTrue(WatchProtocol.isValidCapability("a".repeat(43)))
        assertTrue(WatchProtocol.isValidCapability("A0_-" + "b".repeat(39)))
        assertFalse(WatchProtocol.isValidCapability("a".repeat(42)))
        assertFalse(WatchProtocol.isValidCapability("+" + "a".repeat(42)))
    }

    @Test
    fun timeline_rejects_non_finite_out_of_range_and_negative_values() {
        assertTrue(WatchProtocol.isValidTimeline(0L, false, 1f))
        assertFalse(WatchProtocol.isValidTimeline(-1L, false, 1f))
        assertFalse(WatchProtocol.isValidTimeline(Long.MAX_VALUE, false, 1f))
        assertFalse(WatchProtocol.isValidTimeline(1L, false, Float.POSITIVE_INFINITY))
        assertFalse(WatchProtocol.isValidTimeline(1L, false, Float.NaN))
        assertFalse(WatchProtocol.isValidTimeline(1L, false, 0.1f))
    }

    @Test
    fun text_and_media_limits_reject_controls_whitespace_and_oversize_values() {
        assertTrue(WatchProtocol.isValidMediaKey("tmdb:42/s1e2"))
        assertFalse(WatchProtocol.isValidMediaKey(" tmdb:42"))
        assertFalse(WatchProtocol.isValidMediaKey("tmdb:hello world"))
        assertFalse(WatchProtocol.isValidMediaKey("tmdb:${"x".repeat(513)}"))
        assertFalse(WatchProtocol.isValidOptionalName("bad\nname"))
        assertFalse(WatchProtocol.isValidOptionalName("😀".repeat(33)))
        assertFalse(WatchProtocol.isValidAvatarId(WatchProtocol.AVATAR_COUNT))
        assertFalse(WatchProtocol.isValidChat("bad\nmessage"))
        assertFalse(WatchProtocol.isValidChat("😀".repeat(31)))
    }

    @Test
    fun identifiers_room_codes_sequences_and_timestamps_are_bounded() {
        val now = 1_800_000_000_000L
        assertTrue(WatchProtocol.isValidRoomCode("ABC234"))
        assertFalse(WatchProtocol.isValidRoomCode("abc234"))
        assertFalse(WatchProtocol.isValidClientId("client\n2"))
        assertTrue(WatchProtocol.isValidSequence(0L))
        assertFalse(WatchProtocol.isValidSequence(-1L))
        assertTrue(WatchProtocol.isReasonableServerTime(now, now))
        assertFalse(
            WatchProtocol.isReasonableServerTime(
                now + WatchProtocol.MAX_FUTURE_CLOCK_SKEW_MS + 1L,
                now,
            ),
        )
        assertFalse(
            WatchProtocol.isReasonableServerTime(
                now - WatchProtocol.MAX_TIMELINE_POSITION_MS - 1L,
                now,
            ),
        )
    }

    @Test
    fun room_playlist_capability_and_messages_are_advertised() {
        assertTrue(WatchProtocol.CAPABILITY_ROOM_PLAYLIST in WatchProtocol.SERVER_CAPABILITIES)
        assertTrue("playlistAdd" in WatchProtocol.CLIENT_MESSAGE_TYPES)
        assertTrue("playlistUpdate" in WatchProtocol.CLIENT_MESSAGE_TYPES)
        assertTrue("playlistRemove" in WatchProtocol.CLIENT_MESSAGE_TYPES)
        assertTrue("playlistReorder" in WatchProtocol.CLIENT_MESSAGE_TYPES)
    }

    @Test
    fun playlist_entries_enforce_ids_media_titles_and_unique_bounded_lists() {
        val entry = WatchWirePlaylistEntry("episode-1", "tmdb:42/s1e1", "第一集")
        assertTrue(WatchProtocol.isValidPlaylistEntry(entry))
        assertTrue(WatchProtocol.isValidPlaylist(listOf(entry)))
        assertFalse(WatchProtocol.isValidPlaylistEntry(entry.copy(id = "bad id")))
        assertFalse(WatchProtocol.isValidPlaylistEntry(entry.copy(id = "bad\nid")))
        assertFalse(
            WatchProtocol.isValidPlaylistEntry(
                entry.copy(id = "a".repeat(WatchProtocol.MAX_PLAYLIST_ENTRY_ID_BYTES + 1)),
            ),
        )
        assertFalse(WatchProtocol.isValidPlaylistEntry(entry.copy(mediaKey = "tmdb:bad key")))
        assertFalse(
            WatchProtocol.isValidPlaylistEntry(
                entry.copy(
                    mediaKey = "tmdb:${"x".repeat(WatchProtocol.MAX_MEDIA_KEY_BYTES - 4)}",
                ),
            ),
        )
        assertTrue(
            WatchProtocol.isValidPlaylistEntry(
                entry.copy(
                    mediaKey = "tmdb:${"x".repeat(WatchProtocol.MAX_MEDIA_KEY_BYTES - 5)}",
                ),
            ),
        )
        assertFalse(WatchProtocol.isValidPlaylistEntry(entry.copy(title = "bad\ntitle")))
        assertFalse(
            WatchProtocol.isValidPlaylistEntry(
                entry.copy(title = "x".repeat(WatchProtocol.MAX_PLAYLIST_TITLE_BYTES + 1)),
            ),
        )
        assertFalse(WatchProtocol.isValidPlaylist(listOf(entry, entry.copy(title = "重复"))))
        assertFalse(
            WatchProtocol.isValidPlaylist(
                List(WatchProtocol.MAX_PLAYLIST_ENTRIES + 1) { index ->
                    entry.copy(id = "episode-$index")
                },
            ),
        )
        assertTrue(WatchProtocol.isValidPlaylistRevision(0L))
        assertFalse(WatchProtocol.isValidPlaylistRevision(-1L))
    }
}
