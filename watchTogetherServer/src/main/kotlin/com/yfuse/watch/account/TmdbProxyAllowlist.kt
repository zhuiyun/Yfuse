package com.yfuse.watch.account

import io.ktor.http.Parameters
import java.net.URLEncoder

/**
 * One TMDB read the proxy will make: its canonical path and query (also the cache key), how long
 * the answer stays fresh, and a fixed label that logs name instead of the path, which can carry a
 * person's id or a search term.
 */
internal data class TmdbProxyRequest(
    val pathAndQuery: String,
    val ttlMs: Long,
    val label: String,
)

/** How long a successful answer is reused. Charts move through the day; records barely move. */
internal enum class TmdbFreshness(
    val ttlMs: Long,
) {
    /** Charts and discover pages. Short, but still long enough to absorb a home-page burst. */
    Listing(10 * 60_000L),

    /** Name lookups: stable, but so varied that keeping them long mostly fills the cache. */
    Search(30 * 60_000L),

    /** Title, season and person records. The calendar's next episode changes at most daily. */
    Detail(2 * 60 * 60_000L),
}

/**
 * The only reads the proxy forwards: the home shelves, calendar and detail reads `TmdbRepository`
 * makes, the person record and person search behind the actor page's 其他作品, the title search and
 * alternative titles 文件来源 matching uses, and the credits, find and external-id lookups beside
 * them. Anything else is refused rather than trimmed, so the account's session cannot be spent on
 * arbitrary TMDB calls and an unexpected parameter never changes what a cached answer means.
 */
internal fun tmdbProxyRequest(
    path: List<String>,
    query: Parameters,
): TmdbProxyRequest? {
    val endpoint = TMDB_ENDPOINTS.firstOrNull { it.matches(path) } ?: return null
    val names = query.names()
    if (!endpoint.required.all(names::contains)) return null
    val pairs =
        names.sorted().map { name ->
            val pattern = endpoint.parameters[name] ?: return null
            val value = query.getAll(name)?.singleOrNull() ?: return null
            if (!pattern.matches(value)) return null
            name to value
        }
    val canonical =
        buildString {
            path.forEach { append('/').append(it) }
            pairs.forEachIndexed { index, (name, value) ->
                append(if (index == 0) '?' else '&')
                append(name.encodeQueryComponent()).append('=').append(value.encodeQueryComponent())
            }
        }
    return TmdbProxyRequest(canonical, endpoint.freshness.ttlMs, endpoint.label)
}

private class TmdbEndpoint(
    val label: String,
    val segments: List<Regex>,
    val freshness: TmdbFreshness,
    val parameters: Map<String, Regex> = emptyMap(),
    val required: Set<String> = emptySet(),
) {
    fun matches(path: List<String>): Boolean =
        path.size == segments.size && path.indices.all { segments[it].matches(path[it]) }
}

// URLEncoder writes spaces as '+', which only form decoding reads as a space.
private fun String.encodeQueryComponent(): String = URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")

private fun literal(value: String) = Regex(Regex.escape(value))

private fun appendable(vararg names: String): Regex {
    val one = names.joinToString("|") { Regex.escape(it) }
    return Regex("(?:$one)(?:,(?:$one)){0,${names.size - 1}}")
}

private val ID = Regex("[1-9][0-9]{0,8}")
private val SEASON = Regex("0|[1-9][0-9]{0,3}")
private val EXTERNAL_ID = Regex("(?:tt|nm)[0-9]{5,12}|[1-9][0-9]{0,9}")

private val LANGUAGE = Regex("[a-z]{2}(?:-[A-Z]{2})?")
private val PAGE = Regex("[1-9][0-9]{0,2}")
private val REGION = Regex("[A-Z]{2}")
private val DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
private val YEAR = Regex("[0-9]{4}")
private val COUNT = Regex("[0-9]{1,6}")
private val BOOLEAN = Regex("true|false")

// Adult titles are never asked for by the app, and the proxy will not be the way to ask.
private val FALSE_ONLY = literal("false")
private val SORT = Regex("[a-z_]{1,32}\\.(?:asc|desc)")
private val COUNTRIES = Regex("[A-Z]{2}(?:[|,][A-Z]{2}){0,9}")
private val LANGUAGES = Regex("[a-z]{2}(?:[|,][a-z]{2}){0,9}")
private val GENRES = Regex("[0-9]{1,6}(?:[|,][0-9]{1,6}){0,19}")
private val QUERY = Regex("(?=.*\\S)[^\\p{Cntrl}]{1,100}")
private val EXTERNAL_SOURCE = Regex("imdb_id|tvdb_id")

private val LANGUAGE_ONLY = mapOf("language" to LANGUAGE)
private val CHART = mapOf("language" to LANGUAGE, "page" to PAGE)
private val DISCOVER =
    mapOf(
        "language" to LANGUAGE,
        "page" to PAGE,
        "sort_by" to SORT,
        "with_origin_country" to COUNTRIES,
        "with_original_language" to LANGUAGES,
        "without_genres" to GENRES,
        "vote_count.gte" to COUNT,
        "include_adult" to FALSE_ONLY,
    )
private val SEARCH = mapOf("query" to QUERY, "language" to LANGUAGE, "page" to PAGE, "include_adult" to FALSE_ONLY)

