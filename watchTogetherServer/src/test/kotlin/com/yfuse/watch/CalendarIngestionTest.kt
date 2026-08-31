package com.yfuse.watch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarIngestionTest {
    @Test
    fun official_archive_discovers_new_show_links_without_a_per_show_manifest() {
        val feed =
            CalendarDiscoveryFeed(
                type = "PlatformPage",
                platform = "优酷",
                publisher = "优酷",
                url = "https://www.youku.com/calendar",
                platforms = listOf("优酷"),
            )
        val shows =
            discoverCalendarShowsFromHtml(
                feed = feed,
                html =
                    """
                    <a href="/show/new-a">《全新电视剧》追剧日历</a>
                    <a href="/show/unrelated">普通综艺节目</a>
                    <a href="https://www.youku.com/show/new-a">《全新电视剧》更新时间</a>
                    """.trimIndent(),
                defaultYear = 2026,
            )

        assertEquals(1, shows.size)
        assertEquals("全新电视剧", shows.single().title)
        assertEquals(2026, shows.single().year)
        assertEquals("https://www.youku.com/show/new-a", shows.single().sources.single().url)
    }

    @Test
    fun discovery_feed_can_use_a_platform_specific_title_pattern() {
        val feed =
            CalendarDiscoveryFeed(
                type = "PlatformPage",
                platform = "爱奇艺",
                publisher = "爱奇艺",
                url = "https://www.iqiyi.com/calendar",
                titlePattern = "剧名[:：]\\s*([^|]+)\\s*\\|\\s*会员排期",
                year = 2027,
                platforms = listOf("爱奇艺"),
            )

        val shows =
            discoverCalendarShowsFromHtml(
                feed,
                "<a aria-label=\"剧名：测试新剧 | 会员排期\" href=\"/v/test.html\"><img></a>",
                defaultYear = 2026,
            )

        assertEquals("测试新剧", shows.single().title)
        assertEquals(2027, shows.single().year)
    }

    @Test
    fun verified_account_timeline_associates_post_body_permalink_and_calendar_image() {
        val feed =
            CalendarDiscoveryFeed(
                type = "VerifiedAccount",
                publisherId = "3752699924",
                publisher = "腾讯电视剧",
                url = "https://weibo.com/u/3752699924",
                platforms = listOf("腾讯视频"),
            )
        val shows =
            discoverCalendarShowsFromHtml(
                feed,
                """
                <article>
                  <a href="/3752699924/QmCalendar01">8月27日 12:00</a>
                  <div>#问心2大结局点映礼# #问心2点映礼# 最新追剧日历请查收！</div>
                  <img src="https://wx1.sinaimg.cn/large/calendar.jpg" alt="图片">
                </article>
                <article>
                  <a href="/3752699924/QmTrailer002">8月27日 13:00</a>
                  <div>#普通预告# 主创花絮送达</div>
                </article>
                """.trimIndent(),
                defaultYear = 2026,
            )

        assertEquals(1, shows.size)
        assertEquals("问心2", shows.single().title)
        assertEquals("https://weibo.com/3752699924/QmCalendar01", shows.single().sources.single().url)
        assertEquals(
            listOf("https://wx1.sinaimg.cn/large/calendar.jpg"),
            shows.single().sources.single().imageUrls,
        )
    }

    @Test
    fun verified_account_json_timeline_discovers_hashtag_calendar_without_normalized_html() {
        val feed =
            CalendarDiscoveryFeed(
                type = "VerifiedAccount",
                publisherId = "1832974324",
                publisher = "爱奇艺电视剧",
                url = "https://weibo.com/u/1832974324",
                platforms = listOf("爱奇艺"),
            )
        val shows =
            discoverCalendarShowsFromHtml(
                feed,
                """
                {
                    "data": {
                      "list": [{
                        "mblogid": "Qiqiyi123",
                        "text_raw": "#逐玉追剧日历# 上新！会员排期请查收",
                        "user": {"profile_image_url": "https://wx2.sinaimg.cn/large/avatar.png"},
                        "pics": [{
                          "url": "https://wx2.sinaimg.cn/thumb150/zhuyu.png",
                          "large": {"url": "https://wx2.sinaimg.cn/large/zhuyu.png"}
                        }]
                      }]
                    }
                }
                """.trimIndent(),
                defaultYear = 2026,
            )

        assertEquals("逐玉", shows.single().title)
        assertEquals("https://weibo.com/1832974324/Qiqiyi123", shows.single().sources.single().url)
        assertEquals(
            listOf("https://wx2.sinaimg.cn/large/zhuyu.png"),
            shows.single().sources.single().imageUrls,
        )
    }

    @Test
    fun verified_account_does_not_treat_hashtag_search_as_official_evidence_page() {
        val feed =
            CalendarDiscoveryFeed(
                type = "VerifiedAccount",
                publisherId = "1642904381",
                publisher = "优酷",
                url = "https://weibo.com/u/1642904381",
                platforms = listOf("优酷"),
            )

        val shows =
            discoverCalendarShowsFromHtml(
                feed,
                "<a href=\"https://s.weibo.com/weibo?q=x\">#千香追剧日历#</a>",
                defaultYear = 2026,
            )

        assertTrue(shows.isEmpty())
    }

    @Test
    fun renderer_endpoint_accepts_local_sidecar_but_rejects_remote_plaintext() {
        assertEquals(
            "127.0.0.1",
            validateCalendarRendererEndpoint("http://127.0.0.1:8091/v1/render").host,
        )
        validateCalendarRendererEndpoint("https://renderer.example.com/v1/render")
        assertFailsWith<IllegalArgumentException> {
            validateCalendarRendererEndpoint("http://renderer.example.com/v1/render")
        }
    }

    @Test
    fun parses_ranges_single_episodes_and_chinese_lists() {
        val parsed =
            ChineseScheduleParser.parse(
                "8月19日更新1～3集；8月20日第4、5集；2026年8月21日第6集",
                defaultYear = 2026,
            )

        assertEquals(
            mapOf(
                1 to "2026-08-19",
                2 to "2026-08-19",
                3 to "2026-08-19",
                4 to "2026-08-20",
                5 to "2026-08-20",
                6 to "2026-08-21",
            ),
            parsed,
        )
    }

    @Test
    fun parses_member_rows_from_structure_markdown_without_mixing_svip_or_tv() {
        val parsed =
            ChineseScheduleParser.parse(
                """
                8月
                17SVIP18:0019集周一会员18:0018集东方卫视19:3011-12集
                18SVIP18:0020集周二会员18:0019集东方卫视19:3013-14集
                19SVIP18:0021集周三会员18:0020集东方卫视19:3015-16集
                20SVIP18:0022-23集周四会员18:0021-22集东方卫视19:3017-18集
                21SVIP18:0024-25集周五会员18:0023-24集东方卫视19:3019集
                22SVIP18:0026-27集周六会员18:0025-26集东方卫视19:3020集
                23SVIP18:0028-29集周日会员18:0027-28集东方卫视19:3021-22集
                """.trimIndent(),
                defaultYear = 2026,
                accessTier = "Member",
            )

        assertEquals(
            mapOf(
                18 to "2026-08-17",
                19 to "2026-08-18",
                20 to "2026-08-19",
                21 to "2026-08-20",
                22 to "2026-08-20",
                23 to "2026-08-21",
                24 to "2026-08-21",
                25 to "2026-08-22",
                26 to "2026-08-22",
                27 to "2026-08-23",
                28 to "2026-08-23",
            ),
            parsed,
        )
    }

    @Test
    fun update_counts_are_not_misread_as_episode_coordinates() {
        assertEquals(
            emptyMap(),
            ChineseScheduleParser.parse(
                "8月26日起 VIP会员19:30更新4集；8月27日起 VIP会员每日19:30更新2集",
                defaultYear = 2026,
                accessTier = "Member",
            ),
        )
    }

    @Test
    fun partial_ocr_subset_accepts_the_more_complete_non_conflicting_map() {
        val complete = (1..6).associateWith { "2026-08-${(18 + it).toString().padStart(2, '0')}" }
        val partial = complete.filterKeys { it <= 3 }
        val resolution =
            OcrConfidenceGate.resolve(
                listOf(
                    OcrReading("paddle", "calendar", partial),
                    OcrReading("ocr-space", "calendar", complete),
                ),
                defaultYear = 2026,
                accessTier = "Member",
            )

        assertEquals(OcrAgreement.PartialSubset, resolution.agreement)
        assertEquals(complete, resolution.episodes)
    }

    @Test
    fun cadence_corroboration_accepts_exact_cells_from_one_ocr() {
        val episodes =
            buildMap {
                (1..4).forEach { put(it, "2026-08-26") }
                (5..6).forEach { put(it, "2026-08-27") }
                (7..8).forEach { put(it, "2026-08-28") }
                (9..10).forEach { put(it, "2026-08-29") }
                (11..12).forEach { put(it, "2026-08-30") }
            }
        val rule = "8月26日起 VIP会员19:30更新4集；8月27日起 VIP会员每日19:30更新2集"
        val resolution =
            OcrConfidenceGate.resolve(
                listOf(
                    OcrReading("paddle", rule, emptyMap()),
                    OcrReading("ocr-space", rule, episodes),
                ),
                defaultYear = 2026,
                accessTier = "Member",
            )

        assertEquals(OcrAgreement.SemanticCorroboration, resolution.agreement)
        assertEquals(episodes, resolution.episodes)
    }

    @Test
    fun wake_calendar_real_ocr_text_resolves_member_schedule() {
        val paddle =
            "8月26日起CCTV-8黄金强档每晚两集连播8月26日起VIP会员19:30更新4集" +
                "8月27日起VIP会员每日19：30更新2集未完待续"
        val ocrSpace =
            """
            周三
            26
            1-2集
            爱奇艺VIP会员 1-4集
            周四
            27
            3-4集
            爱奇艺VIP会员 5-6集
            周五
            28
            5-6集
            爱奇艺VIP会员 7-8集
            周六
            29
            7-8集
            爱奇艺VIP会员9-10集
            周日
            30
            9-10集
            爱奇艺VIP会员 11-12集
            8月26日起 CCTV-8黄金强档每晚两集连播
            8月26日起 VIP会员19:30更新4集
            8月27日起 VIP会员每日19:30更新2集
            """.trimIndent()
        val expected =
            buildMap {
                (1..4).forEach { put(it, "2026-08-26") }
                (5..6).forEach { put(it, "2026-08-27") }
                (7..8).forEach { put(it, "2026-08-28") }
                (9..10).forEach { put(it, "2026-08-29") }
                (11..12).forEach { put(it, "2026-08-30") }
            }
        val parsed = ChineseScheduleParser.parse(ocrSpace, 2026, "Member")
        assertEquals(expected, parsed)

        val resolution =
            OcrConfidenceGate.resolve(
                listOf(
                    OcrReading("paddle", paddle, emptyMap()),
                    OcrReading("ocr-space", ocrSpace, parsed),
                ),
                defaultYear = 2026,
                accessTier = "Member",
            )
        assertEquals(OcrAgreement.SemanticCorroboration, resolution.agreement)
        assertEquals(expected, resolution.episodes)
    }

    @Test
    fun daily_calendar_grid_is_rebuilt_when_ocr_flattens_rows_differently() {
        val ocrSpace =
            """
            爱奇艺VIP会员 追剧日历 8月 周一 周二 周三 周四 周五 周六 周日
            17 18 19 20今日开播 21 22 23
            1-4集 5-6集 7-8集 9-10集
            24 25 26 27 会员收官 28 29 30
            20集 21集 11-12集 13-14集 15-16集 17-18集 19集
            8月20日起,VIP会员首更4集,每日19:30更新2集
            """.trimIndent()
        val paddle =
            "8月20日起，VIP会员首更4集，每日19：30更新2集 " +
                "1-4集 5-6集 7-8集 9-10集 11-12集 13-14集 15-16集 17-18集 19集 20集 21集"
        val expected =
            buildMap {
                listOf(1..4, 5..6, 7..8, 9..10, 11..12, 13..14, 15..16, 17..18, 19..19, 20..20, 21..21)
                    .forEachIndexed { dayOffset, episodes ->
                        val date = java.time.LocalDate.of(2026, 8, 20).plusDays(dayOffset.toLong()).toString()
                        episodes.forEach { put(it, date) }
                    }
            }
        val resolution =
            OcrConfidenceGate.resolve(
                listOf(
                    OcrReading("ocr-space", ocrSpace, ChineseScheduleParser.parse(ocrSpace, 2026, "Member")),
                    OcrReading("paddle", paddle, ChineseScheduleParser.parse(paddle, 2026, "Member")),
                ),
                defaultYear = 2026,
                accessTier = "Member",
            )

        assertEquals(OcrAgreement.SemanticCorroboration, resolution.agreement)
        assertEquals(expected, resolution.episodes)
    }

    @Test
    fun full_series_corroboration_accepts_contiguous_episode_range() {
        val episodes = (1..24).associateWith { "2026-08-26" }
        val resolution =
            OcrConfidenceGate.resolve(
                listOf(
                    OcrReading("paddle", "8月26日12:00 VIP会员看全集", emptyMap()),
                    OcrReading("ocr-space", "8月26日12:00 VIP会员看全集 1-24集", episodes),
                ),
                defaultYear = 2026,
                accessTier = "Member",
            )

        assertEquals(OcrAgreement.SemanticCorroboration, resolution.agreement)
        assertEquals(episodes, resolution.episodes)
    }

    @Test
    fun full_series_corroboration_builds_coordinates_from_one_ocr_range() {
        val resolution =
            OcrConfidenceGate.resolve(
                listOf(
                    OcrReading(
                        "paddle",
                        "8月26日12:00 VIP会员看全集 部分剧集内容可供非会员观看",
                        emptyMap(),
                    ),
                    OcrReading(
                        "ocr-space",
                        "8月26日12:00 VIP会员看全集 部分剧集内容可供非会员观看 VIP 1-24集",
                        emptyMap(),
                    ),
                ),
                defaultYear = 2026,
                accessTier = "Member",
            )

        assertEquals(OcrAgreement.SemanticCorroboration, resolution.agreement)
        assertEquals((1..24).associateWith { "2026-08-26" }, resolution.episodes)
    }

    @Test
    fun any_shared_episode_date_conflict_still_fails_closed() {
        val resolution =
            OcrConfidenceGate.resolve(
                listOf(
                    OcrReading("paddle", "", mapOf(1 to "2026-08-26")),
                    OcrReading("ocr-space", "", mapOf(1 to "2026-08-27")),
                ),
                defaultYear = 2026,
                accessTier = "Member",
            )

        assertTrue(resolution.conflict)
        assertEquals(OcrAgreement.None, resolution.agreement)
    }

    @Test
    fun third_independent_provider_can_resolve_a_two_provider_conflict() {
        val expected = mapOf(1 to "2026-08-26", 2 to "2026-08-27", 3 to "2026-08-28")
        val resolution =
            OcrConfidenceGate.resolve(
                listOf(
                    OcrReading("paddle", "", expected),
                    OcrReading("ocr-space", "", expected + (1 to "2026-08-25")),
                    OcrReading("third", "", expected),
                ),
                defaultYear = 2026,
                accessTier = "Member",
            )

        assertEquals(OcrAgreement.Majority, resolution.agreement)
        assertEquals(expected, resolution.episodes)
        assertEquals(setOf("paddle", "third"), resolution.providerIds)
    }

    @Test
    fun providers_in_the_same_independence_group_do_not_form_consensus() {
        val episodes = mapOf(1 to "2026-08-26")
        val resolution =
            OcrConfidenceGate.resolve(
                listOf(
                    OcrReading("model-a", "", episodes, independenceGroup = "same-upstream"),
                    OcrReading("model-b", "", episodes, independenceGroup = "same-upstream"),
                ),
                defaultYear = 2026,
                accessTier = "Member",
            )

        assertEquals(OcrAgreement.None, resolution.agreement)
        assertTrue(resolution.episodes.isEmpty())
    }

    @Test
    fun calendar_image_priority_prefers_calendar_and_large_variants() {
        val prioritized =
            prioritizeCalendarImages(
                listOf(
                    "https://wx1.sinaimg.cn/thumbnail/poster.jpg",
                    "https://wx1.sinaimg.cn/large/poster.jpg",
                    "https://wx1.sinaimg.cn/large/calendar.jpg",
                ),
            )

        assertEquals("https://wx1.sinaimg.cn/large/calendar.jpg", prioritized.first())
        assertEquals(2, prioritized.size)
    }

    @Test
    fun verified_account_plus_explicit_coordinates_and_tmdb_is_official() {
        val series =
            CalendarEvidenceGate.compile(
                show = show(),
                identity = identity(),
                sources = listOf(source(mapOf(1 to "2026-08-19", 2 to "2026-08-20"))),
                revision = "2026-08-26-r1",
                generatedAt = "2026-08-26T04:00:00Z",
            )

        assertEquals("Official", series?.authority)
        assertEquals(80, series?.confidence)
        assertEquals(listOf(1, 2), series?.episodes?.map(CalendarEpisode::episodeNumber))
    }

    @Test
    fun dual_ocr_consensus_is_recorded_and_capped_at_one_hundred() {
        val series =
            CalendarEvidenceGate.compile(
                show = show(),
                identity = identity(),
                sources = listOf(source(mapOf(1 to "2026-08-19"), ocr = true)),
                revision = "2026-08-26-r1",
                generatedAt = "2026-08-26T04:00:00Z",
            )

        assertEquals(100, series?.confidence)
        assertEquals(
            listOf("VerifiedAccount", "OcrConsensus", "TmdbIdentity"),
            series?.evidence?.map(CalendarEvidence::type),
        )
    }

    @Test
    fun conflicting_official_coordinates_fail_closed() {
        val series =
            CalendarEvidenceGate.compile(
                show = show(),
                identity = identity(),
                sources =
                    listOf(
                        source(mapOf(1 to "2026-08-19")),
                        source(mapOf(1 to "2026-08-20")).copy(
                            source = source(mapOf()).source.copy(url = "https://weibo.com/7758737065/post/2"),
                        ),
                    ),
                revision = "2026-08-26-r1",
                generatedAt = "2026-08-26T04:00:00Z",
            )

        assertNull(series)
    }

    @Test
    fun more_than_twenty_evidence_records_fail_closed() {
        val sources =
            (1..10).map { index ->
                source(mapOf(index to "2026-08-${index.toString().padStart(2, '0')}"), ocr = true).copy(
                    source = source(emptyMap()).source.copy(url = "https://weibo.com/7758737065/post/$index"),
                    contentHash = index.toString().padStart(64, 'a'),
                )
            }

        val series =
            CalendarEvidenceGate.compile(
                show = show(),
                identity = identity(),
                sources = sources,
                revision = "2026-08-26-r1",
                generatedAt = "2026-08-26T04:00:00Z",
            )

        assertNull(series)
    }

    @Test
    fun capture_timestamp_and_hash_do_not_change_the_semantic_fingerprint() {
        val first =
            CalendarEvidenceGate.compile(
                show(),
                identity(),
                listOf(source(mapOf(1 to "2026-08-19"))),
                "2026-08-26-r1",
                "2026-08-26T04:00:00Z",
            )!!
        val second =
            first.copy(
                revision = "2026-08-26-r2",
                updatedAt = "2026-08-26T05:00:00Z",
                evidence = first.evidence.map { it.copy(capturedAt = "2026-08-26T05:00:00Z", contentHash = "b".repeat(64)) },
            )

        assertEquals(semanticFingerprint(listOf(first)), semanticFingerprint(listOf(second)))
    }

    @Test
    fun dual_ocr_requires_distinct_providers_and_endpoints() {
        assertFailsWith<IllegalArgumentException> {
            validateOcrProviders(
                listOf(
                    CalendarOcrProviderConfig("ocr-a", "https://ocr.example.com/read"),
                    CalendarOcrProviderConfig("ocr-b", "https://ocr.example.com/read/"),
                ),
            )
        }
        validateOcrProviders(listOf(CalendarOcrProviderConfig("ocr-a", "https://ocr-a.example.com/read")))
    }

    @Test
    fun paddle_ocr_parser_reads_job_and_markdown_results() {
        assertEquals(
            "job-123",
            PaddleOcrResponseParser.submittedJobId("""{"data":{"jobId":"job-123"}}"""),
        )
        assertEquals(
            "8月26日\nVIP会员\n1-24集",
            PaddleOcrResponseParser.extractMarkdownText(
                """{"result":{"ocrResults":[{"prunedResult":{"rec_texts":["8月26日","VIP会员","1-24集"]}}]}}""",
            ),
        )
        assertEquals(
            PaddleOcrJobSnapshot("done", "https://example.com/result.jsonl"),
            PaddleOcrResponseParser.jobSnapshot(
                """{"data":{"state":"done","resultUrl":{"jsonUrl":"https://example.com/result.jsonl"}}}""",
            ),
        )
        assertEquals(
            "8月19日更新1～3集\n8月20日更新第4集",
            PaddleOcrResponseParser.extractMarkdownText(
                """
                {"result":{"layoutParsingResults":[{"markdown":{"text":"8月19日更新1～3集","images":{}}}]}}
                {"result":{"layoutParsingResults":[{"markdown":{"text":"8月20日更新第4集","images":{}}}]}}
                """.trimIndent(),
            ),
        )
        assertTrue(
            PaddleOcrResponseParser.isSubmitQueueFull(
                """{"code":10010,"msg":"任务提交队列已满，请稍后重试"}""",
            ),
        )
        assertTrue(!PaddleOcrResponseParser.isSubmitQueueFull("""{"code":10001}"""))
    }

    @Test
    fun paddle_ocr_token_is_restricted_to_official_endpoint() {
        validateOcrProviders(
            listOf(
                CalendarOcrProviderConfig(
                    id = "paddle",
                    endpoint = "https://paddleocr.aistudio-app.com/api/v2/ocr/jobs",
                    apiKeyEnvironment = "YFUSE_CALENDAR_PADDLEOCR_TOKEN",
                    protocol = "PaddleOcrJobs",
                    model = "PaddleOCR-VL-1.6",
                ),
            ),
        )
        validateOcrProviders(
            listOf(
                CalendarOcrProviderConfig(
                    id = "paddle-structure",
                    endpoint = "https://paddleocr.aistudio-app.com/api/v2/ocr/jobs",
                    apiKeyEnvironment = "YFUSE_CALENDAR_PADDLEOCR_TOKEN",
                    protocol = "PaddleOcrJobs",
                    model = "PP-StructureV3",
                ),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            validateOcrProviders(
                listOf(
                    CalendarOcrProviderConfig(
                        id = "paddle",
                        endpoint = "https://attacker.example/api/v2/ocr/jobs",
                        apiKeyEnvironment = "YFUSE_CALENDAR_PADDLEOCR_TOKEN",
                        protocol = "PaddleOcrJobs",
                    ),
                ),
            )
        }
    }

    @Test
    fun ocr_space_parser_combines_pages_and_rejects_processing_errors() {
        assertEquals(
            "8月19日更新1～3集\n8月20日更新第4集",
            OcrSpaceResponseParser.extractText(
                """
                {
                  "ParsedResults": [
                    {"ParsedText": "8月19日更新1～3集"},
                    {"ParsedText": "8月20日更新第4集"}
                  ],
                  "OCRExitCode": 1,
                  "IsErroredOnProcessing": false
                }
                """.trimIndent(),
            ),
        )
        assertNull(
            OcrSpaceResponseParser.extractText(
                """{"ParsedResults":[],"OCRExitCode":3,"IsErroredOnProcessing":true}""",
            ),
        )
    }

    @Test
    fun ocr_space_key_is_restricted_to_official_endpoint() {
        validateOcrProviders(
            listOf(
                CalendarOcrProviderConfig(
                    id = "ocr-space",
                    endpoint = "https://api.ocr.space/parse/image",
                    apiKeyEnvironment = "YFUSE_CALENDAR_OCR_SPACE_KEY",
                    protocol = "OcrSpace",
                    engine = 3,
                    language = "auto",
                ),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            validateOcrProviders(
                listOf(
                    CalendarOcrProviderConfig(
                        id = "ocr-space",
                        endpoint = "https://attacker.example/parse/image",
                        apiKeyEnvironment = "YFUSE_CALENDAR_OCR_SPACE_KEY",
                        protocol = "OcrSpace",
                    ),
                ),
            )
        }
    }

    @Test
    fun tvmaze_full_schedule_discovers_foreign_show_without_inventing_streaming_time() {
        val shows =
            OverseasScheduleParser.discoverTvmazeShows(
                body =
                    """
                    [{
                      "season": 2,
                      "number": 3,
                      "airdate": "2026-08-28",
                      "airtime": "",
                      "_embedded": {"show": {
                        "id": 901,
                        "name": "Example Global Drama",
                        "type": "Scripted",
                        "weight": 95,
                        "premiered": "2025-09-01",
                        "externals": {"imdb": "tt1234567"},
                        "network": null,
                        "webChannel": {"name": "Netflix", "country": null},
                        "schedule": {"time": ""}
                      }}
                    }]
                    """.trimIndent(),
                today = java.time.LocalDate.of(2026, 8, 27),
                config = OverseasCalendarConfig(enabled = true),
            )

        val show = shows.single()
        assertEquals("Foreign", show.origin)
        assertEquals("GLOBAL", show.availabilityRegion)
        assertEquals("DateOnly", show.releaseMode)
        assertNull(show.airTime)
        assertNull(show.timeZoneId)
        assertEquals(901, show.tvmazeId)
    }

    @Test
    fun tvmaze_airstamp_keeps_utc_and_beijing_instants() {
        val source =
            OverseasScheduleParser.parseTvmazeEpisodes(
                body =
                    """
                    [{
                      "season": 1,
                      "number": 4,
                      "airdate": "2026-08-27",
                      "airtime": "21:00",
                      "airstamp": "2026-08-28T01:00:00+00:00"
                    }]
                    """.trimIndent(),
                seasonNumber = 1,
                showId = 901,
                timeZoneId = "America/New_York",
                capturedAt = "2026-08-27T12:00:00Z",
            )!!

        val episode = source.episodes.getValue(4)
        assertEquals("2026-08-27", episode.airDate)
        assertEquals("2026-08-28T01:00:00Z", episode.releaseAtUtc)
        assertEquals("2026-08-28T09:00+08:00", episode.releaseAtBeijing)
        assertEquals("21:00", source.airTime)
    }

    @Test
    fun tvmaze_discovery_excludes_news_and_sports() {
        val shows =
            OverseasScheduleParser.discoverTvmazeShows(
                body =
                    """
                    [{
                      "season": 1,
                      "number": 1,
                      "airdate": "2026-08-28",
                      "_embedded": {"show": {
                        "id": 902,
                        "name": "Daily News",
                        "type": "News",
                        "weight": 100,
                        "premiered": "2026-01-01",
                        "network": {"name": "Network", "country": {"code": "US", "timezone": "America/New_York"}},
                        "webChannel": null
                      }}
                    }]
                    """.trimIndent(),
                today = java.time.LocalDate.of(2026, 8, 28),
                config = OverseasCalendarConfig(enabled = true),
            )

        assertTrue(shows.isEmpty())
    }

    @Test
    fun tmdb_and_tvmaze_agreement_is_verified_at_eighty_five() {
        val tmdb =
            StructuredCalendarSource(
                type = "TmdbSchedule",
                publisher = "TMDB",
                sourceUrl = "https://api.themoviedb.org/3/tv/1/season/1",
                capturedAt = "2026-08-27T12:00:00Z",
                contentHash = HASH,
                episodes = mapOf(1 to CalendarEpisode(1, "2026-08-28")),
            )
        val tvmaze =
            StructuredCalendarSource(
                type = "TvmazeSchedule",
                publisher = "TVmaze",
                sourceUrl = "https://www.tvmaze.com/shows/901",
                capturedAt = "2026-08-27T12:00:00Z",
                contentHash = "b".repeat(64),
                episodes = mapOf(1 to CalendarEpisode(1, "2026-08-28")),
            )
        val foreign =
            CalendarIngestionShow(
                title = "Example Global Drama",
                year = 2026,
                tmdbId = 1,
                tvmazeId = 901,
                airTime = null,
                timeZoneId = null,
                platforms = listOf("Netflix"),
                accessTier = "Unknown",
                origin = "Foreign",
                availabilityRegion = "GLOBAL",
                releaseMode = "DateOnly",
                sources = emptyList(),
            )
        val series =
            OverseasEvidenceGate.compile(
                foreign,
                ResolvedCalendarIdentity(1, foreign.title, null, "https://www.themoviedb.org/tv/1", HASH),
                listOf(tmdb, tvmaze),
                "2026-08-27-r1",
                "2026-08-27T12:00:00Z",
            )

        assertEquals("Verified", series?.authority)
        assertEquals(85, series?.confidence)
        assertEquals("Foreign", series?.origin)
    }

    @Test
    fun conflicting_tmdb_and_tvmaze_dates_are_not_published() {
        val show =
            CalendarIngestionShow(
                title = "Conflict",
                year = 2026,
                tmdbId = 1,
                airTime = null,
                timeZoneId = null,
                platforms = listOf("Network"),
                accessTier = "Unknown",
                origin = "Foreign",
                sources = emptyList(),
            )
        val sources =
            listOf(
                StructuredCalendarSource("TmdbSchedule", "TMDB", "https://www.themoviedb.org/tv/1", HASH, HASH, mapOf(1 to CalendarEpisode(1, "2026-08-28"))),
                StructuredCalendarSource("TvmazeSchedule", "TVmaze", "https://www.tvmaze.com/shows/1", HASH, HASH, mapOf(1 to CalendarEpisode(1, "2026-08-29"))),
            )

        assertNull(
            OverseasEvidenceGate.compile(
                show,
                ResolvedCalendarIdentity(1, show.title, null, "https://www.themoviedb.org/tv/1", HASH),
                sources,
                "2026-08-27-r1",
                "2026-08-27T12:00:00Z",
            ),
        )
    }

    private fun show() =
        CalendarIngestionShow(
            title = "师兄太稳健",
            year = 2026,
            tmdbId = 272938,
            platforms = listOf("优酷", "爱奇艺"),
            sources = emptyList(),
        )

    private fun identity() =
        ResolvedCalendarIdentity(
            tmdbId = 272938,
            title = "师兄太稳健",
            posterPath = "/poster.jpg",
            evidenceUrl = "https://www.themoviedb.org/tv/272938",
            evidenceHash = HASH,
        )

    private fun source(
        episodes: Map<Int, String>,
        ocr: Boolean = false,
    ) = ParsedCalendarSource(
        source =
            CalendarSourceConfig(
                type = "VerifiedAccount",
                publisherId = "7758737065",
                publisher = "师兄太稳健官微",
                url = "https://weibo.com/7758737065/post/1",
            ),
        capturedAt = "2026-08-26T04:00:00Z",
        contentHash = HASH,
        episodes = episodes,
        ocrConsensus = ocr,
    )

    private companion object {
        const val HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
