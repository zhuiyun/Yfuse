package com.yfuse.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchInviteTest {
    @Test
    fun uri_round_trips_including_a_chinese_title() {
        val invite = WatchInvite(
            roomCode = "ABC234",
            mediaKey = "tmdb:12345",
            title = "奥德赛",
        )
        val parsed = WatchInvite.parse(invite.toUri())
        assertEquals(invite, parsed)
    }

    @Test
    fun uri_round_trips_an_endpoint_with_reserved_characters() {
        val invite = WatchInvite(
            roomCode = "KLM789",
            mediaKey = "imdb:tt0111161",
            title = "The Shawshank Redemption",
            endpoint = "https://watch.example.com:8443/relay",
        )
        assertEquals(invite, WatchInvite.parse(invite.toUri()))
    }

    @Test
    fun parse_rejects_foreign_and_malformed_links() {
        assertNull(WatchInvite.parse("https://example.com/watch/ABC234"))
        assertNull(WatchInvite.parse("yfuse://other/ABC234"))
        // Too short to be a room code.
        assertNull(WatchInvite.parse("yfuse://watch/ABC"))
        assertNull(WatchInvite.parse(""))
    }

    @Test
    fun parse_from_text_finds_the_link_inside_a_pasted_share_block() {
        val shared = WatchInvite(
            roomCode = "PQR345",
            mediaKey = "tmdb:99",
            title = "星海彼岸",
        ).shareText()
        val found = WatchInvite.parseFromText("朋友发来：\n$shared\n快来")
        assertEquals("PQR345", found?.roomCode)
        assertEquals("tmdb:99", found?.mediaKey)
        assertEquals("星海彼岸", found?.title)
    }

    @Test
    fun parse_from_text_accepts_a_bare_code_with_surrounding_words() {
        val found = WatchInvite.parseFromText("房间码：ABC234")
        assertEquals("ABC234", found?.roomCode)
        assertNull(found?.mediaKey)
    }

    @Test
    fun parse_from_text_returns_null_when_there_is_no_code() {
        assertNull(WatchInvite.parseFromText("今晚一起看电影吗"))
        assertNull(WatchInvite.parseFromText("   "))
    }

    @Test
    fun normalize_code_drops_ambiguous_characters_rather_than_guessing() {
        // O and 0, I and 1 are absent from the server's alphabet on purpose; dropping them
        // makes a mistyped code fail visibly instead of joining some other room.
        assertEquals("ABC", WatchInvite.normalizeCode("a-b-c"))
        assertEquals("ABC23", WatchInvite.normalizeCode("ABC023"))
        assertTrue(WatchInvite.isCompleteCode("abc234"))
        assertTrue(!WatchInvite.isCompleteCode("ABC23"))
    }

    @Test
    fun share_text_contains_both_the_code_and_the_link() {
        val text = WatchInvite("XYZ567", "tmdb:5", "沙丘").shareText()
        assertTrue(text.contains("XYZ567"))
        assertTrue(text.contains("yfuse://watch/XYZ567"))
        assertTrue(text.contains("沙丘"))
    }

    @Test
    fun an_episode_without_its_own_ids_is_keyed_by_the_show_and_its_place_in_it() {
        // The case that made cross-server watch-together fail for every series: Emby
        // libraries almost never carry provider ids on individual episodes.
        val key = episodeWatchKey(
            ownProviderIds = emptyMap(),
            seriesProviderIds = mapOf("Tmdb" to "1399"),
            seasonNumber = 2,
            episodeNumber = 5,
            fallbackId = "local-episode-id",
        )
        assertEquals("tmdb:1399/s2e5", key)
        assertEquals(EpisodeCoordinate("tmdb:1399", 2, 5), parseEpisodeWatchKey(key))
    }

    @Test
    fun an_episode_with_its_own_provider_id_keeps_it() {
        // More precise than a coordinate, and it survives a server that numbers differently.
        assertEquals(
            "tmdb:99",
            episodeWatchKey(
                ownProviderIds = mapOf("Tmdb" to "99"),
                seriesProviderIds = mapOf("Tmdb" to "1399"),
                seasonNumber = 2,
                episodeNumber = 5,
                fallbackId = "local",
            ),
        )
    }

    @Test
    fun an_unidentified_show_leaves_the_key_server_local() {
        // Nothing to anchor a coordinate to, so claiming one would match the wrong episode
        // on someone else's server rather than honestly missing.
        assertEquals(
            "emby:local",
            episodeWatchKey(
                ownProviderIds = emptyMap(),
                seriesProviderIds = emptyMap(),
                seasonNumber = 1,
                episodeNumber = 3,
                fallbackId = "local",
            ),
        )
    }

    @Test
    fun a_plain_title_key_is_not_read_as_an_episode() {
        assertNull(parseEpisodeWatchKey("tmdb:603"))
        assertNull(parseEpisodeWatchKey("emby:abc123"))
        assertNull(parseEpisodeWatchKey("tmdb:1399/nonsense"))
    }
}
