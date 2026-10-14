package com.yfuse.watch

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

internal fun htmlToText(html: String): String =
    html
        .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
        .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("&#(\\d+);")) { match ->
            match.groupValues[1]
                .toIntOrNull()
                ?.takeIf { it in Char.MIN_VALUE.code..Char.MAX_VALUE.code }
                ?.toChar()
                ?.toString()
                ?: match.value
        }.replace(Regex("&#x([0-9a-fA-F]+);")) { match ->
            match.groupValues[1]
                .toIntOrNull(
                    16,
                )?.takeIf { it in Char.MIN_VALUE.code..Char.MAX_VALUE.code }
                ?.toChar()
                ?.toString()
                ?: match.value
        }

private data class CalendarArchiveAnchor(
    val start: Int,
    val end: Int,
    val url: String,
    val label: String,
)

private data class CalendarArchiveCandidate(
    val title: String,
    val sourceUrl: String,
    val imageUrls: List<String> = emptyList(),
    val priority: Int = 0,
)

internal fun discoverCalendarShowsFromHtml(
    feed: CalendarDiscoveryFeed,
    html: String,
    defaultYear: Int,
): List<CalendarIngestionShow> {
    val titleRegex = runCatching { Regex(feed.titlePattern, RegexOption.IGNORE_CASE) }.getOrNull() ?: return emptyList()
    val base = runCatching { URI(feed.url) }.getOrNull() ?: return emptyList()
    val anchors = extractCalendarArchiveAnchors(base, html)
    val candidates = mutableListOf<CalendarArchiveCandidate>()

    anchors.forEach { anchor ->
        val title = extractCalendarArchiveTitle(anchor.label, titleRegex) ?: return@forEach
        if (!isUsableCalendarSource(feed, anchor.url)) return@forEach
        candidates += CalendarArchiveCandidate(title, anchor.url, priority = 20)
    }

    // On verified-account timelines the permanent post link, body and images are separate DOM
    // nodes. Segmenting from one permanent link to the next keeps them associated without
    // depending on Weibo's frequently-changing CSS class names.
    val permalinks =
        if (feed.type == "VerifiedAccount") {
            anchors
                .filter { isUsableCalendarSource(feed, it.url) }
                .distinctBy(CalendarArchiveAnchor::url)
        } else {
            emptyList()
        }
    permalinks.forEachIndexed { index, anchor ->
        val end = permalinks.getOrNull(index + 1)?.start ?: html.length
        if (end <= anchor.start || end - anchor.start > MAX_ARCHIVE_ENTRY_CHARS) return@forEachIndexed
        val entryHtml = html.substring(anchor.start, end)
        val title = extractCalendarArchiveTitle(htmlToText(entryHtml), titleRegex) ?: return@forEachIndexed
        candidates +=
            CalendarArchiveCandidate(
                title = title,
                sourceUrl = anchor.url,
                imageUrls = extractArchiveEntryImages(base, entryHtml),
                priority = 100,
            )
    }

    discoverCalendarArchiveJson(feed, html, titleRegex, base).forEach(candidates::add)

    return candidates
        .asSequence()
        .filter { it.title.length in 1..120 }
        .sortedWith(
            compareByDescending<CalendarArchiveCandidate> { it.priority }
                .thenByDescending { it.imageUrls.size },
        ).distinctBy { normalizeTitle(it.title) to (feed.year ?: defaultYear) }
        .take(feed.maxShows)
        .map { candidate ->
            CalendarIngestionShow(
                title = candidate.title,
                year = feed.year?.takeIf { it in 1900..2100 } ?: defaultYear,
                seasonNumber = feed.seasonNumber,
                airTime = feed.airTime,
                timeZoneId = feed.timeZoneId,
                platforms = feed.platforms,
                accessTier = feed.accessTier,
                sources =
                    listOf(
                        CalendarSourceConfig(
                            type = feed.type,
                            platform = feed.platform,
                            publisherId = feed.publisherId,
                            publisher = feed.publisher,
                            url = candidate.sourceUrl,
                            imageUrls = candidate.imageUrls,
                        ),
                    ),
            )
        }.toList()
}

