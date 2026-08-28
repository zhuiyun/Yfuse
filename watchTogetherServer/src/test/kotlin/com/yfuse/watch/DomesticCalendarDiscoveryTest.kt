package com.yfuse.watch

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DomesticCalendarDiscoveryTest {
    @Test
    fun tmdb_on_air_keeps_only_chinese_drama_candidates() {
        val body =
            """
            {
              "results": [
                {
                  "id": 101,
                  "name": "醒来",
                  "original_name": "醒来",
                  "original_language": "zh",
                  "origin_country": ["CN"],
                  "genre_ids": [18, 9648],
                  "first_air_date": "2026-08-26",
                  "poster_path": "/wake.jpg",
                  "popularity": 52.5
                },
                {
                  "id": 102,
                  "name": "动画候选",
                  "original_language": "zh",
                  "origin_country": ["CN"],
                  "genre_ids": [16],
                  "first_air_date": "2026-08-20"
                },
                {
                  "id": 103,
                  "name": "海外剧",
                  "original_language": "en",
                  "origin_country": ["US"],
                  "genre_ids": [18],
                  "first_air_date": "2026-08-20"
                }
              ]
            }
            """.trimIndent()

        val candidates =
            DomesticCandidateParser.parseTmdbOnAir(
                body = body,
                today = LocalDate.of(2026, 8, 28),
                config = TmdbDomesticCandidateConfig(),
            )

        assertEquals(1, candidates.size)
        assertEquals(101, candidates.single().tmdbId)
        assertEquals("醒来", candidates.single().title)
        assertEquals("/wake.jpg", candidates.single().posterPath)
    }

    @Test
    fun platform_catalog_extracts_titles_but_drops_navigation_labels() {
        val feed =
            PlatformCatalogCandidateFeed(
                platform = "爱奇艺",
                publisher = "爱奇艺电视剧",
                url = "https://www.iqiyi.com/dianshiju/",
            )
        val html =
            """
            <a href="/v/wake.html" title="醒来 热度：7009"><img alt="醒来"></a>
            <a href="/channel/tv">电视剧频道</a>
            <script>window.__DATA__={"albumName":"师兄太稳健","displayName":"立即播放"};</script>
            """.trimIndent()

        val candidates = DomesticCandidateParser.parsePlatformCatalog(html, feed, 2026)

        assertEquals(setOf("醒来", "师兄太稳健"), candidates.map(DomesticShowCandidate::title).toSet())
        assertTrue(candidates.all { it.platforms == listOf("爱奇艺") })
    }

    @Test
    fun candidate_is_bound_to_a_specific_verified_calendar_post() {
        val feed =
            CalendarDiscoveryFeed(
                type = "VerifiedAccount",
                publisherId = "1832974324",
                publisher = "爱奇艺电视剧",
                url = "https://weibo.com/u/1832974324",
                platforms = listOf("爱奇艺"),
            )
        val content =
            """
            {
              "timelineResponses": [
                {
                  "mblogid": "Wake2026A",
                  "text_raw": "#醒来# 会员追剧更新安排请查收",
                  "pics": [{"large_url":"https://wx1.sinaimg.cn/large/calendar.jpg"}]
                },
                {
                  "mblogid": "Other2026A",
                  "text_raw": "今日平台活动更新",
                  "pics": [{"large_url":"https://wx1.sinaimg.cn/large/other.jpg"}]
                }
              ]
            }
            """.trimIndent()
        val candidate =
            DomesticShowCandidate(
                title = "醒来",
                year = 2026,
                tmdbId = 289761,
                posterPath = "/wake.jpg",
                aliases = listOf("醒来"),
                discoveryWeight = 500,
            )

        val shows = discoverCandidateCalendarShowsFromHtml(feed, content, listOf(candidate), 2026)

        assertEquals(1, shows.size)
        assertEquals(289761, shows.single().tmdbId)
        assertEquals("https://weibo.com/1832974324/Wake2026A", shows.single().sources.single().url)
        assertEquals(
            listOf("https://wx1.sinaimg.cn/large/calendar.jpg"),
            shows.single().sources.single().imageUrls,
        )
    }

    @Test
    fun candidate_without_unique_official_mention_is_not_promoted() {
        val feed =
            CalendarDiscoveryFeed(
                type = "VerifiedAccount",
                publisherId = "1642904381",
                publisher = "优酷",
                url = "https://weibo.com/u/1642904381",
                platforms = listOf("优酷"),
            )
        val content =
            """
            {"mblogid":"Generic2026","text_raw":"今日会员更新安排","pics":[]}
            """.trimIndent()
        val candidates =
            listOf(
                DomesticShowCandidate("师兄太稳健", 2026, aliases = listOf("师兄太稳健")),
                DomesticShowCandidate("驸马小仵作", 2026, aliases = listOf("驸马小仵作")),
            )

        assertTrue(discoverCandidateCalendarShowsFromHtml(feed, content, candidates, 2026).isEmpty())
    }

    @Test
    fun tmdb_and_platform_candidates_merge_without_losing_platform() {
        val merged =
            mergeDomesticCandidates(
                candidates =
                    listOf(
                        DomesticShowCandidate(
                            title = "醒来",
                            year = 2026,
                            tmdbId = 289761,
                            posterPath = "/wake.jpg",
                            aliases = listOf("醒来"),
                            discoveryWeight = 500,
                        ),
                        DomesticShowCandidate(
                            title = "醒来",
                            year = 2026,
                            platforms = listOf("爱奇艺"),
                            aliases = listOf("电视剧醒来"),
                            discoveryWeight = 50,
                        ),
                    ),
                maxShows = 20,
            )

        assertEquals(1, merged.size)
        assertEquals(289761, merged.single().tmdbId)
        assertEquals(listOf("爱奇艺"), merged.single().platforms)
        assertTrue("电视剧醒来" in merged.single().aliases)
    }
}
