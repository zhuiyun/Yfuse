package com.yfuse.watch

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

private val domesticDiscoveryJson = Json { ignoreUnknownKeys = true }

/**
 * Broad discovery is deliberately separated from publication evidence. TMDB and platform
 * catalogues only tell us which titles should be looked for on allowlisted official accounts;
 * they can never publish episode coordinates by themselves.
 */
@Serializable
internal data class DomesticCandidateDiscoveryConfig(
    val enabled: Boolean = false,
    val maxShows: Int = 200,
    val tmdbOnAir: TmdbDomesticCandidateConfig = TmdbDomesticCandidateConfig(),
    val platformCatalogs: List<PlatformCatalogCandidateFeed> = emptyList(),
)

@Serializable
internal data class TmdbDomesticCandidateConfig(
    val enabled: Boolean = true,
    val endpoint: String = "https://api.themoviedb.org/3/tv/on_the_air",
    val language: String = "zh-CN",
    val maxPages: Int = 3,
    val maxShows: Int = 120,
    val originCountries: List<String> = listOf("CN"),
    val originalLanguages: List<String> = listOf("zh"),
    val requiredGenreIds: List<Int> = listOf(18),
)

@Serializable
internal data class PlatformCatalogCandidateFeed(
    val platform: String,
    val publisher: String,
    val url: String,
    val titlePattern: String? = null,
    val maxShows: Int = 60,
    val render: Boolean = true,
    val accessTier: String = "Member",
)

internal data class DomesticShowCandidate(
    val title: String,
    val year: Int,
    val tmdbId: Int? = null,
    val posterPath: String? = null,
    val aliases: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
    val accessTier: String = "Unknown",
    val discoveryWeight: Int = 0,
)

internal data class DomesticCandidateCounts(
    val tmdb: Int = 0,
    val platform: Int = 0,
    val merged: Int = 0,
    val evidenceMatched: Int = 0,
)

