package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DanmakuTest {

    @Test
    fun preferences_survive_recreation() {
        val settings = MapSettings()
        val first = DanmakuPreferences(settings).apply {
            addSource("夏天", "https://example.com/{title}/{id}")
            addSource("alpha2", "https://danmaku.example.com")
            setEnabled(false)
            setDisplayArea(DanmakuDisplayArea.ThreeQuarters)
            setFontSize(DanmakuFontSize.Large)
            setSpeed(DanmakuSpeed.Fast)
            setOpacity(DanmakuOpacity.High)
        }
        val second = first.sources.value[1]
        first.selectSource(second.id)

        val restored = DanmakuPreferences(settings)

        assertEquals(listOf("夏天", "alpha2"), restored.sources.value.map { it.name })
        assertEquals(second.id, restored.activeSourceId.value)
        assertEquals("alpha2", restored.activeSource()?.name)
        assertFalse(restored.enabled.value)
        assertEquals(DanmakuDisplayArea.ThreeQuarters, restored.displayArea.value)
        assertEquals(DanmakuFontSize.Large, restored.fontSize.value)
        assertEquals(DanmakuSpeed.Fast, restored.speed.value)
        assertEquals(DanmakuOpacity.High, restored.opacity.value)
    }

    @Test
    fun the_single_stored_link_becomes_the_first_source() {
        val settings = MapSettings()
        settings.putString("danmaku.urlTemplate", "https://example.com/{id}")

        val migrated = DanmakuPreferences(settings)

        assertEquals(1, migrated.sources.value.size)
        assertEquals("https://example.com/{id}", migrated.sources.value.first().url)
        assertEquals(migrated.sources.value.first().id, migrated.activeSourceId.value)
        // The list is now authoritative: deleting the last source has to stick, rather
        // than falling back to the key it was seeded from.
        migrated.removeSource(migrated.sources.value.first().id)
        assertTrue(DanmakuPreferences(settings).sources.value.isEmpty())
    }

    @Test
    fun the_first_source_added_is_the_one_in_use() {
        val prefs = DanmakuPreferences(MapSettings())
        val only = prefs.addSource("", "https://danmaku.example.com")

        assertEquals(only?.id, prefs.activeSourceId.value)
        // A blank name still has to name something in a chip row.
        assertEquals("弹幕源 1", only?.name)
        assertTrue(only?.supportsSearch == true)
    }

    @Test
    fun deleting_a_source_drops_the_matches_that_named_it() {
        val prefs = DanmakuPreferences(MapSettings())
        val kept = requireNotNull(prefs.addSource("kept", "https://a.example.com"))
        val dropped = requireNotNull(prefs.addSource("dropped", "https://b.example.com"))
        prefs.bind("item-1", DanmakuBinding(kept.id, "e1", "A - 第1集"))
        prefs.bind("item-2", DanmakuBinding(dropped.id, "e2", "B - 第2集"))

        prefs.removeSource(dropped.id)

        assertEquals(listOf("item-1"), prefs.bindings.value.keys.toList())
        assertEquals(kept.id, prefs.activeSourceId.value)
    }

    @Test
    fun a_deleted_active_source_hands_off_rather_than_leaving_nothing_selected() {
        val prefs = DanmakuPreferences(MapSettings())
        val first = requireNotNull(prefs.addSource("first", "https://a.example.com"))
        val second = requireNotNull(prefs.addSource("second", "https://b.example.com"))

        prefs.removeSource(first.id)

        assertEquals(second.id, prefs.activeSourceId.value)
        assertEquals("second", prefs.activeSource()?.name)
    }

    @Test
    fun a_stale_active_id_still_resolves_to_a_usable_source() {
        val sources = listOf(DanmakuSource("a", "A", "https://a.example.com"))

        assertEquals("A", sources.activeOr("gone")?.name)
        assertEquals(null, emptyList<DanmakuSource>().activeOr("a"))
    }

    @Test
    fun a_server_address_is_searchable_and_a_template_is_not() {
        assertTrue(DanmakuSource("a", "A", "https://a.example.com").supportsSearch)
        assertFalse(DanmakuSource("b", "B", "https://b.example.com/d?id={id}").supportsSearch)
    }

    @Test
    fun resolves_and_encodes_media_placeholders() {
        val url = DanmakuRepository.resolveUrl(
            "https://example.com/{serverId}/{id}?title={title}&season={season}&episode={episode}",
            DanmakuMedia(
                id = "a/b",
                title = "测试 标题",
                episode = 7,
                season = 2,
                serverId = "server 1",
            ),
        )

        assertEquals(
            "https://example.com/server%201/a%2Fb?title=%E6%B5%8B%E8%AF%95%20%E6%A0%87%E9%A2%98&season=2&episode=7",
            url,
        )
    }

    @Test
    fun parses_bilibili_xml() {
        val comments = DanmakuParser.parse(
            """
            <i>
              <d p="1.5,1,25,16711680,0,0,0,0">滚动 &amp; 测试</d>
              <d p="2.0,5,25,16777215,0,0,0,0">顶部</d>
              <d p="3.0,4,25,255,0,0,0,0">底部</d>
            </i>
            """.trimIndent(),
        )

        assertEquals(3, comments.size)
        assertEquals(1_500L, comments[0].timeMs)
        assertEquals("滚动 & 测试", comments[0].text)
        assertEquals(0xFF0000, comments[0].color)
        assertEquals(DanmakuKind.Top, comments[1].kind)
        assertEquals(DanmakuKind.Bottom, comments[2].kind)
    }

    @Test
    fun parses_dplayer_and_common_json() {
        val comments = DanmakuParser.parse(
            """
            {
              "data": [
                [1.25, 0, 16777215, "alice", "滚动"],
                [2.5, 1, 65280, "bob", "顶部"]
              ],
              "comments": [
                {"progress": 3750, "content": "对象格式", "mode": 4, "color": "#0000ff"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(3, comments.size)
        assertEquals(listOf(1_250L, 2_500L, 3_750L), comments.map { it.timeMs })
        assertEquals(DanmakuKind.Top, comments[1].kind)
        assertEquals(DanmakuKind.Bottom, comments[2].kind)
        assertEquals(0x0000FF, comments[2].color)
        assertTrue(comments.all { it.text.isNotBlank() })
    }

    @Test
    fun parses_dandanplay_comments() {
        val comments = DanmakuParser.parse(
            """
            {
              "count": 3,
              "comments": [
                {"cid": 1, "p": "12.5,1,16711680,1234567", "m": "滚动"},
                {"cid": 2, "p": "13,5,16777215,1234567", "m": "顶部"},
                {"cid": 3, "p": "14,4,255,1234567", "m": "底部"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(3, comments.size)
        assertEquals(12_500L, comments[0].timeMs)
        // Four fields, so the colour is at index 2 — the same attribute in Bilibili's XML
        // carries a font size there and puts the colour one further along.
        assertEquals(0xFF0000, comments[0].color)
        assertEquals(DanmakuKind.Top, comments[1].kind)
        assertEquals(DanmakuKind.Bottom, comments[2].kind)
        assertEquals(0x0000FF, comments[2].color)
    }

    @Test
    fun parses_a_search_response() {
        val results = DanmakuApi.parseSearch(
            """
            {"animes":[
              {"animeId":1,"animeTitle":"九门(2021)【电影】","typeDescription":"电影",
               "episodeCount":1,"startDate":"2021-05-01T00:00:00"},
              {"animeId":2,"animeTitle":"九门(2026)【电视剧】","typeDescription":"电视剧",
               "episodeCount":8,"year":2026}
            ]}
            """.trimIndent(),
        )

        assertEquals(2, results.size)
        assertEquals("1", results[0].animeId)
        assertEquals("电影 · 1 集 · 2021", results[0].subtitle)
        assertEquals("电视剧 · 8 集 · 2026", results[1].subtitle)
    }

    @Test
    fun parses_an_episode_list_nested_under_bangumi() {
        val episodes = DanmakuApi.parseEpisodes(
            """
            {"bangumi":{"episodes":[
              {"episodeId":1001,"episodeTitle":"第1集 九门 01","episodeNumber":"1"},
              {"episodeId":1004,"episodeTitle":"第4集 九门 04","episodeNumber":"4"}
            ]}}
            """.trimIndent(),
            "九门(2026)【电视剧】",
        )

        assertEquals(2, episodes.size)
        assertEquals("1004", episodes[1].episodeId)
        assertEquals("九门(2026)【电视剧】- 第4集 九门 04", episodes[1].label)
    }

    @Test
    fun an_automatic_match_takes_the_episode_being_played() {
        val body = """
            {"animes":[{"animeId":2,"animeTitle":"九门(2026)","episodes":[
              {"episodeId":1001,"episodeTitle":"第1集","episodeNumber":"1"},
              {"episodeId":1004,"episodeTitle":"第4集","episodeNumber":"4"}
            ]}]}
        """.trimIndent()

        assertEquals("1004", DanmakuApi.parseMatch(body, episodeNumber = 4)?.episodeId)
        // No number to go on — the server's own ordering is the only answer left.
        assertEquals("1001", DanmakuApi.parseMatch(body, episodeNumber = null)?.episodeId)
        assertEquals(null, DanmakuApi.parseMatch("{\"animes\":[]}", episodeNumber = 1))
    }

    @Test
    fun a_server_root_is_found_whether_or_not_the_api_path_was_pasted() {
        with(DanmakuRepository) {
            assertEquals(
                "https://d.example.com",
                DanmakuSource("a", "A", "https://d.example.com/api/v2/").apiRoot(),
            )
            assertEquals(
                "https://d.example.com/danmu",
                DanmakuSource("a", "A", "https://d.example.com/danmu").apiRoot(),
            )
            // A template addresses one file; there is no index behind it to search.
            assertEquals(null, DanmakuSource("a", "A", "https://d.example.com/{id}").apiRoot())
        }
    }

    @Test
    fun repeated_lines_collapse_into_one_with_a_count() {
        val comments = DanmakuFilter.merge(
            listOf(
                DanmakuComment(1_000L, "笑死"),
                DanmakuComment(2_000L, "笑死"),
                DanmakuComment(3_000L, "别的话"),
                DanmakuComment(4_000L, "笑死"),
            ),
        )

        assertEquals(2, comments.size)
        // The earliest survives: a comment reacts to something that just happened, so the
        // first one is the one whose timing is right.
        assertEquals(1_000L, comments[0].timeMs)
        assertEquals(3, comments[0].repeats)
        assertEquals("笑死 ×3", comments[0].displayText)
        assertEquals(1, comments[1].repeats)
        assertEquals("别的话", comments[1].displayText)
    }

    @Test
    fun the_same_line_far_apart_stays_two_comments() {
        val comments = DanmakuFilter.merge(
            listOf(
                DanmakuComment(0L, "片头曲好听"),
                DanmakuComment(21_000L, "片头曲好听"),
            ),
            windowMs = 20_000L,
        )

        assertEquals(2, comments.size)
        assertTrue(comments.all { it.repeats == 1 })
    }

    @Test
    fun blocked_words_match_anywhere_in_the_line_and_ignore_case() {
        val comments = DanmakuFilter.apply(
            comments = listOf(
                DanmakuComment(0L, "前面有剧透注意"),
                DanmakuComment(1_000L, "SPOILER ahead"),
                DanmakuComment(2_000L, "画面真好"),
            ),
            merge = false,
            blockedWords = listOf("剧透", "spoiler"),
        )

        assertEquals(listOf("画面真好"), comments.map { it.text })
    }

    @Test
    fun an_episode_match_is_keyed_on_the_show_so_it_survives_changing_servers() {
        val onServerA = danmakuBindingKey(
            itemId = "aaa",
            title = "九门 第4集",
            seriesName = "九门",
            seasonNumber = 1,
            episodeNumber = 4,
        )
        val onServerB = danmakuBindingKey(
            itemId = "bbb",
            title = "Jiu Men E04",
            seriesName = " 九门 ",
            seasonNumber = 1,
            episodeNumber = 4,
        )

        assertEquals(onServerA, onServerB)
    }

    @Test
    fun a_different_episode_is_a_different_key() {
        val fourth = danmakuBindingKey("a", "x", seriesName = "九门", episodeNumber = 4)
        val fifth = danmakuBindingKey("a", "x", seriesName = "九门", episodeNumber = 5)

        assertTrue(fourth != fifth)
    }

    @Test
    fun a_film_is_keyed_on_its_title_and_anything_nameless_on_its_id() {
        assertEquals(
            danmakuBindingKey("aaa", "Blade Runner 2049"),
            danmakuBindingKey("bbb", "blade  runner 2049"),
        )
        assertEquals("i:only-an-id", danmakuBindingKey("only-an-id", "   "))
    }

    @Test
    fun recent_searches_are_newest_first_deduplicated_and_capped() {
        val prefs = DanmakuPreferences(MapSettings())
        repeat(10) { prefs.rememberSearch("片 $it") }
        prefs.rememberSearch("片 0")

        val recent = prefs.recentSearches.value

        assertEquals(8, recent.size)
        assertEquals("片 0", recent.first())
        assertEquals(recent.size, recent.distinct().size)
    }

    @Test
    fun blocked_words_survive_recreation_and_ignore_duplicates() {
        val settings = MapSettings()
        DanmakuPreferences(settings).apply {
            addBlockedWord("剧透")
            addBlockedWord(" 剧透 ")
            addBlockedWord("")
        }

        assertEquals(listOf("剧透"), DanmakuPreferences(settings).blockedWords.value)
    }
}
