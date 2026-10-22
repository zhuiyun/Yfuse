package com.yfuse.watch

internal val PLATFORM_HOSTS =
    mapOf(
        "爱奇艺" to setOf("iqiyi.com"),
        "优酷" to setOf("youku.com"),
        "腾讯视频" to setOf("v.qq.com"),
        "芒果TV" to setOf("mgtv.com"),
    )

internal const val MAX_IMAGES_PER_SOURCE = 6
internal const val MAX_OCR_IMAGES_PER_SOURCE = 4
internal const val MAX_CALENDAR_IMAGE_BYTES = 12 * 1024 * 1024
internal const val MAX_CALENDAR_EPISODES = 500
internal const val MIN_OCR_PROVIDERS = 2
internal const val MAX_OCR_PROVIDERS = 3
internal const val MIN_PARTIAL_OCR_COORDINATES = 3
internal const val MAX_SOURCES_PER_SHOW = 9
internal const val MAX_EVIDENCE_PER_SERIES = 20

// UTF-8 uses at most three bytes per UTF-16 code unit. HTTP reads also cap bytes at
// three times these character limits, before buffering/decoding a whole response.
internal const val MAX_SOURCE_CHARS = 4_000_000
internal const val MAX_TVMAZE_SCHEDULE_BYTES = 96L * 1024 * 1024
internal val TVMAZE_SHOW_TYPES =
    setOf("Scripted", "Animation", "Documentary", "Reality", "Talk Show", "Game Show", "News", "Sports", "Variety")
internal const val SOURCE_FETCH_ATTEMPTS = 3
internal const val SOURCE_FETCH_RETRY_BASE_MS = 350L
internal const val MAX_OCR_RESULT_CHARS = 4_000_000
internal const val OCR_PROTOCOL_BRIDGE = "Bridge"
internal const val OCR_PROTOCOL_PADDLE_JOBS = "PaddleOcrJobs"
internal const val OCR_PROTOCOL_OCR_SPACE = "OcrSpace"
internal const val PADDLE_OCR_HOST = "paddleocr.aistudio-app.com"
internal const val PADDLE_OCR_JOBS_PATH = "/api/v2/ocr/jobs"
private const val PADDLE_OCR_VL_MODEL = "PaddleOCR-VL-1.6"
internal const val PADDLE_OCR_V6_MODEL = "PP-OCRv6"
private const val PADDLE_STRUCTURE_V3_MODEL = "PP-StructureV3"
internal const val DEFAULT_PADDLE_OCR_MODEL = PADDLE_STRUCTURE_V3_MODEL
internal val SUPPORTED_PADDLE_OCR_MODELS =
    setOf(PADDLE_OCR_VL_MODEL, PADDLE_OCR_V6_MODEL, PADDLE_STRUCTURE_V3_MODEL)
internal const val OCR_SPACE_HOST = "api.ocr.space"
internal const val OCR_SPACE_PARSE_PATH = "/parse/image"
internal const val DEFAULT_OCR_SPACE_ENGINE = 3
internal const val DEFAULT_OCR_SPACE_LANGUAGE = "auto"
internal const val MAX_DISCOVERY_FEEDS = 20
internal const val MAX_DISCOVERED_SHOWS = 200
internal const val MAX_DOMESTIC_CANDIDATES = 500
internal const val MAX_PLATFORM_CATALOGS = 20
internal const val MAX_CANDIDATE_EVIDENCE_TEXT_CHARS = 20_000
internal const val DISCOVERED_SCHEDULE_RETENTION_DAYS = 45
internal const val DISCOVERY_REQUEST_CONCURRENCY = 4
internal const val SHOW_INGESTION_CONCURRENCY = 4
internal const val SOURCE_INGESTION_CONCURRENCY = 6
internal const val OCR_REQUEST_CONCURRENCY = 4
internal const val PADDLE_REQUEST_CONCURRENCY = 1
internal const val PADDLE_SUBMIT_ATTEMPTS = 6
internal const val PADDLE_SUBMIT_RETRY_MILLIS = 2_000L
internal const val PADDLE_QUEUE_FULL_CODE = 10010
internal const val OCR_SPACE_REQUEST_CONCURRENCY = 1
internal const val OCR_SPACE_ATTEMPTS = 3
internal const val OCR_SPACE_RETRY_MILLIS = 1_500L