/**
 * Uses a broad candidate set to recover official posts whose wording does not fit the generic
 * title pattern. A candidate still needs one unambiguous mention in an allowlisted account post
 * plus a schedule/update signal; the returned source then goes through the normal dual-OCR gate.
 */
internal fun discoverCandidateCalendarShowsFromHtml(
    feed: CalendarDiscoveryFeed,
    html: String,
    candidates: List<DomesticShowCandidate>,
    defaultYear: Int,
): List<CalendarIngestionShow> {
    if (feed.type != "VerifiedAccount" || candidates.isEmpty()) return emptyList()
    val base = runCatching { URI(feed.url) }.getOrNull() ?: return emptyList()
    val matches = mutableListOf<Pair<DomesticShowCandidate, CalendarArchiveCandidate>>()

    fun addEvidence(
        text: String,
        sourceUrl: String,
        imageUrls: List<String>,
        priority: Int,
    ) {
        if (!CALENDAR_CANDIDATE_EVIDENCE_KEYWORDS.containsMatchIn(text)) return
        if (!isUsableCalendarSource(feed, sourceUrl)) return
        val candidate = matchDomesticCandidate(text, candidates) ?: return
        matches += candidate to CalendarArchiveCandidate(candidate.title, sourceUrl, imageUrls, priority)
    }

    val anchors = extractCalendarArchiveAnchors(base, html)
    val permalinks =
        anchors
            .filter { isUsableCalendarSource(feed, it.url) }
            .distinctBy(CalendarArchiveAnchor::url)
    permalinks.forEachIndexed { index, anchor ->
        val end = permalinks.getOrNull(index + 1)?.start ?: html.length
        if (end <= anchor.start || end - anchor.start > MAX_ARCHIVE_ENTRY_CHARS) return@forEachIndexed
        val entryHtml = html.substring(anchor.start, end)
        addEvidence(
            text = htmlToText(entryHtml),
            sourceUrl = anchor.url,
            imageUrls = extractArchiveEntryImages(base, entryHtml),
            priority = 110,
        )
    }

    val root = runCatching { ingestionJson.parseToJsonElement(html) }.getOrNull()

    fun visit(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                val strings =
                    element.mapValues { (_, value) -> runCatching { value.jsonPrimitive.content }.getOrNull() }
                val text = ARCHIVE_JSON_TEXT_KEYS.mapNotNull(strings::get).joinToString(" ")
                val directUrl =
                    ARCHIVE_JSON_LINK_KEYS
                        .mapNotNull(strings::get)
                        .mapNotNull { resolveHttpsUrl(base, it) }
                        .firstOrNull { isUsableCalendarSource(feed, it) }
                val postId = ARCHIVE_JSON_POST_ID_KEYS.mapNotNull(strings::get).firstOrNull()
                val constructedUrl =
                    feed.publisherId
                        ?.takeIf { it.all(Char::isDigit) }
                        ?.let { publisherId -> postId?.let { "https://weibo.com/$publisherId/$it" } }
                        ?.takeIf { isUsableCalendarSource(feed, it) }
                (directUrl ?: constructedUrl)?.let { sourceUrl ->
                    addEvidence(
                        text = text,
                        sourceUrl = sourceUrl,
                        imageUrls = collectJsonImageUrls(element, base),
                        priority = 100,
                    )
                }
                element.values.forEach(::visit)
            }
            else -> runCatching { element.jsonArray }.getOrNull()?.forEach(::visit)
        }
    }
    root?.let(::visit)

    return matches
        .sortedWith(
            compareByDescending<Pair<DomesticShowCandidate, CalendarArchiveCandidate>> { it.second.priority }
                .thenByDescending { it.second.imageUrls.size },
        ).distinctBy { (candidate, archive) -> normalizeTitle(candidate.title) to archive.sourceUrl }
        .take(feed.maxShows)
        .map { (candidate, archive) ->
            CalendarIngestionShow(
                title = candidate.title,
                year = candidate.year.takeIf { it in 1900..2100 } ?: defaultYear,
                tmdbId = candidate.tmdbId,
                posterPath = candidate.posterPath,
                seasonNumber = feed.seasonNumber,
                airTime = feed.airTime,
                timeZoneId = feed.timeZoneId,
                platforms = (feed.platforms + candidate.platforms).distinct(),
                accessTier = candidate.accessTier.takeIf { it != "Unknown" } ?: feed.accessTier,
                discoveryWeight = candidate.discoveryWeight,
                sources =
                    listOf(
                        CalendarSourceConfig(
                            type = feed.type,
                            platform = feed.platform,
                            publisherId = feed.publisherId,
                            publisher = feed.publisher,
                            url = archive.sourceUrl,
                            imageUrls = archive.imageUrls,
                        ),
                    ),
            )
        }
}