internal object DomesticCandidateParser {
    fun parseTmdbOnAir(
        body: String,
        today: LocalDate,
        config: TmdbDomesticCandidateConfig,
    ): List<DomesticShowCandidate> {
        val results =
            runCatching {
                domesticDiscoveryJson.parseToJsonElement(body).jsonObject["results"]?.jsonArray
            }.getOrNull() ?: return emptyList()
        val allowedCountries = config.originCountries.map(String::uppercase).toSet()
        val allowedLanguages = config.originalLanguages.map(String::lowercase).toSet()
        val requiredGenres = config.requiredGenreIds.toSet()
        return results.mapNotNull { node ->
            val item = node as? JsonObject ?: return@mapNotNull null
            val countries = item.arrayStrings("origin_country").map(String::uppercase).toSet()
            if (allowedCountries.isNotEmpty() && countries.intersect(allowedCountries).isEmpty()) return@mapNotNull null
            val originalLanguage = item.string("original_language")?.lowercase()
            if (allowedLanguages.isNotEmpty() && originalLanguage !in allowedLanguages) return@mapNotNull null
            val genres = item.arrayInts("genre_ids").toSet()
            if (requiredGenres.isNotEmpty() && genres.intersect(requiredGenres).isEmpty()) return@mapNotNull null
            val id = item.int("id")?.takeIf { it > 0 } ?: return@mapNotNull null
            val title = item.string("name")?.let(::cleanDomesticCandidateTitle) ?: return@mapNotNull null
            if (!isPlausibleDomesticTitle(title)) return@mapNotNull null
            val originalTitle = item.string("original_name")?.let(::cleanDomesticCandidateTitle)
            val firstAirDate = item.string("first_air_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val year = firstAirDate?.year ?: today.year
            if (year !in 1900..(today.year + 1)) return@mapNotNull null
            DomesticShowCandidate(
                title = title,
                year = year,
                tmdbId = id,
                posterPath = item.string("poster_path"),
                aliases = listOfNotNull(title, originalTitle).distinctBy(::normalizeTitle),
                discoveryWeight =
                    ((item.double("popularity") ?: 0.0) * 10.0)
                        .toInt()
                        .coerceIn(0, 1_000),
            )
        }.distinctBy(DomesticShowCandidate::tmdbId)
            .sortedWith(
                compareByDescending<DomesticShowCandidate>(DomesticShowCandidate::discoveryWeight)
                    .thenBy(DomesticShowCandidate::title),
            ).take(config.maxShows)
    }

    fun parsePlatformCatalog(
        body: String,
        feed: PlatformCatalogCandidateFeed,
        defaultYear: Int,
    ): List<DomesticShowCandidate> {
        val titles = linkedSetOf<String>()
        feed.titlePattern
            ?.let { runCatching { Regex(it, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)) }.getOrNull() }
            ?.findAll(body)
            ?.forEach { match -> match.groupValues.getOrNull(1)?.let(titles::add) }

        PLATFORM_CATALOG_JSON_TITLE_REGEX.findAll(body).forEach { match ->
            match.groupValues.getOrNull(1)?.decodePlatformEscapes()?.let(titles::add)
        }
        PLATFORM_CATALOG_ANCHOR_REGEX.findAll(body).forEach { match ->
            val attributes = match.groupValues[1] + " " + match.groupValues[3]
            PLATFORM_CATALOG_ATTRIBUTE_TITLE_REGEX.find(attributes)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(titles::add)
            PLATFORM_CATALOG_IMAGE_ALT_REGEX.find(match.groupValues[4])
                ?.groupValues
                ?.getOrNull(1)
                ?.let(titles::add)
            htmlToCatalogText(match.groupValues[4]).takeIf { it.length <= 80 }?.let(titles::add)
        }

        return titles.asSequence()
            .map(String::decodePlatformEscapes)
            .map(::cleanDomesticCandidateTitle)
            .filter(::isPlausibleDomesticTitle)
            .distinctBy(::normalizeTitle)
            .take(feed.maxShows)
            .mapIndexed { index, title ->
                DomesticShowCandidate(
                    title = title,
                    year = defaultYear,
                    aliases = listOf(title),
                    platforms = listOf(feed.platform),
                    accessTier = feed.accessTier,
                    discoveryWeight = (feed.maxShows - index).coerceAtLeast(1),
                )
            }.toList()
    }
}

internal fun mergeDomesticCandidates(
    candidates: List<DomesticShowCandidate>,
    maxShows: Int,
): List<DomesticShowCandidate> {
    val byTitle =
        candidates
            .filter { isPlausibleDomesticTitle(it.title) }
            .groupBy { normalizeTitle(it.title) }
            .values
            .map { matching ->
                val preferred =
                    matching.firstOrNull { it.tmdbId != null }
                        ?: matching.maxBy(DomesticShowCandidate::discoveryWeight)
                preferred.copy(
                    posterPath = preferred.posterPath ?: matching.firstNotNullOfOrNull(DomesticShowCandidate::posterPath),
                    aliases =
                        matching.flatMap { it.aliases + it.title }
                            .filter(String::isNotBlank)
                            .distinctBy(::normalizeTitle),
                    platforms = matching.flatMap(DomesticShowCandidate::platforms).distinct(),
                    accessTier = matching.firstOrNull { it.accessTier != "Unknown" }?.accessTier ?: preferred.accessTier,
                    discoveryWeight = matching.maxOf(DomesticShowCandidate::discoveryWeight),
                )
            }
    return byTitle
        .groupBy { candidate -> candidate.tmdbId?.let { "tmdb:$it" } ?: "title:${normalizeTitle(candidate.title)}" }
        .values
        .map { it.maxBy(DomesticShowCandidate::discoveryWeight) }
        .sortedWith(
            compareByDescending<DomesticShowCandidate> { it.tmdbId != null }
                .thenByDescending(DomesticShowCandidate::discoveryWeight)
                .thenBy(DomesticShowCandidate::title),
        ).take(maxShows)
}