private val TMDB_ENDPOINTS: List<TmdbEndpoint> =
    listOf(
        TmdbEndpoint("movie/popular", listOf(literal("movie"), literal("popular")), TmdbFreshness.Listing, CHART),
        TmdbEndpoint(
            "movie/now_playing",
            listOf(literal("movie"), literal("now_playing")),
            TmdbFreshness.Listing,
            CHART,
        ),
        TmdbEndpoint("tv/popular", listOf(literal("tv"), literal("popular")), TmdbFreshness.Listing, CHART),
        TmdbEndpoint("tv/airing_today", listOf(literal("tv"), literal("airing_today")), TmdbFreshness.Listing, CHART),
        TmdbEndpoint(
            "discover/movie",
            listOf(literal("discover"), literal("movie")),
            TmdbFreshness.Listing,
            DISCOVER +
                mapOf(
                    "include_video" to BOOLEAN,
                    "primary_release_date.gte" to DATE,
                    "primary_release_date.lte" to DATE,
                    "primary_release_year" to YEAR,
                    "region" to REGION,
                ),
        ),
        TmdbEndpoint(
            "discover/tv",
            listOf(literal("discover"), literal("tv")),
            TmdbFreshness.Listing,
            DISCOVER +
                mapOf(
                    "first_air_date.gte" to DATE,
                    "first_air_date.lte" to DATE,
                    "first_air_date_year" to YEAR,
                    "air_date.gte" to DATE,
                    "air_date.lte" to DATE,
                ),
        ),
        TmdbEndpoint(
            "search/movie",
            listOf(literal("search"), literal("movie")),
            TmdbFreshness.Search,
            SEARCH + mapOf("year" to YEAR, "primary_release_year" to YEAR, "region" to REGION),
            required = setOf("query"),
        ),
        TmdbEndpoint(
            "search/tv",
            listOf(literal("search"), literal("tv")),
            TmdbFreshness.Search,
            SEARCH + mapOf("year" to YEAR, "first_air_date_year" to YEAR),
            required = setOf("query"),
        ),
        TmdbEndpoint(
            "search/multi",
            listOf(literal("search"), literal("multi")),
            TmdbFreshness.Search,
            SEARCH,
            required = setOf("query"),
        ),
        TmdbEndpoint(
            "search/person",
            listOf(literal("search"), literal("person")),
            TmdbFreshness.Search,
            SEARCH,
            required = setOf("query"),
        ),
        TmdbEndpoint(
            "movie/{id}",
            listOf(literal("movie"), ID),
            TmdbFreshness.Detail,
            mapOf("language" to LANGUAGE, "append_to_response" to appendable("credits", "external_ids")),
        ),
        TmdbEndpoint(
            "tv/{id}",
            listOf(literal("tv"), ID),
            TmdbFreshness.Detail,
            mapOf(
                "language" to LANGUAGE,
                "append_to_response" to appendable("credits", "aggregate_credits", "external_ids"),
            ),
        ),
        TmdbEndpoint(
            "movie/{id}/credits",
            listOf(literal("movie"), ID, literal("credits")),
            TmdbFreshness.Detail,
            LANGUAGE_ONLY,
        ),
        TmdbEndpoint(
            "tv/{id}/credits",
            listOf(literal("tv"), ID, literal("credits")),
            TmdbFreshness.Detail,
            LANGUAGE_ONLY,
        ),
        TmdbEndpoint(
            "tv/{id}/aggregate_credits",
            listOf(literal("tv"), ID, literal("aggregate_credits")),
            TmdbFreshness.Detail,
            LANGUAGE_ONLY,
        ),
        TmdbEndpoint(
            "movie/{id}/external_ids",
            listOf(literal("movie"), ID, literal("external_ids")),
            TmdbFreshness.Detail,
        ),
        TmdbEndpoint("tv/{id}/external_ids", listOf(literal("tv"), ID, literal("external_ids")), TmdbFreshness.Detail),
        TmdbEndpoint(
            "movie/{id}/alternative_titles",
            listOf(literal("movie"), ID, literal("alternative_titles")),
            TmdbFreshness.Detail,
        ),
        TmdbEndpoint(
            "tv/{id}/alternative_titles",
            listOf(literal("tv"), ID, literal("alternative_titles")),
            TmdbFreshness.Detail,
        ),
        TmdbEndpoint(
            "tv/{id}/season/{n}",
            listOf(literal("tv"), ID, literal("season"), SEASON),
            TmdbFreshness.Detail,
            LANGUAGE_ONLY,
        ),
        TmdbEndpoint(
            "person/{id}",
            listOf(literal("person"), ID),
            TmdbFreshness.Detail,
            mapOf(
                "language" to LANGUAGE,
                "append_to_response" to appendable("combined_credits", "translations", "external_ids"),
            ),
        ),
        TmdbEndpoint(
            "person/{id}/combined_credits",
            listOf(literal("person"), ID, literal("combined_credits")),
            TmdbFreshness.Detail,
            LANGUAGE_ONLY,
        ),
        TmdbEndpoint(
            "person/{id}/external_ids",
            listOf(literal("person"), ID, literal("external_ids")),
            TmdbFreshness.Detail,
        ),
        TmdbEndpoint(
            "find/{external_id}",
            listOf(literal("find"), EXTERNAL_ID),
            TmdbFreshness.Detail,
            mapOf("external_source" to EXTERNAL_SOURCE, "language" to LANGUAGE),
            required = setOf("external_source"),
        ),
    )