private fun matchDomesticCandidate(
    text: String,
    candidates: List<DomesticShowCandidate>,
): DomesticShowCandidate? {
    val compactText = text.replace(Regex("\\s+"), " ").take(MAX_CANDIDATE_EVIDENCE_TEXT_CHARS)
    val normalizedText = normalizeTitle(compactText)
    val matching =
        candidates
            .filter { candidate ->
                (candidate.aliases + candidate.title).distinctBy(::normalizeTitle).any { alias ->
                    val cleaned = alias.trim()
                    if (cleaned.isBlank()) return@any false
                    val escaped = Regex.escape(cleaned)
                    val explicitlyNamed =
                        Regex("(?:#|《|「|『)\\s*$escaped\\s*(?:#|》|」|』)", RegexOption.IGNORE_CASE)
                            .containsMatchIn(compactText)
                    val normalizedAlias = normalizeTitle(cleaned)
                    explicitlyNamed || (normalizedAlias.length >= 4 && normalizedText.contains(normalizedAlias))
                }
            }.distinctBy { candidate -> candidate.tmdbId?.let { "tmdb:$it" } ?: normalizeTitle(candidate.title) }
    return matching.singleOrNull()
}

private fun extractCalendarArchiveAnchors(
    base: URI,
    html: String,
): List<CalendarArchiveAnchor> =
    ARCHIVE_ANCHOR_REGEX
        .findAll(html)
        .mapNotNull { anchor ->
            val attributes = anchor.groupValues[1] + " " + anchor.groupValues[3]
            val label =
                buildString {
                    append(htmlToText(anchor.groupValues[4]))
                    Regex("(?is)(?:title|aria-label)=[\"']([^\"']+)[\"']")
                        .find(attributes)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.let {
                            append(' ')
                            append(htmlToText(it))
                        }
                }.replace(Regex("\\s+"), " ").trim()
            val sourceUrl = resolveHttpsUrl(base, anchor.groupValues[2]) ?: return@mapNotNull null
            CalendarArchiveAnchor(anchor.range.first, anchor.range.last + 1, sourceUrl, label)
        }.toList()

private fun extractCalendarArchiveTitle(
    sourceText: String,
    configuredPattern: Regex,
): String? {
    val text = sourceText.replace(Regex("\\s+"), " ").trim()
    configuredPattern.find(text)?.groupValues?.getOrNull(1)?.let(::cleanCalendarTitle)?.let { title ->
        if (title.isNotBlank()) return title
    }
    CALENDAR_HASHTAG_TITLE_PATTERNS.forEach { pattern ->
        pattern.find(text)?.groupValues?.getOrNull(1)?.let(::cleanCalendarTitle)?.let { title ->
            if (title.isNotBlank()) return title
        }
    }
    if (!CALENDAR_DISCOVERY_KEYWORDS.containsMatchIn(text)) return null
    Regex("[《「『]([^》」』]{1,120})[》」』]")
        .find(text)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::cleanCalendarTitle)
        ?.takeIf(String::isNotBlank)
        ?.let { return it }
    return Regex("#([^#]{1,100})#")
        .findAll(text)
        .map { it.groupValues[1] }
        .map(::cleanCalendarTitle)
        .filter { it.length in 1..60 && !GENERIC_CALENDAR_HASHTAGS.contains(it) }
        .minByOrNull(String::length)
}

private fun cleanCalendarTitle(value: String): String =
    value
        .replace(Regex("^(?:电视剧|剧集|网剧|网络剧|动画|综艺)"), "")
        .replace(
            Regex(
                "(?:最新)?(?:追剧|播出|更新|会员|加更|观看|暗恋)日历$|" +
                    "(?:大结局)?点映礼$|观看指引$|通关攻略$|今日开播$|开播$|定档(?:\\d{4})?$",
            ),
            "",
        ).trim(' ', '#', '《', '》', '「', '」', '『', '』', ':', '：', '-', '—')

