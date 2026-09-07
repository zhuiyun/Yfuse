package com.yfuse.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchInviteTest {
    @Test
    fun uri_round_trips_including_a_chinese_title() {
        val invite =
            WatchInvite(
                roomCode = "ABC234",
                mediaKey = "tmdb:12345",
                title = "奥德赛",
            )
        val parsed = WatchInvite.parse(invite.toUri())
        assertEquals(invite, parsed)
    }

    @Test
    fun new_invites_never_serialize_a_legacy_relay_endpoint() {
        val uri =
            WatchInvite(
                roomCode = "KLM789",
                mediaKey = "imdb:tt0111161",
                title = "The Shawshank Redemption",
                endpoint = "https://watch.example.com:8443/relay",
            ).toUri()

        assertTrue("e=" !in uri)
        assertNull(WatchInvite.parse(uri)?.endpoint)
    }

    @Test
    fun legacy_invite_endpoint_is_parsed_only_so_non_official_relays_can_be_rejected() {
        val parsed =
            WatchInvite.parse(
                "yfuse://watch/KLM789?e=https%3A%2F%2Fwatch.example.com%3A8443%2Frelay",
            )

        assertEquals("https://watch.example.com:8443/relay", parsed?.endpoint)
        assertEquals("https://watch.example.com:8443/relay", parsed?.unsupportedEndpoint)

        val official =
            WatchInvite.parse(
                "yfuse://watch/KLM789?e=https%3A%2F%2F47.112.219.60",
            )
        assertNull(official?.unsupportedEndpoint)
    }

    @Test
    fun parse_bounds_what_a_foreign_app_can_hand_over() {
        val longTitle = "片".repeat(400)
        val parsed = WatchInvite.parse("yfuse://watch/KLM789?t=$longTitle&k=tmdb:1399/s2e5")
        assertEquals(WatchInvite.MAX_TITLE_CHARS, parsed?.title?.length)
        assertEquals("tmdb:1399/s2e5", parsed?.mediaKey)

        assertNull(WatchInvite.parse("yfuse://watch/KLM789?k=" + "x".repeat(2_000)))
        assertNull(WatchInvite.parse("yfuse://watch/KLM789?k=" + "tmdb:" + "9".repeat(300)))
        assertNull(WatchInvite.parse("yfuse://watch/KLM789?k=not%20a%20key%3Cscript%3E"))
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
        val shared =
            WatchInvite(
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
        val key =
            episodeWatchKey(
                ownProviderIds = emptyMap(),
                seriesProviderIds = mapOf("Tmdb" to "1399"),
                seasonNumber = 2,
                episodeNumber = 5,
                fallbackId = "local-episode-id",
            )
        assertEquals("tmdb:1399/s2e5", key)
        assertEquals(EpisodeCoordinate("tmdb:1399", 2, 5), parseEpisodeWatchKey(key))
    }

    /**
     * The coordinate wins over the episode's own id, because the key has to be the same
     * string on both devices and the episode's own id is the part that differs: one
     * library holds `Tvdb` for it, the other `Tmdb`, and two names for one episode is a
     * room that never syncs.
     */
    @Test
    fun an_identified_show_outranks_the_episodes_own_provider_id() {
        assertEquals(
            "tmdb:1399/s2e5",
            episodeWatchKey(
                ownProviderIds = mapOf("Tmdb" to "99"),
                seriesProviderIds = mapOf("Tmdb" to "1399"),
                seasonNumber = 2,
                episodeNumber = 5,
                fallbackId = "local",
            ),
        )
    }

    /** Two libraries holding different provider ids for the same episode still agree. */
    @Test
    fun differently_scraped_libraries_produce_the_same_episode_key() {
        val host =
            episodeWatchKey(
                ownProviderIds = mapOf("Tvdb" to "7654321"),
                seriesProviderIds = mapOf("Tmdb" to "1399", "Tvdb" to "121361"),
                seasonNumber = 2,
                episodeNumber = 5,
                fallbackId = "host-local-id",
            )
        val guest =
            episodeWatchKey(
                ownProviderIds = emptyMap(),
                seriesProviderIds = mapOf("Tmdb" to "1399"),
                seasonNumber = 2,
                episodeNumber = 5,
                fallbackId = "guest-local-id",
            )

        assertEquals(host, guest)
    }

    /**
     * What a device listens on. The published key is one of these; the room's key was
     * chosen from the other library's metadata, so every name has to be on the list or the
     * two never meet — and a room that never matches is a room where nothing the host does
     * reaches anyone, pause included.
     */
    @Test
    fun an_episode_answers_to_the_coordinate_its_own_ids_and_its_server_local_id() {
        val keys =
            watchMatchKeys(
                ownProviderIds = mapOf("Tvdb" to "7654321"),
                seriesProviderIds = mapOf("Tmdb" to "1399", "Tvdb" to "121361"),
                seasonNumber = 2,
                episodeNumber = 5,
                fallbackId = "local",
            )

        assertEquals(
            listOf(
                "tmdb:1399/s2e5",
                "tvdb:121361/s2e5",
                "tvdb:7654321",
                "emby:local",
            ),
            keys,
        )
    }

    @Test
    fun a_film_answers_to_every_provider_id_its_library_holds() {
        assertEquals(
            listOf("tmdb:603", "imdb:tt0133093", "emby:local"),
            watchMatchKeys(
                ownProviderIds = mapOf("Imdb" to "tt0133093", "Tmdb" to "603"),
                fallbackId = "local",
            ),
        )
    }

    /**
     * An unidentified show still says *which episode*, which is the half of the key the
     * other side can act on: two people who each opened the same series by hand, on
     * libraries sharing no metadata at all, have nothing else to sync on.
     */
    @Test
    fun an_unidentified_show_still_carries_the_coordinate() {
        assertEquals(
            "emby:local/s2e5",
            episodeWatchKey(
                ownProviderIds = mapOf("Tmdb" to "99"),
                seriesProviderIds = emptyMap(),
                seasonNumber = 2,
                episodeNumber = 5,
                fallbackId = "local",
            ),
        )
    }

    @Test
    fun an_entry_that_is_not_numbered_has_no_coordinate_to_write() {
        // A film, or an episode whose library never filled the number in.
        assertEquals(
            "emby:local",
            episodeWatchKey(
                ownProviderIds = emptyMap(),
                seriesProviderIds = emptyMap(),
                seasonNumber = 1,
                episodeNumber = null,
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