private fun cleanDomesticCandidateTitle(value: String): String {
    val cleaned = htmlToCatalogText(value).replace(PLATFORM_CATALOG_STATUS_PREFIX, "")
    val withoutMetadata =
        PLATFORM_CATALOG_METADATA_MARKER.find(cleaned)
            ?.let { cleaned.substring(0, it.range.first) }
            ?: cleaned
    return withoutMetadata
        .replace(PLATFORM_CATALOG_STATUS_SUFFIX, "")
        .replace(Regex("[🔥💥🌸🌊❤💕🥰👊❗🎉🌕️]+"), "")
        .trim(' ', '#', '《', '》', '「', '」', '『', '』', ':', '：', '-', '—', '|')
}

private fun isPlausibleDomesticTitle(value: String): Boolean {
    if (value.length !in 2..40 || value.none { it.code in 0x3400..0x9FFF }) return false
    if (PLATFORM_CATALOG_NAVIGATION_WORDS.any { value.equals(it, true) || value.contains(it, true) }) return false
    if (value.count(Char::isWhitespace) > 5) return false
    return normalizeTitle(value).length in 2..40
}

private fun htmlToCatalogText(value: String): String =
    value
        .replace(Regex("(?is)<script[^>]*>.*?</script>|<style[^>]*>.*?</style>"), " ")
        .replace(Regex("(?is)<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("""&#(\d+);""")) { match ->
            match.groupValues[1].toIntOrNull()?.toChar()?.toString().orEmpty()
        }.replace(Regex("\\s+"), " ")
        .trim()

private fun String.decodePlatformEscapes(): String =
    replace("\\/", "/")
        .replace(Regex("\\\\u([0-9a-fA-F]{4})")) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }

private fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)

private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
private fun JsonObject.double(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull
private fun JsonObject.arrayStrings(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }

private fun JsonObject.arrayInts(key: String): List<Int> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.intOrNull }

private const val PLATFORM_CATALOG_TITLE_KEYS =
    "title|name|albumName|album_name|showName|show_name|tvName|tv_name|displayName|display_name"
private val PLATFORM_CATALOG_JSON_TITLE_REGEX =
    Regex("(?is)[\\\"'](?:$PLATFORM_CATALOG_TITLE_KEYS)[\\\"']\\s*:\\s*[\\\"']([^\\\"'\\\\]{2,100})[\\\"']")
private val PLATFORM_CATALOG_ANCHOR_REGEX =
    Regex("(?is)<a\\b([^>]*?)href=[\\\"']([^\\\"']+)[\\\"']([^>]*)>(.*?)</a>")
private val PLATFORM_CATALOG_ATTRIBUTE_TITLE_REGEX =
    Regex("(?is)(?:title|aria-label)=[\\\"']([^\\\"']{2,100})[\\\"']")
private val PLATFORM_CATALOG_IMAGE_ALT_REGEX =
    Regex("(?is)<img[^>]+alt=[\\\"']([^\\\"']{2,100})[\\\"']")
private val PLATFORM_CATALOG_STATUS_PREFIX =
    Regex("^(?:(?:独播|VIP|会员|限免|热播|新上线|预告|预约|首播|HOT|NEW)\\s*)+", RegexOption.IGNORE_CASE)
private val PLATFORM_CATALOG_STATUS_SUFFIX =
    Regex("(?:独播|VIP|会员|限免|HOT|NEW|预告|预约|更新至?第?\\d{1,3}[集期话]?|\\d{1,3}[集期话]全|全\\d{1,3}[集期话])+$", RegexOption.IGNORE_CASE)
private val PLATFORM_CATALOG_METADATA_MARKER =
    Regex("(?:热度|评分|上线时间|更新状态|简介|演职员|主演|立即播放)[:：]?")
private val PLATFORM_CATALOG_NAVIGATION_WORDS =
    setOf(
        "首页",
        "电视剧",
        "电视剧频道",
        "热播剧集",
        "新剧速递",
        "排行榜",
        "全部地区",
        "全部类型",
        "全部年份",
        "综合排序",
        "立即播放",
        "更多",
        "猜你喜欢",
        "VIP会员",
    )