private fun isUsableCalendarSource(
    feed: CalendarDiscoveryFeed,
    url: String,
): Boolean {
    val uri = runCatching { requireHttps(url) }.getOrNull() ?: return false
    if (feed.type == "VerifiedAccount") {
        if (!(uri.host.equals("weibo.com", true) || uri.host.endsWith(".weibo.com", true))) return false
        val publisherId = feed.publisherId ?: return false
        if (!url.contains(publisherId)) return false
        return WEIBO_PERMALINK_PATH.matches(uri.path)
    }
    val allowedHosts = PLATFORM_HOSTS[feed.platform] ?: return false
    if (Regex(
            "\\.(?:jpe?g|png|webp|gif|svg)(?:$|[?#])",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(uri.path)
    ) {
        return false
    }
    return url != feed.url && allowedHosts.any { uri.host.equals(it, true) || uri.host.endsWith(".$it", true) }
}

private fun extractArchiveEntryImages(
    base: URI,
    html: String,
): List<String> =
    ARCHIVE_IMAGE_REGEX
        .findAll(html)
        .mapNotNull { match ->
            val raw =
                match.groupValues[1]
                    .substringBefore(',')
                    .trim()
                    .substringBefore(' ')
            resolveHttpsUrl(base, raw)?.takeIf(::isLikelyCalendarMediaUrl)
        }.distinct()
        .take(MAX_IMAGES_PER_SOURCE)
        .toList()

private fun discoverCalendarArchiveJson(
    feed: CalendarDiscoveryFeed,
    content: String,
    titleRegex: Regex,
    base: URI,
): List<CalendarArchiveCandidate> {
    val root = runCatching { ingestionJson.parseToJsonElement(content) }.getOrNull() ?: return emptyList()
    val candidates = mutableListOf<CalendarArchiveCandidate>()

    fun visit(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                val strings =
                    element.mapValues { (_, value) ->
                        runCatching { value.jsonPrimitive.content }.getOrNull()
                    }
                val text =
                    ARCHIVE_JSON_TEXT_KEYS
                        .mapNotNull(strings::get)
                        .joinToString(" ")
                val title = extractCalendarArchiveTitle(text, titleRegex)
                if (title != null) {
                    val directUrl =
                        ARCHIVE_JSON_LINK_KEYS
                            .mapNotNull(strings::get)
                            .mapNotNull { resolveHttpsUrl(base, it) }
                            .firstOrNull { isUsableCalendarSource(feed, it) }
                    val postId = ARCHIVE_JSON_POST_ID_KEYS.mapNotNull(strings::get).firstOrNull()
                    val constructedUrl =
                        feed.publisherId
                            ?.takeIf { it.all(Char::isDigit) }
                            ?.let { publisherId -> postId?.let { "https://weibo.com/$publisherId/$it" } }
                            ?.takeIf { isUsableCalendarSource(feed, it) }
                    val sourceUrl = directUrl ?: constructedUrl
                    if (sourceUrl != null) {
                        candidates +=
                            CalendarArchiveCandidate(
                                title = title,
                                sourceUrl = sourceUrl,
                                imageUrls = collectJsonImageUrls(element, base),
                                priority = 90,
                            )
                    }
                }
                element.values.forEach(::visit)
            }
            else -> runCatching { element.jsonArray }.getOrNull()?.forEach(::visit)
        }
    }
    visit(root)
    return candidates
}

