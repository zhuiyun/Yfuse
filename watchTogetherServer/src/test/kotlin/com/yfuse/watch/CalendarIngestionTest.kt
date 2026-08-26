package com.yfuse.watch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarIngestionTest {
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
