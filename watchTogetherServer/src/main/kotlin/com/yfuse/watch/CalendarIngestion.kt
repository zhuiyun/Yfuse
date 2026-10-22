package com.yfuse.watch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.absoluteValue

internal val ingestionJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

private data class CapturedCalendarDiscoveryFeed(
    val feed: CalendarDiscoveryFeed,
    val contents: List<String>,
)

private data class DomesticDiscoveryBatch(
    val shows: List<CalendarIngestionShow>,
    val counts: DomesticCandidateCounts,
)

private class CalendarIngestionRuntime(
    private val configFile: File,
    private val outputFile: File?,
    private val tmdbToken: String?,
    private val scheduleStore: CalendarScheduleStore,
    private val ocrCache: CalendarOcrCache,
) {
    private val http =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    private val discoveryRequests = Semaphore(DISCOVERY_REQUEST_CONCURRENCY)
    private val showRequests = Semaphore(SHOW_INGESTION_CONCURRENCY)
    private val sourceRequests = Semaphore(SOURCE_INGESTION_CONCURRENCY)
    private val ocrRequests = Semaphore(OCR_REQUEST_CONCURRENCY)
    private val paddleRequests = Semaphore(PADDLE_REQUEST_CONCURRENCY)
    private val ocrSpaceRequests = Semaphore(OCR_SPACE_REQUEST_CONCURRENCY)
    private val imageFingerprintCache = ConcurrentHashMap<String, String>()

    suspend fun runOnce(): Boolean =
        withContext(Dispatchers.IO) {
            imageFingerprintCache.clear()
            val config = ingestionJson.decodeFromString<CalendarIngestionConfig>(configFile.readText())
            validateConfig(config)
            val generatedAt = Instant.now().toString()
            val existing = readExistingPublication()
            val today = LocalDate.now(ZoneOffset.UTC)
            CalendarIngestionHealth.running(config.shows.size, discoveredShows = 0)
            val domesticDiscovery = discoverShows(config, today)
            val domesticDiscoveredShows = domesticDiscovery.shows
            val overseasDiscoveredShows =
                loadOverseasDiscoveryStream(
                    config.overseas,
                    today,
                ) { url -> openStream(url, MAX_TVMAZE_SCHEDULE_BYTES) }
            val discoveredShows = domesticDiscoveredShows + overseasDiscoveredShows
            CalendarIngestionHealth.discovered(
                domestic = domesticDiscoveredShows.size,
                overseas = overseasDiscoveredShows.size,
                candidates = domesticDiscovery.counts,
            )
            val ingestionShows = mergeIngestionShows(config.shows + discoveredShows)
            CalendarIngestionHealth.registerShows(ingestionShows)
            val fallbackSchedules =
                (existing?.schedules.orEmpty() + DEFAULT_CALENDAR_SCHEDULES)
                    .distinctBy(CalendarSeries::tmdbId)
            val provisionalRevision = nextRevision(existing?.revision, today)
            val refreshedSchedules =
                coroutineScope {
                    ingestionShows
                        .map { show ->
                            async {
                                showRequests.withPermit {
                                    collectShowSchedule(
                                        show = show,
                                        config = config,
                                        fallbackSchedules = fallbackSchedules,
                                        revision = provisionalRevision,
                                        generatedAt = generatedAt,
                                    )
                                }
                            }
                        }.awaitAll()
                        .filterNotNull()
                        .distinctBy(CalendarSeries::tmdbId)
                }
            // Discovery archive pages naturally roll older posts out. Keep their last verified
            // schedule through the useful catch-up window instead of deleting it from the next
            // complete publication merely because the source link left the first page.
            val retentionDate = today.minusDays(DISCOVERED_SCHEDULE_RETENTION_DAYS.toLong()).toString()
            val refreshedIds = refreshedSchedules.map(CalendarSeries::tmdbId).toSet()
            val retainedSchedules =
                existing
                    ?.schedules
                    .orEmpty()
                    .filterNot { it.tmdbId in refreshedIds }
                    // Archive pages roll domestic posts off their first page, so those need a
                    // grace window. Overseas discovery is a complete filtered snapshot: keeping
                    // titles absent from it would preserve excluded news/sports indefinitely.
                    .filter { it.origin == "Domestic" }
                    .filter { schedule ->
                        schedule.episodes.maxOfOrNull(CalendarEpisode::airDate)?.let { it >= retentionDate } == true
                    }.map { it.copy(revision = provisionalRevision) }
            val schedules = (refreshedSchedules + retainedSchedules).distinctBy(CalendarSeries::tmdbId)
            if (schedules.isEmpty()) {
                CalendarIngestionHealth.succeeded(changed = false, publishedShows = 0)
                return@withContext false
            }
            val contentFingerprint = semanticFingerprint(schedules)
            val oldFingerprint = existing?.schedules?.let(::semanticFingerprint)
            if (contentFingerprint == oldFingerprint) {
                CalendarIngestionHealth.succeeded(changed = false, publishedShows = schedules.size)
                return@withContext false
            }
            val publication = CalendarPublication(provisionalRevision, generatedAt, schedules)
            validateCalendarPublication(publication)
            scheduleStore.replace(publication)
            runCatching { writeAtomically(publication) }
                .onFailure { failure ->
                    if (scheduleStore === NoOpCalendarScheduleStore) throw failure
                    ServerLog.warn("calendar_json_snapshot_failed", throwable = failure)
                }
            CalendarIngestionHealth.succeeded(changed = true, publishedShows = schedules.size)
            true
        }

    private suspend fun discoverShows(
        config: CalendarIngestionConfig,
        today: LocalDate,
    ): DomesticDiscoveryBatch =
        coroutineScope {
            val capturedFeeds = async { captureDiscoveryFeeds(config) }
            val tmdbCandidates = async { discoverTmdbDomesticCandidates(config, today) }
            val platformCandidates = async { discoverPlatformDomesticCandidates(config, today.year) }
            val captures = capturedFeeds.await()
            val directShows =
                captures
                    .flatMap { captured ->
                        captured.contents.flatMap { content ->
                            discoverCalendarShowsFromHtml(captured.feed, content, today.year)
                        }
                    }.filter { show ->
                        show.sources.all { source -> runCatching { validateSource(source, config) }.isSuccess }
                    }
            val tmdb = tmdbCandidates.await()
            val platform = platformCandidates.await()
            val candidates =
                if (config.domesticCandidates.enabled) {
                    mergeDomesticCandidates(tmdb + platform, config.domesticCandidates.maxShows)
                } else {
                    emptyList()
                }
            val evidenceMatched =
                captures
                    .filter { it.feed.type == "VerifiedAccount" }
                    .flatMap { captured ->
                        captured.contents.flatMap { content ->
                            discoverCandidateCalendarShowsFromHtml(
                                feed = captured.feed,
                                html = content,
                                candidates = candidates,
                                defaultYear = today.year,
                            )
                        }
                    }.filter { show ->
                        show.sources.all { source -> runCatching { validateSource(source, config) }.isSuccess }
                    }
            val shows = mergeIngestionShows(directShows + evidenceMatched).take(MAX_DISCOVERED_SHOWS)
            DomesticDiscoveryBatch(
                shows = shows,
                counts =
                    DomesticCandidateCounts(
                        tmdb = tmdb.size,
                        platform = platform.size,
                        merged = candidates.size,
                        evidenceMatched = evidenceMatched.distinctBy { normalizeTitle(it.title) }.size,
                    ),
            )
        }

    private suspend fun captureDiscoveryFeeds(config: CalendarIngestionConfig): List<CapturedCalendarDiscoveryFeed> =
        coroutineScope {
            config.discoveryFeeds
                .map { feed ->
                    async {
                        discoveryRequests.withPermit {
                            val raw = fetchText(feed.url)
                            val rendered = config.pageRenderer?.let { fetchRenderedText(feed.url, it) }
                            CapturedCalendarDiscoveryFeed(
                                feed = feed,
                                contents = listOfNotNull(rendered, raw).distinct(),
                            )
                        }
                    }
                }.awaitAll()
        }

    private fun discoverTmdbDomesticCandidates(
        config: CalendarIngestionConfig,
        today: LocalDate,
    ): List<DomesticShowCandidate> {
        val candidateConfig = config.domesticCandidates
        val tmdbConfig = candidateConfig.tmdbOnAir
        if (!candidateConfig.enabled || !tmdbConfig.enabled) return emptyList()
        val token = tmdbToken?.takeIf(String::isNotBlank) ?: return emptyList()
        val encodedLanguage = java.net.URLEncoder.encode(tmdbConfig.language, Charsets.UTF_8)
        return (1..tmdbConfig.maxPages)
            .flatMap { page ->
                val separator = if ('?' in tmdbConfig.endpoint) '&' else '?'
                val url = "${tmdbConfig.endpoint}$separator" + "language=$encodedLanguage&page=$page"
                fetchBearerJson(url, token)
                    ?.let { body -> DomesticCandidateParser.parseTmdbOnAir(body, today, tmdbConfig) }
                    .orEmpty()
            }.distinctBy(DomesticShowCandidate::tmdbId)
            .sortedByDescending(DomesticShowCandidate::discoveryWeight)
            .take(tmdbConfig.maxShows)
    }

    private suspend fun discoverPlatformDomesticCandidates(
        config: CalendarIngestionConfig,
        defaultYear: Int,
    ): List<DomesticShowCandidate> {
        if (!config.domesticCandidates.enabled) return emptyList()
        return coroutineScope {
            config.domesticCandidates.platformCatalogs
                .map { feed ->
                    async {
                        discoveryRequests.withPermit {
                            val raw = fetchText(feed.url)
                            val rendered =
                                if (feed.render) {
                                    config.pageRenderer?.let { fetchRenderedText(feed.url, it) }
                                } else {
                                    null
                                }
                            listOfNotNull(rendered, raw)
                                .distinct()
                                .flatMap { body ->
                                    DomesticCandidateParser.parsePlatformCatalog(body, feed, defaultYear)
                                }.distinctBy { normalizeTitle(it.title) }
                                .take(feed.maxShows)
                        }
                    }
                }.awaitAll()
                .flatten()
        }
    }

    private suspend fun collectShowSchedule(
        show: CalendarIngestionShow,
        config: CalendarIngestionConfig,
        fallbackSchedules: List<CalendarSeries>,
        revision: String,
        generatedAt: String,
    ): CalendarSeries? {
        val fallback =
            fallbackSchedules.firstOrNull { current ->
                current.tmdbId == show.tmdbId || normalizeTitle(current.title) == normalizeTitle(show.title)
            }
        val identity = resolveIdentity(show)
        if (identity == null) {
            logCalendarRejection(show, "identity")
            return fallback
                ?.copy(revision = revision)
                .also { CalendarIngestionHealth.completed(show, if (it == null) "rejected" else "retained") }
        }
        if (show.origin == "Foreign") {
            val compiled = collectOverseasSchedule(show, identity, revision, generatedAt)
            if (compiled == null) logCalendarRejection(show, "overseas-evidence-gate")
            return compiled ?: fallbackSchedules.firstOrNull { it.tmdbId == identity.tmdbId }?.copy(revision = revision)
        }
        val sources =
            coroutineScope {
                show.sources
                    .map { source ->
                        async {
                            sourceRequests.withPermit { captureSource(show, source, config) }
                        }
                    }.awaitAll()
                    .filterNotNull()
            }
        if (sources.isEmpty()) logCalendarRejection(show, "source-empty")
        val compiled = CalendarEvidenceGate.compile(show, identity, sources, revision, generatedAt)
        if (compiled == null) logCalendarRejection(show, "evidence-gate")
        val retained = fallbackSchedules.firstOrNull { it.tmdbId == identity.tmdbId }?.copy(revision = revision)
        return (compiled?.let { mergeCollectedCalendarSchedule(retained, it) } ?: retained).also {
            CalendarIngestionHealth.completed(
                show,
                when {
                    compiled != null -> "published"
                    retained != null -> "retained"
                    else -> "rejected"
                },
            )
        }
    }

    private suspend fun collectOverseasSchedule(
        show: CalendarIngestionShow,
        identity: ResolvedCalendarIdentity,
        revision: String,
        generatedAt: String,
    ): CalendarSeries? =
        coroutineScope {
            val tmdb = async { sourceRequests.withPermit { fetchTmdbSeason(show, identity, generatedAt) } }
            val tvmaze = async { sourceRequests.withPermit { fetchTvmazeEpisodes(show, generatedAt) } }
            OverseasEvidenceGate.compile(
                show = show,
                identity = identity,
                sources = listOfNotNull(tmdb.await(), tvmaze.await()),
                revision = revision,
                generatedAt = generatedAt,
            )
        }

    private fun fetchTmdbSeason(
        show: CalendarIngestionShow,
        identity: ResolvedCalendarIdentity,
        capturedAt: String,
    ): StructuredCalendarSource? {
        val token = tmdbToken?.takeIf(String::isNotBlank) ?: return null
        val url = "https://api.themoviedb.org/3/tv/${identity.tmdbId}/season/${show.seasonNumber}?language=en-US"
        val body = fetchBearerJson(url, token) ?: return null
        return OverseasScheduleParser.parseTmdbSeason(body, url, capturedAt)
    }

    private fun fetchTvmazeEpisodes(
        show: CalendarIngestionShow,
        capturedAt: String,
    ): StructuredCalendarSource? {
        val showId = show.tvmazeId ?: lookupTvmazeId(show.imdbId) ?: return null
        val url = "https://api.tvmaze.com/shows/$showId/episodes?specials=0"
        val body = fetchText(url) ?: return null
        return OverseasScheduleParser.parseTvmazeEpisodes(
            body = body,
            seasonNumber = show.seasonNumber,
            showId = showId,
            timeZoneId = show.timeZoneId,
            capturedAt = capturedAt,
        )
    }

    private fun lookupTvmazeId(imdbId: String?): Int? {
        val normalized = imdbId?.takeIf { it.matches(Regex("tt\\d{5,12}")) } ?: return null
        val body = fetchText("https://api.tvmaze.com/lookup/shows?imdb=$normalized") ?: return null
        return OverseasScheduleParser.parseTvmazeShowId(body)
    }

    private suspend fun captureSource(
        show: CalendarIngestionShow,
        source: CalendarSourceConfig,
        config: CalendarIngestionConfig,
    ): ParsedCalendarSource? {
        validateSource(source, config)
        val capturedAt = Instant.now().toString()
        val rawHtml = fetchText(source.url)
        var html =
            rawHtml ?: fetchRenderedText(source.url, config.pageRenderer) ?: run {
                logCalendarRejection(show, "source-fetch")
                return null
            }
        var pageEpisodes = ChineseScheduleParser.parse(htmlToText(html), show.year, show.accessTier)
        var extractedImages = extractCalendarImages(source.url, html)
        if (
            pageEpisodes.isEmpty() &&
            extractedImages.isEmpty() &&
            source.imageUrls.isEmpty() &&
            rawHtml != null
        ) {
            fetchRenderedText(source.url, config.pageRenderer)?.let { rendered ->
                html = rendered
                pageEpisodes = ChineseScheduleParser.parse(htmlToText(rendered), show.year, show.accessTier)
                extractedImages = extractCalendarImages(source.url, rendered)
            }
        }
        val imageUrls =
            prioritizeCalendarImages(
                if (source.imageUrls.isNotEmpty()) source.imageUrls else extractedImages,
            ).take(MAX_OCR_IMAGES_PER_SOURCE)
        val ocrCaptures = mutableListOf<OcrConsensusCapture>()
        imageUrls.forEach { imageUrl ->
            val imageHash = fetchCalendarImageHash(imageUrl)
            val readings =
                coroutineScope {
                    config.ocrProviders
                        .take(MIN_OCR_PROVIDERS)
                        .map { provider ->
                            async {
                                ocrRequests.withPermit {
                                    ocrReading(provider, imageUrl, imageHash, show)
                                }
                            }
                        }.awaitAll()
                        .filterNotNull()
                }.toMutableList()
            var resolution = OcrConfidenceGate.resolve(readings, show.year, show.accessTier)
            config.ocrProviders
                .drop(MIN_OCR_PROVIDERS)
                .firstOrNull()
                ?.takeIf {
                    resolution.conflict ||
                        resolution.episodes.isEmpty() ||
                        resolution.agreement == OcrAgreement.None
                }?.let { fallback ->
                    ocrRequests.withPermit { ocrReading(fallback, imageUrl, imageHash, show) }
                }?.let(readings::add)
            resolution = OcrConfidenceGate.resolve(readings, show.year, show.accessTier)
            if (readings.size < MIN_OCR_PROVIDERS) {
                val successfulProviders =
                    readings
                        .map(OcrReading::providerId)
                        .sorted()
                        .joinToString("+")
                        .replace(Regex("[^A-Za-z0-9._+-]"), "_")
                        .take(120)
                        .ifBlank { "none" }
                logCalendarRejection(show, "ocr-provider-count-${readings.size}-$successfulProviders")
                return@forEach
            }
            if (resolution.conflict) {
                logCalendarRejection(show, "ocr-conflict")
                return null
            }
            if (resolution.episodes.isEmpty() || resolution.agreement == OcrAgreement.None) {
                logCalendarRejection(show, "ocr-no-consensus")
                return@forEach
            }
            ocrCaptures +=
                OcrConsensusCapture(
                    imageUrl = imageUrl,
                    episodes = resolution.episodes,
                    readingHashes =
                        readings
                            .filter { it.providerId in resolution.providerIds }
                            .map { reading -> reading.providerId to reading.text.sha256() },
                    agreement = resolution.agreement,
                )
        }
        val ocrConsensus = ocrCaptures.isNotEmpty()
        val combined = mergeWithoutConflict(listOf(pageEpisodes) + ocrCaptures.map(OcrConsensusCapture::episodes))
        if (combined == null) {
            logCalendarRejection(show, "source-ocr-conflict")
            return null
        }
        if (combined.isEmpty()) {
            logCalendarRejection(show, "coordinates-empty")
            return null
        }
        val evidenceFingerprint =
            buildString {
                append("official-page-v1:")
                append(html.sha256())
                ocrCaptures.sortedBy(OcrConsensusCapture::imageUrl).forEach { capture ->
                    append('|')
                    append(capture.imageUrl)
                    capture.readingHashes.sortedBy { it.first }.forEach { (providerId, hash) ->
                        append('|')
                        append(providerId)
                        append(':')
                        append(hash)
                    }
                }
            }.sha256()
        return ParsedCalendarSource(
            source = source,
            capturedAt = capturedAt,
            contentHash = evidenceFingerprint,
            episodes = combined,
            ocrConsensus = ocrConsensus,
            ocrAgreement = ocrCaptures.maxByOrNull { ocrConfidenceBonus(it.agreement) }?.agreement ?: OcrAgreement.None,
        )
    }

    private fun resolveIdentity(show: CalendarIngestionShow): ResolvedCalendarIdentity? {
        show.tmdbId?.takeIf { it > 0 }?.let { id ->
            val url = "https://www.themoviedb.org/tv/$id"
            return ResolvedCalendarIdentity(
                id,
                show.title,
                show.posterPath,
                url,
                "$id:${show.title}:${show.year}".sha256(),
            )
        }
        val token = tmdbToken?.takeIf(String::isNotBlank) ?: return null
        if (show.origin == "Foreign" && !show.imdbId.isNullOrBlank()) {
            resolveTmdbIdentityByImdb(show, token)?.let { return it }
        }
        val encodedTitle = java.net.URLEncoder.encode(show.title, Charsets.UTF_8)
        val language = if (show.origin == "Foreign") "en-US" else "zh-CN"
        val url = "https://api.themoviedb.org/3/search/tv?language=$language&query=$encodedTitle&year=${show.year}"
        val body = fetchBearerJson(url, token) ?: return null
        val candidates =
            ingestionJson
                .parseToJsonElement(body)
                .jsonObject["results"]
                ?.jsonArray
                .orEmpty()
        val normalized = normalizeTitle(show.title)
        val exact =
            candidates.mapNotNull { node ->
                val obj = node.jsonObject
                val title = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val original = obj["original_name"]?.jsonPrimitive?.content.orEmpty()
                val date = obj["first_air_date"]?.jsonPrimitive?.content.orEmpty()
                val year = date.take(4).toIntOrNull()
                if (normalizeTitle(title) != normalized && normalizeTitle(original) != normalized) {
                    return@mapNotNull null
                }
                if (year != null && (year - show.year).absoluteValue > 1) return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
                val poster = obj["poster_path"]?.jsonPrimitive?.content
                ResolvedCalendarIdentity(
                    id,
                    title,
                    poster,
                    "https://www.themoviedb.org/tv/$id",
                    body.sha256(),
                )
            }
        return exact.singleOrNull()
    }

    private fun resolveTmdbIdentityByImdb(
        show: CalendarIngestionShow,
        token: String,
    ): ResolvedCalendarIdentity? {
        val imdbId = show.imdbId?.takeIf { it.matches(Regex("tt\\d{5,12}")) } ?: return null
        val url = "https://api.themoviedb.org/3/find/$imdbId?external_source=imdb_id&language=en-US"
        val body = fetchBearerJson(url, token) ?: return null
        val matches =
            ingestionJson
                .parseToJsonElement(body)
                .jsonObject["tv_results"]
                ?.jsonArray
                .orEmpty()
        val match = matches.singleOrNull()?.jsonObject ?: return null
        val id = match["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        return ResolvedCalendarIdentity(
            tmdbId = id,
            title = match["name"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) ?: show.title,
            posterPath = match["poster_path"]?.jsonPrimitive?.content,
            evidenceUrl = "https://www.themoviedb.org/tv/$id",
            evidenceHash = body.sha256(),
        )
    }

    private fun fetchBearerJson(
        url: String,
        token: String,
    ): String? {
        val request =
            HttpRequest
                .newBuilder(URI(url))
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .GET()
                .build()
        val response =
            runCatching { http.send(request, boundedStringBodyHandler(MAX_SOURCE_CHARS * 3)) }.getOrNull()
                ?: return null
        return response.body().takeIf { response.statusCode() in 200..299 && it.length <= MAX_SOURCE_CHARS }
    }

    private fun logCalendarRejection(
        show: CalendarIngestionShow,
        stage: String,
    ) {
        CalendarIngestionHealth.rejected(show, stage)
        val safeTitle = show.title.replace(Regex("[\\r\\n\\t]"), " ").take(120)
        ServerLog.warn("calendar_ingestion_rejected", "title" to safeTitle, "stage" to stage)
    }

    private suspend fun ocr(
        provider: CalendarOcrProviderConfig,
        imageUrl: String,
    ): String? {
        val key = provider.apiKeyEnvironment?.let(System::getenv)
        if (provider.apiKeyEnvironment != null && key.isNullOrBlank()) return null
        return when (provider.protocol) {
            OCR_PROTOCOL_BRIDGE -> bridgeOcr(provider, imageUrl, key)
            OCR_PROTOCOL_PADDLE_JOBS ->
                paddleRequests.withPermit {
                    paddleOcr(provider, imageUrl, key ?: return@withPermit null)
                }
            OCR_PROTOCOL_OCR_SPACE ->
                ocrSpaceRequests.withPermit {
                    ocrSpace(provider, imageUrl, key ?: return@withPermit null)
                }
            else -> null
        }
    }

    private suspend fun ocrReading(
        provider: CalendarOcrProviderConfig,
        imageUrl: String,
        imageHash: String?,
        show: CalendarIngestionShow,
    ): OcrReading? {
        val providerKey = ocrProviderCacheKey(provider)
        val cachedText =
            imageHash?.let { hash ->
                when (val cached = ocrCache.lookup(providerKey, hash)) {
                    is CalendarOcrCacheLookup.Success -> {
                        CalendarIngestionHealth.ocrCacheHit(false)
                        cached.text
                    }
                    CalendarOcrCacheLookup.RecentFailure -> {
                        CalendarIngestionHealth.ocrCacheHit(true)
                        return null
                    }
                    CalendarOcrCacheLookup.Miss -> null
                }
            }
        val text =
            cachedText ?: run {
                CalendarIngestionHealth.ocrProviderRequest()
                val captured = ocr(provider, imageUrl)
                if (imageHash != null) {
                    if (captured == null) {
                        ocrCache.putFailure(providerKey, imageHash)
                    } else {
                        ocrCache.putSuccess(providerKey, imageHash, captured)
                    }
                }
                captured
            }
        return text?.let {
            OcrReading(
                providerId = provider.id,
                text = it,
                episodes = ChineseScheduleParser.parse(it, show.year, show.accessTier),
                independenceGroup = provider.independenceGroup?.takeIf(String::isNotBlank) ?: provider.id,
            )
        }
    }

    private fun fetchCalendarImageHash(imageUrl: String): String? =
        imageFingerprintCache
            .computeIfAbsent(imageUrl) { url ->
                val uri = runCatching { requireHttps(url) }.getOrNull() ?: return@computeIfAbsent ""
                val request =
                    HttpRequest
                        .newBuilder(uri)
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", "YfuseCalendarBot/1.1 (+official-schedule-evidence)")
                        .header("Accept", "image/*")
                        .GET()
                        .build()
                val response =
                    runCatching { http.send(request, HttpResponse.BodyHandlers.ofInputStream()) }
                        .getOrNull() ?: return@computeIfAbsent ""
                val body = response.body().use { it.readNBytes(MAX_CALENDAR_IMAGE_BYTES + 1) }
                if (response.statusCode() !in 200..299 || body.isEmpty() || body.size > MAX_CALENDAR_IMAGE_BYTES) {
                    ""
                } else {
                    body.sha256()
                }
            }.takeIf(String::isNotBlank)

    private fun bridgeOcr(
        provider: CalendarOcrProviderConfig,
        imageUrl: String,
        key: String?,
    ): String? {
        val payload = "{\"imageUrl\":${ingestionJson.encodeToString(imageUrl)}}"
        val builder =
            HttpRequest
                .newBuilder(URI(provider.endpoint))
                .timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
        if (!key.isNullOrBlank()) builder.header("Authorization", "Bearer $key")
        val response =
            runCatching { http.send(builder.build(), boundedStringBodyHandler(MAX_OCR_RESULT_CHARS * 3)) }
                .getOrNull()
                ?: return null
        if (response.statusCode() !in 200..299) return null
        return runCatching {
            ingestionJson
                .parseToJsonElement(response.body())
                .jsonObject["text"]
                ?.jsonPrimitive
                ?.content
        }.getOrNull()?.takeIf(String::isNotBlank)
    }

    private fun paddleOcr(
        provider: CalendarOcrProviderConfig,
        imageUrl: String,
        key: String,
    ): String? {
        val model = provider.model ?: DEFAULT_PADDLE_OCR_MODEL
        val optionalPayload =
            buildJsonObject {
                put("useDocOrientationClassify", false)
                put("useDocUnwarping", false)
                when (model) {
                    PADDLE_OCR_V6_MODEL -> put("useTextlineOrientation", false)
                    else -> put("useChartRecognition", false)
                }
            }
        val payload =
            ingestionJson.encodeToString(
                PaddleOcrSubmitRequest(
                    fileUrl = imageUrl,
                    model = model,
                    optionalPayload = optionalPayload,
                ),
            )
        val submitRequest =
            HttpRequest
                .newBuilder(URI(provider.endpoint))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $key")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()
        var submitResponse: HttpResponse<String>? = null
        for (attempt in 0 until PADDLE_SUBMIT_ATTEMPTS) {
            val response =
                runCatching { http.send(submitRequest, boundedStringBodyHandler(MAX_OCR_RESULT_CHARS * 3)) }.getOrNull()
            if (response != null && response.statusCode() in 200..299) {
                submitResponse = response
                break
            }
            val retryable =
                response == null ||
                    response.statusCode() == 429 ||
                    response.statusCode() >= 500 ||
                    (response.statusCode() == 400 && PaddleOcrResponseParser.isSubmitQueueFull(response.body()))
            if (!retryable || attempt == PADDLE_SUBMIT_ATTEMPTS - 1) return null
            if (!sleepForPaddlePoll(PADDLE_SUBMIT_RETRY_MILLIS * (attempt + 1L))) return null
        }
        val acceptedResponse = submitResponse ?: return null
        val jobId = PaddleOcrResponseParser.submittedJobId(acceptedResponse.body()) ?: return null
        val pollUri = URI(provider.endpoint.trimEnd('/') + "/" + java.net.URLEncoder.encode(jobId, Charsets.UTF_8))
        val deadline = System.nanoTime() + Duration.ofSeconds(provider.pollTimeoutSeconds).toNanos()

        while (System.nanoTime() < deadline) {
            if (!sleepForPaddlePoll(provider.pollIntervalMillis)) return null
            val pollRequest =
                HttpRequest
                    .newBuilder(pollUri)
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer $key")
                    .GET()
                    .build()
            val pollResponse =
                runCatching { http.send(pollRequest, boundedStringBodyHandler(MAX_OCR_RESULT_CHARS * 3)) }.getOrNull()
                    ?: continue
            if (pollResponse.statusCode() !in 200..299) return null
            val snapshot = PaddleOcrResponseParser.jobSnapshot(pollResponse.body()) ?: return null
            when (snapshot.state) {
                "pending", "running" -> Unit
                "done" -> return snapshot.resultUrl?.let(::downloadPaddleResult)
                "failed" -> return null
                else -> return null
            }
        }
        return null
    }

    private fun ocrSpace(
        provider: CalendarOcrProviderConfig,
        imageUrl: String,
        key: String,
    ): String? {
        val form =
            listOf(
                "url" to imageUrl,
                "language" to (provider.language ?: DEFAULT_OCR_SPACE_LANGUAGE),
                "isOverlayRequired" to "false",
                "detectOrientation" to "true",
                "scale" to "true",
                "isTable" to "true",
                "OCREngine" to (provider.engine ?: DEFAULT_OCR_SPACE_ENGINE).toString(),
            ).joinToString("&") { (name, value) ->
                java.net.URLEncoder.encode(name, Charsets.UTF_8) +
                    "=" +
                    java.net.URLEncoder.encode(value, Charsets.UTF_8)
            }
        val request =
            HttpRequest
                .newBuilder(URI(provider.endpoint))
                .timeout(Duration.ofSeconds(provider.pollTimeoutSeconds))
                .header("apikey", key)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build()
        for (attempt in 0 until OCR_SPACE_ATTEMPTS) {
            val response =
                runCatching { http.send(request, boundedStringBodyHandler(MAX_OCR_RESULT_CHARS * 3)) }.getOrNull()
            if (
                response != null &&
                response.statusCode() in 200..299 &&
                response.body().length <= MAX_OCR_RESULT_CHARS
            ) {
                OcrSpaceResponseParser.extractText(response.body())?.let { return it }
                ServerLog.warn(
                    "calendar_ocr_empty",
                    "summary" to OcrSpaceResponseParser.failureSummary(response.body()),
                )
            } else {
                ServerLog.warn(
                    "calendar_ocr_request_failed",
                    "status" to (response?.statusCode() ?: -1),
                    "bytes" to (response?.body()?.length ?: 0),
                )
            }
            val retryable =
                response == null ||
                    response.statusCode() == 429 ||
                    response.statusCode() >= 500 ||
                    response.statusCode() in 200..299
            if (!retryable || attempt == OCR_SPACE_ATTEMPTS - 1) return null
            if (!sleepForPaddlePoll(OCR_SPACE_RETRY_MILLIS * (attempt + 1L))) return null
        }
        return null
    }

    private fun sleepForPaddlePoll(milliseconds: Long): Boolean =
        try {
            Thread.sleep(milliseconds)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private fun downloadPaddleResult(url: String): String? {
        val uri = runCatching { requireHttps(url) }.getOrNull() ?: return null
        val request =
            HttpRequest
                .newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build()
        val response =
            runCatching { http.send(request, boundedStringBodyHandler(MAX_OCR_RESULT_CHARS * 3)) }.getOrNull()
                ?: return null
        if (response.statusCode() !in 200..299 || response.body().length > MAX_OCR_RESULT_CHARS) return null
        return PaddleOcrResponseParser.extractMarkdownText(response.body())
    }

    private fun fetchText(
        url: String,
        maxChars: Int = MAX_SOURCE_CHARS,
    ): String? {
        val uri = runCatching { requireHttps(url) }.getOrNull() ?: return null
        repeat(SOURCE_FETCH_ATTEMPTS) { attempt ->
            val request =
                HttpRequest
                    .newBuilder(uri)
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "YfuseCalendarBot/1.1 (+official-schedule-evidence)")
                    .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.8,*/*;q=0.5")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .GET()
                    .build()
            val response = runCatching { http.send(request, boundedStringBodyHandler(maxChars * 3)) }.getOrNull()
            if (
                response != null &&
                response.statusCode() in 200..299 &&
                response.body().length <= maxChars
            ) {
                return response.body()
            }
            val retryable = response == null || response.statusCode() == 429 || response.statusCode() >= 500
            if (!retryable || attempt == SOURCE_FETCH_ATTEMPTS - 1) return null
            try {
                Thread.sleep(SOURCE_FETCH_RETRY_BASE_MS * (attempt + 1L))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return null
    }

    /**
     * Like [fetchText], but hands over the body as it arrives, capped at [maxBytes]; retries only
     * while no body has been read. The caller closes the stream.
     */
    private fun openStream(
        url: String,
        maxBytes: Long,
    ): InputStream? {
        val uri = runCatching { requireHttps(url) }.getOrNull() ?: return null
        repeat(SOURCE_FETCH_ATTEMPTS) { attempt ->
            val request =
                HttpRequest
                    .newBuilder(uri)
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "YfuseCalendarBot/1.1 (+official-schedule-evidence)")
                    .header("Accept", "application/json")
                    .GET()
                    .build()
            val response = runCatching { http.send(request, HttpResponse.BodyHandlers.ofInputStream()) }.getOrNull()
            if (response != null && response.statusCode() in 200..299) {
                return BoundedInputStream(response.body(), maxBytes)
            }
            response?.body()?.close()
            val retryable = response == null || response.statusCode() == 429 || response.statusCode() >= 500
            if (!retryable || attempt == SOURCE_FETCH_ATTEMPTS - 1) return null
            try {
                Thread.sleep(SOURCE_FETCH_RETRY_BASE_MS * (attempt + 1L))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return null
    }

    private fun fetchRenderedText(
        url: String,
        renderer: CalendarPageRendererConfig?,
    ): String? {
        renderer ?: return null
        val endpoint = runCatching { validateCalendarRendererEndpoint(renderer.endpoint) }.getOrNull() ?: return null
        val key = renderer.apiKeyEnvironment?.let(System::getenv)
        if (renderer.apiKeyEnvironment != null && key.isNullOrBlank()) return null
        val payload = "{\"url\":${ingestionJson.encodeToString(url)}}"
        val builder =
            HttpRequest
                .newBuilder(endpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
        if (!key.isNullOrBlank()) builder.header("Authorization", "Bearer $key")
        val response =
            runCatching { http.send(builder.build(), boundedStringBodyHandler(MAX_SOURCE_CHARS * 3)) }.getOrNull()
                ?: return null
        if (response.statusCode() !in 200..299 || response.body().length > MAX_SOURCE_CHARS) return null
        return runCatching {
            val root = ingestionJson.parseToJsonElement(response.body()).jsonObject
            (root["html"] ?: root["content"])
                ?.jsonPrimitive
                ?.content
                ?.takeIf { it.length <= MAX_SOURCE_CHARS }
        }.getOrNull()
    }

    private fun validateConfig(config: CalendarIngestionConfig) {
        require(config.refreshMinutes in 15..1_440)
        require(config.shows.size <= 1_000)
        require(config.discoveryFeeds.size <= MAX_DISCOVERY_FEEDS)
        val domestic = config.domesticCandidates
        require(domestic.maxShows in 1..MAX_DOMESTIC_CANDIDATES)
        require(domestic.platformCatalogs.size <= MAX_PLATFORM_CATALOGS)
        require(domestic.tmdbOnAir.maxPages in 1..10)
        require(domestic.tmdbOnAir.maxShows in 1..MAX_DOMESTIC_CANDIDATES)
        require(domestic.tmdbOnAir.language.matches(Regex("[a-z]{2}(?:-[A-Z]{2})?")))
        require(domestic.tmdbOnAir.originCountries.size <= 10)
        require(domestic.tmdbOnAir.originCountries.all { it.matches(Regex("[A-Za-z]{2}")) })
        require(domestic.tmdbOnAir.originalLanguages.size <= 10)
        require(domestic.tmdbOnAir.originalLanguages.all { it.matches(Regex("[A-Za-z]{2,3}")) })
        require(domestic.tmdbOnAir.requiredGenreIds.size <= 20)
        require(domestic.tmdbOnAir.requiredGenreIds.all { it > 0 })
        if (domestic.enabled && domestic.tmdbOnAir.enabled) {
            require(!tmdbToken.isNullOrBlank()) { "TMDB token is required for domestic candidate discovery" }
            val tmdbUri = requireHttps(domestic.tmdbOnAir.endpoint)
            require(tmdbUri.host.equals("api.themoviedb.org", true))
            require(tmdbUri.path == "/3/tv/on_the_air")
        }
        domestic.platformCatalogs.forEach { feed ->
            require(feed.publisher.isNotBlank() && feed.publisher.length <= 80)
            require(feed.platform in PLATFORM_HOSTS)
            require(feed.maxShows in 1..100)
            require(feed.accessTier in setOf("Unknown", "Free", "Member", "SviP"))
            feed.titlePattern?.let { pattern ->
                require(pattern.length in 1..1_000)
                require(runCatching { Regex(pattern) }.isSuccess)
            }
            val uri = requireHttps(feed.url)
            require(
                PLATFORM_HOSTS.getValue(feed.platform).any {
                    uri.host.equals(it, true) ||
                        uri.host.endsWith(".$it", true)
                },
            )
        }
        require(config.overseas.pastDays in 0..30)
        require(config.overseas.futureDays in 1..180)
        require(config.overseas.maxShows in 1..500)
        require(config.overseas.countryCodes.size <= 30)
        require(config.overseas.countryCodes.all { it.matches(Regex("[A-Za-z]{2}")) })
        require(config.overseas.allowedShowTypes.isNotEmpty() && config.overseas.allowedShowTypes.size <= 10)
        require(config.overseas.allowedShowTypes.all { it in TVMAZE_SHOW_TYPES })
        if (config.overseas.enabled) {
            require(!tmdbToken.isNullOrBlank()) { "TMDB token is required for overseas identity mapping" }
            val overseasUri = requireHttps(config.overseas.tvmazeFullScheduleUrl)
            require(overseasUri.host.equals("api.tvmaze.com", true))
        }
        validateOcrProviders(config.ocrProviders)
        config.pageRenderer?.let { renderer ->
            validateCalendarRendererEndpoint(renderer.endpoint)
            require(renderer.apiKeyEnvironment?.length?.let { it in 1..120 } != false)
        }
        require(
            config.verifiedAccounts
                .map(VerifiedCalendarAccount::publisherId)
                .distinct()
                .size ==
                config.verifiedAccounts.size,
        )
        config.verifiedAccounts.forEach { account ->
            require(account.publisherId.isNotBlank() && account.publisher.isNotBlank())
            val uri = requireHttps(account.profileUrl)
            require(uri.host.equals("weibo.com", true) || uri.host.endsWith(".weibo.com", true))
            require(account.profileUrl.contains(account.publisherId))
        }
        config.shows.forEach { show ->
            validateShowDefaults(show)
            require(show.sources.size <= MAX_SOURCES_PER_SHOW)
            require(show.origin == "Foreign" || show.sources.isNotEmpty())
        }
        config.discoveryFeeds.forEach { feed ->
            require(feed.publisher.isNotBlank() && feed.publisher.length <= 80)
            require(feed.titlePattern.length in 1..1_000)
            require(runCatching { Regex(feed.titlePattern) }.isSuccess)
            require(feed.maxShows in 1..100)
            validateShowDefaults(
                CalendarIngestionShow(
                    title = "discovered",
                    year = feed.year ?: LocalDate.now(ZoneOffset.UTC).year,
                    seasonNumber = feed.seasonNumber,
                    airTime = feed.airTime,
                    timeZoneId = feed.timeZoneId,
                    platforms = feed.platforms,
                    accessTier = feed.accessTier,
                    sources = emptyList(),
                ),
            )
            validateSource(
                CalendarSourceConfig(
                    type = feed.type,
                    platform = feed.platform,
                    publisherId = feed.publisherId,
                    publisher = feed.publisher,
                    url = feed.url,
                ),
                config,
            )
        }
    }

    private fun validateShowDefaults(show: CalendarIngestionShow) {
        require(show.title.isNotBlank() && show.title.length <= 120)
        require(show.year in 1900..2100)
        require(show.seasonNumber in 1..100)
        require((show.airTime == null) == (show.timeZoneId == null))
        show.airTime?.let { require(runCatching { LocalTime.parse(it) }.isSuccess) }
        show.timeZoneId?.let { require(runCatching { ZoneId.of(it) }.isSuccess) }
        require(show.platforms.isNotEmpty() && show.platforms.size <= 10)
        require(show.platforms.all { it.isNotBlank() && it.length <= 40 })
        require(show.origin in setOf("Domestic", "Foreign"))
        if (show.origin == "Domestic") require(show.platforms.all(PLATFORM_HOSTS::containsKey))
        require(show.availabilityRegion?.matches(Regex("[A-Z]{2}|GLOBAL")) != false)
        require(show.releaseMode in setOf("Scheduled", "Weekly", "Batch", "DateOnly", "Unknown"))
        require(show.discoveryWeight in 0..1_000)
        require(show.tvmazeId?.let { it > 0 } != false)
        require(show.imdbId?.matches(Regex("tt\\d{5,12}")) != false)
        require(show.accessTier in setOf("Unknown", "Free", "Member", "SviP"))
    }

    private fun validateSource(
        source: CalendarSourceConfig,
        config: CalendarIngestionConfig,
    ) {
        require(source.type in setOf("PlatformPage", "VerifiedAccount"))
        val uri = requireHttps(source.url)
        if (source.type == "PlatformPage") {
            val allowed = PLATFORM_HOSTS[source.platform] ?: error("Unsupported calendar platform")
            require(allowed.any { uri.host.equals(it, true) || uri.host.endsWith(".$it", true) })
        } else {
            require(uri.host.equals("weibo.com", true) || uri.host.endsWith(".weibo.com", true))
            val account = config.verifiedAccounts.singleOrNull { it.publisherId == source.publisherId }
            requireNotNull(account) { "Official account is not allowlisted" }
            require(account.publisher == source.publisher)
            require(source.url.contains(account.publisherId))
        }
        source.imageUrls.forEach(::requireHttps)
    }

    private fun readExistingPublication(): CalendarPublication? =
        scheduleStore.current()
            ?: outputFile?.takeIf(File::isFile)?.let { file ->
                runCatching {
                    ingestionJson.decodeFromString<CalendarPublication>(file.readText())
                }.getOrNull()
            }

    private fun writeAtomically(publication: CalendarPublication) {
        val target = outputFile ?: return
        target.absoluteFile.parentFile?.mkdirs()
        val temporary = File(target.absolutePath + ".tmp")
        temporary.writeText(ingestionJson.encodeToString(publication))
        runCatching {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

internal fun CoroutineScope.launchCalendarIngestionFromEnvironment(
    scheduleStore: CalendarScheduleStore = NoOpCalendarScheduleStore,
): Job? {
    val configPath =
        System
            .getenv("YFUSE_CALENDAR_INGEST_CONFIG")
            ?.takeIf(String::isNotBlank)
            ?: return null
    val outputFile =
        System
            .getenv("YFUSE_CALENDAR_SCHEDULES_PATH")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
    if (outputFile == null && scheduleStore === NoOpCalendarScheduleStore) return null
    val cacheFile =
        System
            .getenv("YFUSE_CALENDAR_OCR_CACHE_PATH")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: outputFile?.resolveSibling("calendar-ocr-cache.db")
            ?: File("/var/lib/yfuse/calendar-ocr-cache.db")
    val ocrCache =
        runCatching { CalendarOcrCache.sqlite(cacheFile) }.getOrElse { failure ->
            ServerLog.warn("calendar_ocr_cache_disabled", throwable = failure)
            NoOpCalendarOcrCache
        }
    val runtime =
        CalendarIngestionRuntime(
            configFile = File(configPath),
            outputFile = outputFile,
            tmdbToken = System.getenv("TMDB_TOKEN"),
            scheduleStore = scheduleStore,
            ocrCache = ocrCache,
        )
    // A collection round is minutes of blocking HTTP and polling sleeps. On the server's
    // shared dispatcher that pinned a worker thread the watch sockets also run on.
    return launch(Dispatchers.IO) {
        try {
            while (isActive) {
                try {
                    runtime.runOnce()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    CalendarIngestionHealth.failed(failure)
                    ServerLog.error("calendar_ingestion_failed", throwable = failure)
                }
                val minutes =
                    runCatching {
                        ingestionJson
                            .decodeFromString<CalendarIngestionConfig>(
                                File(configPath).readText(),
                            ).refreshMinutes
                    }.getOrDefault(30)
                delay(minutes.coerceIn(15, 1_440) * 60_000L)
            }
        } finally {
            ocrCache.close()
        }
    }
}

internal fun requireHttps(value: String): URI =
    URI(value).also { uri ->
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) { "HTTPS URL required" }
        require(uri.userInfo == null) { "Credentials are not allowed in calendar URLs" }
    }

internal fun validateCalendarRendererEndpoint(value: String): URI =
    URI(value).also { uri ->
        val isHttps = uri.scheme.equals("https", true)
        val isLoopbackHttp =
            uri.scheme.equals("http", true) &&
                uri.host?.lowercase() in setOf("127.0.0.1", "::1", "localhost")
        require((isHttps || isLoopbackHttp) && !uri.host.isNullOrBlank()) {
            "Renderer endpoint must use HTTPS or loopback HTTP"
        }
        require(uri.userInfo == null) { "Credentials are not allowed in renderer URLs" }
    }

private fun nextRevision(
    previous: String?,
    today: LocalDate,
): String {
    val prefix = today.toString()
    val sequence =
        previous
            ?.takeIf { it.startsWith("$prefix-r") }
            ?.substringAfterLast("-r")
            ?.toIntOrNull()
            ?.plus(1)
            ?: 1
    return "$prefix-r$sequence"
}

internal fun normalizeTitle(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)

internal fun semanticFingerprint(schedules: List<CalendarSeries>): String =
    ingestionJson
        .encodeToString(
            schedules
                .sortedBy(CalendarSeries::tmdbId)
                .map { schedule ->
                    schedule.copy(
                        revision = "",
                        updatedAt = "",
                        evidence = schedule.evidence.map { it.copy(capturedAt = "", contentHash = "") },
                    )
                },
        ).sha256()

internal fun validateOcrProviders(providers: List<CalendarOcrProviderConfig>) {
    require(providers.size <= MAX_OCR_PROVIDERS)
    require(providers.map(CalendarOcrProviderConfig::id).distinct().size == providers.size)
    require(
        providers
            .map { requireHttps(it.endpoint).normalize().toString().trimEnd('/') }
            .distinct()
            .size == providers.size,
    ) { "OCR providers must use distinct endpoints" }
    providers.forEach {
        require(it.id.isNotBlank() && it.id.length <= 40)
        val endpoint = requireHttps(it.endpoint)
        require(it.protocol in setOf(OCR_PROTOCOL_BRIDGE, OCR_PROTOCOL_PADDLE_JOBS, OCR_PROTOCOL_OCR_SPACE))
        require(it.pollIntervalMillis in 500..10_000)
        require(it.pollTimeoutSeconds in 15..300)
        if (it.protocol == OCR_PROTOCOL_PADDLE_JOBS) {
            require(endpoint.host.equals(PADDLE_OCR_HOST, ignoreCase = true)) {
                "PaddleOCR credentials may only be sent to the official API host"
            }
            require(endpoint.path.trimEnd('/') == PADDLE_OCR_JOBS_PATH)
            require(!it.apiKeyEnvironment.isNullOrBlank())
            require((it.model ?: DEFAULT_PADDLE_OCR_MODEL) in SUPPORTED_PADDLE_OCR_MODELS)
        }
        if (it.protocol == OCR_PROTOCOL_OCR_SPACE) {
            require(endpoint.host.equals(OCR_SPACE_HOST, ignoreCase = true)) {
                "OCR.space credentials may only be sent to the official API host"
            }
            require(endpoint.path.trimEnd('/').equals(OCR_SPACE_PARSE_PATH, ignoreCase = true))
            require(!it.apiKeyEnvironment.isNullOrBlank())
            require((it.engine ?: DEFAULT_OCR_SPACE_ENGINE) in 1..3)
            require((it.language ?: DEFAULT_OCR_SPACE_LANGUAGE).matches(Regex("[a-z]{3}|auto")))
        }
    }
}

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") { "%02x".format(it) }

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

private fun ocrProviderCacheKey(provider: CalendarOcrProviderConfig): String =
    listOf(
        provider.id,
        provider.protocol,
        provider.endpoint,
        provider.model.orEmpty(),
        provider.engine?.toString().orEmpty(),
        provider.language.orEmpty(),
        provider.independenceGroup.orEmpty(),
    ).joinToString("|")
        .sha256()