private fun collectJsonImageUrls(
    element: JsonElement,
    base: URI,
): List<String> {
    data class RankedImageUrl(
        val url: String,
        val score: Int,
        val order: Int,
    )

    val urls = mutableListOf<RankedImageUrl>()
    var order = 0

    fun visit(
        current: JsonElement,
        key: String = "",
        path: List<String> = emptyList(),
    ) {
        val currentPath = path + key.lowercase()
        if (currentPath.any { it == "user" || it.contains("avatar") || it.contains("profile_image") }) return
        when (current) {
            is JsonObject -> current.forEach { (childKey, child) -> visit(child, childKey, currentPath) }
            else -> {
                val values = runCatching { current.jsonArray }.getOrNull()
                if (values != null) {
                    values.forEach { visit(it, key, path) }
                } else if (key.contains("url", true) || key.contains("image", true) || key.contains("pic", true)) {
                    runCatching { current.jsonPrimitive.content }
                        .getOrNull()
                        ?.let { resolveHttpsUrl(base, it) }
                        ?.takeIf(::isLikelyCalendarMediaUrl)
                        ?.let { url ->
                            val mediaPath = currentPath.joinToString("/")
                            val score =
                                when {
                                    "pics" in currentPath || "pic_infos" in currentPath -> 100
                                    "page_info" in currentPath -> 25
                                    else -> 0
                                } +
                                    if (
                                        listOf("large", "largest", "original", "mw2000").any(mediaPath::contains)
                                    ) {
                                        30
                                    } else {
                                        0
                                    }
                            urls += RankedImageUrl(url, score, order++)
                        }
                }
            }
        }
    }
    visit(element)
    return urls
        .sortedWith(compareByDescending<RankedImageUrl> { it.score }.thenBy(RankedImageUrl::order))
        .distinctBy { ranked ->
            runCatching { URI(ranked.url) }.getOrNull()?.let { uri ->
                if (uri.host.endsWith(".sinaimg.cn", true) || uri.host.equals("sinaimg.cn", true)) {
                    "sinaimg:${uri.path.substringAfterLast('/')}"
                } else {
                    ranked.url
                }
            } ?: ranked.url
        }.map(RankedImageUrl::url)
        .take(MAX_IMAGES_PER_SOURCE)
}

private fun resolveHttpsUrl(
    base: URI,
    raw: String,
): String? {
    val cleaned = raw.replace("\\/", "/").replace("&amp;", "&").trim()
    if (cleaned.isBlank() || cleaned.startsWith("data:") || cleaned.startsWith("javascript:")) return null
    val normalized = if (cleaned.startsWith("//")) "https:$cleaned" else cleaned
    val resolved = runCatching { base.resolve(normalized) }.getOrNull() ?: return null
    return resolved.toString().takeIf { resolved.scheme.equals("https", true) && !resolved.host.isNullOrBlank() }
}

private fun isLikelyCalendarMediaUrl(url: String): Boolean {
    val lower = url.lowercase()
    if (listOf("avatar", "icon", "logo", "emoji", "emoticon").any(lower::contains)) return false
    val host = runCatching { URI(url).host.lowercase() }.getOrNull() ?: return false
    return CALENDAR_MEDIA_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }
}

internal fun prioritizeCalendarImages(urls: List<String>): List<String> =
    urls
        .asSequence()
        .filter(::isLikelyCalendarMediaUrl)
        .mapIndexed { index, url ->
            val lower = url.lowercase()
            val score =
                when {
                    listOf(
                        "calendar",
                        "schedule",
                        "%e6%97%a5%e5%8e%86",
                        "%e6%8e%92%e6%9c%9f",
                    ).any(lower::contains) -> 100
                    listOf("large", "largest", "original", "mw2000", "ori").any(lower::contains) -> 50
                    listOf("thumb", "thumbnail", "small", "square").any(lower::contains) -> -50
                    else -> 0
                }
            Triple(url, score, index)
        }.sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenBy { it.third })
        .distinctBy { (url) ->
            runCatching { URI(url) }.getOrNull()?.let { uri ->
                if (uri.host.endsWith(".sinaimg.cn", true) || uri.host.equals("sinaimg.cn", true)) {
                    "sinaimg:${uri.path.substringAfterLast('/')}"
                } else {
                    url
                }
            } ?: url
        }.map { it.first }
        .toList()

internal fun mergeIngestionShows(shows: List<CalendarIngestionShow>): List<CalendarIngestionShow> =
    shows
        .groupBy { show -> "title:${normalizeTitle(show.title)}" }
        .values
        .map { matching ->
            val preferred = matching.firstOrNull { it.tmdbId != null } ?: matching.first()
            preferred.copy(
                platforms = matching.flatMap(CalendarIngestionShow::platforms).distinct(),
                posterPath = preferred.posterPath ?: matching.firstNotNullOfOrNull(CalendarIngestionShow::posterPath),
                discoveryWeight = matching.maxOf(CalendarIngestionShow::discoveryWeight),
                sources = matching.flatMap(CalendarIngestionShow::sources).distinctBy(CalendarSourceConfig::url),
            )
        }.groupBy { show -> show.tmdbId?.let { "tmdb:$it" } ?: "title:${normalizeTitle(show.title)}" }
        .values
        .map { matching ->
            val preferred = matching.maxBy(CalendarIngestionShow::discoveryWeight)
            preferred.copy(
                platforms = matching.flatMap(CalendarIngestionShow::platforms).distinct(),
                sources = matching.flatMap(CalendarIngestionShow::sources).distinctBy(CalendarSourceConfig::url),
            )
        }

internal fun extractCalendarImages(
    pageUrl: String,
    html: String,
): List<String> {
    val base = URI(pageUrl)
    val pageIsCalendar = CALENDAR_DISCOVERY_KEYWORDS.containsMatchIn(htmlToText(html))
    return ARCHIVE_IMAGE_REGEX
        .findAll(html)
        .filter { match ->
            val tag = match.value.lowercase()
            listOf("日历", "calendar", "schedule", "追剧", "排期").any(tag::contains) || pageIsCalendar
        }.mapNotNull { match ->
            val candidate =
                match.groupValues[1]
                    .substringBefore(',')
                    .trim()
                    .substringBefore(' ')
            resolveHttpsUrl(base, candidate)?.takeIf(::isLikelyCalendarMediaUrl)
        }.distinct()
        .take(MAX_IMAGES_PER_SOURCE)
        .toList()
}

internal const val DEFAULT_DISCOVERY_TITLE_PATTERN =
    "[《「『]([^》」』]{1,120})[》」』].{0,40}(?:追剧日历|播出日历|更新日历|排期|更新时间)"
private val ARCHIVE_ANCHOR_REGEX =
    Regex("(?is)<a\\b([^>]*?)href=[\"']([^\"']+)[\"']([^>]*)>(.*?)</a>")
private val ARCHIVE_IMAGE_REGEX =
    Regex(
        "(?is)<img[^>]+(?:src|data-src|data-original|data-lazy-src|data-actualsrc|srcset)=" +
            "[\"']([^\"']+)[\"'][^>]*>",
    )
private val CALENDAR_DISCOVERY_KEYWORDS =
    Regex("(?:追剧|播出|更新|会员|加更|观看|暗恋)日历|排期|更新时间|观看指引|通关攻略")
private val CALENDAR_CANDIDATE_EVIDENCE_KEYWORDS =
    Regex("日历|排期|更新|首更|开播|收官|点映|会员|SVIP|VIP|追剧|观看指引", RegexOption.IGNORE_CASE)
private val CALENDAR_HASHTAG_TITLE_PATTERNS =
    listOf(
        Regex("#([^#]{1,80}?)(?:追剧|播出|更新|会员|加更|观看|暗恋)日历#"),
        Regex("[《「『]([^》」』]{1,120})[》」』].{0,120}(?:追剧|播出|更新|会员|加更|观看)日历"),
    )
private val GENERIC_CALENDAR_HASHTAGS =
    setOf("追剧日历", "播出日历", "更新日历", "会员日历", "最新追剧日历", "大结局点映礼", "点映礼")
private val WEIBO_PERMALINK_PATH = Regex("^/\\d{6,}/[A-Za-z0-9_-]{5,}/?$")
private val ARCHIVE_JSON_TEXT_KEYS =
    listOf("text_raw", "text", "content", "title", "desc", "description", "shareTitle")
private val ARCHIVE_JSON_LINK_KEYS =
    listOf("url", "href", "link", "page_url", "share_url", "shareUrl")
private val ARCHIVE_JSON_POST_ID_KEYS = listOf("mblogid", "bid")
private val CALENDAR_MEDIA_HOST_SUFFIXES =
    setOf(
        "sinaimg.cn",
        "iqiyipic.com",
        "qiyipic.com",
        "ykimg.com",
        "alicdn.com",
        "gtimg.com",
        "qpic.cn",
        "qq.com",
        "mgtv.com",
        "mgtvcdn.com",
        "hitv.com",
    )
private const val MAX_ARCHIVE_ENTRY_CHARS = 250_000
