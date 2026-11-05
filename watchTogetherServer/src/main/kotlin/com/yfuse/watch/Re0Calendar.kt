package com.yfuse.watch

import com.yfuse.watch.protocol.SupplementalCalendarEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@Serializable
internal data class Re0CalendarConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "http://127.0.0.1:12366",
    val usernameEnvironment: String = "YFUSE_RE0_USERNAME",
    val passwordEnvironment: String = "YFUSE_RE0_PASSWORD",
    val days: Int = 30,
) {
    fun validate() {
        if (!enabled) return
        val uri = URI(baseUrl)
        require(uri.scheme == "https" || uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]")) {
            "RE0 requires HTTPS or same-host loopback HTTP"
        }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(uri.path.isNullOrEmpty() || uri.path == "/")
        require(days in 1..45)
        require(listOf(usernameEnvironment, passwordEnvironment).all { it.matches(Regex("[A-Z][A-Z0-9_]{0,79}")) })
    }
}

internal data class Re0CalendarSnapshot(
    val entries: List<SupplementalCalendarEntry>,
    val ttlSeconds: Int,
)

/** No redirects: a login body and session cookie must never travel to a different origin. */
internal class Re0CalendarClient(
    private val environment: (String) -> String? = System::getenv,
) {
    private val http =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER))
            .build()

    fun fetch(config: Re0CalendarConfig): Re0CalendarSnapshot {
        config.validate()
        val username = environment(config.usernameEnvironment)?.takeIf(String::isNotBlank)
        val password = environment(config.passwordEnvironment)?.takeIf(String::isNotBlank)
        require(username != null && password != null) { "RE0 credentials are not configured" }
        val root = config.baseUrl.trimEnd('/')
        val credentials =
            buildJsonObject {
                put("username", username)
                put("password", password)
            }
        val login = request("$root/api/login", credentials.toString())
        require((login["success"] as? JsonPrimitive)?.booleanOrNull == true) { "RE0 login failed" }
        return parseRe0Calendar(request("$root/api/media/calendar?days=${config.days}"), LocalDate.now(RE0_ZONE))
    }

    private fun request(
        url: String,
        body: String? = null,
    ): JsonObject {
        val builder = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20))
        if (body != null) {
            builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        }
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        val stream = response.body()
        val reading = CompletableFuture.supplyAsync { stream.readNBytes(MAX_RE0_BYTES + 1) }
        val bytes =
            try {
                reading.get(20, TimeUnit.SECONDS)
            } finally {
                stream.close()
                reading.cancel(true)
            }
        require(response.statusCode() == 200 && bytes.size <= MAX_RE0_BYTES) { "RE0 response unavailable or too large" }
        return ingestionJson.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: error("Invalid RE0 response")
    }
}

/** Cache success for at most six hours; retry failures later without dropping the last publication. */
internal class Re0CalendarFeed(
    private val fetch: (Re0CalendarConfig) -> Re0CalendarSnapshot = Re0CalendarClient()::fetch,
    private val now: () -> Instant = Instant::now,
) {
    private var configKey: Re0CalendarConfig? = null
    private var retryAt: Instant = Instant.MIN
    private var cached: List<SupplementalCalendarEntry>? = null

    fun entries(
        config: Re0CalendarConfig,
        previous: List<SupplementalCalendarEntry>,
    ): List<SupplementalCalendarEntry> {
        if (!config.enabled) return emptyList()
        config.validate()
        if (configKey != config) {
            configKey = config
            retryAt = Instant.MIN
            cached = null
        }
        val instant = now()
        val today = instant.atZone(RE0_ZONE).toLocalDate()
        if (!instant.isBefore(retryAt)) {
            try {
                val snapshot = fetch(config)
                // A future-only upstream window must not erase the recent catch-up history.
                cached =
                    (snapshot.entries + (cached ?: previous).filter { it.airDate < today.toString() })
                        .distinctBy(SupplementalCalendarEntry::coordinate)
                retryAt = instant.plusSeconds(snapshot.ttlSeconds.coerceIn(300, 21_600).toLong())
            } catch (failure: Exception) {
                if (failure is InterruptedException ||
                    failure is kotlinx.coroutines.CancellationException
                ) {
                    throw failure
                }
                retryAt = instant.plusSeconds(900)
                // Do not log response bodies, credentials or upstream exception messages.
                ServerLog.warn("re0_calendar_refresh_failed")
            }
        }
        return (cached ?: previous)
            .filter { it.airDate >= today.minusDays(45).toString() }
            .take(MAX_RE0_ENTRIES)
    }
}

internal fun parseRe0Calendar(
    response: JsonObject,
    today: LocalDate,
): Re0CalendarSnapshot {
    require(response.flag("success") == true) { "RE0 calendar request failed" }
    val data = response["data"] as? JsonObject ?: error("Missing RE0 calendar data")
    require(data.text("source") == "hdhive" && data.text("feed_status") == "ready")
    require(data.flag("is_stale") == false && data.flag("is_partial") == false)
    require(data.flag("requires_hdhive_configuration") != true)
    val syncedAt = requireNotNull(data.text("upstream_synced_at") ?: data.text("last_synced_at"))
    Instant.parse(syncedAt)
    val items = data["items"] as? JsonArray ?: error("Missing RE0 calendar items")
    require(items.size <= MAX_RE0_ENTRIES)
    val entries =
        items.mapNotNull { element ->
            runCatching {
                val item = element as? JsonObject ?: return@runCatching null
                if (item.text("origin") != "hdhive" || item.text("source") != "tmdb") return@runCatching null
                val kind = item.text("media_type") ?: return@runCatching null
                if (kind !in setOf("tv", "movie")) return@runCatching null
                val episode = item["calendar_episode"] as? JsonObject
                val exact = item.text("first_aired")?.takeIf { 'T' in it }?.let(Instant::parse)
                // Exact instants, when present, determine the display day in Shanghai.
                val date =
                    exact?.atZone(RE0_ZONE)?.toLocalDate()
                        ?: LocalDate.parse(item.text("calendar_date"))
                if (date < today.minusDays(45) || date > today.plusDays(46)) return@runCatching null
                val poster =
                    item
                        .text("poster_url")
                        ?.takeIf { it.startsWith("https://image.tmdb.org/t/p/") }
                        ?.substringAfterLast('/')
                        ?.let { "/$it" }
                SupplementalCalendarEntry(
                    tmdbId = item.number("tmdb_id") ?: 0,
                    title = item.text("title").orEmpty(),
                    kind = kind,
                    seasonNumber = if (kind == "tv") episode?.number("season_number") ?: 0 else 0,
                    episodeNumber = if (kind == "tv") episode?.number("episode_number") ?: 0 else 0,
                    episodeTitle = episode?.text("name"),
                    posterPath = poster,
                    airDate = date.toString(),
                    releaseAtUtc = exact?.toString(),
                    network = item.text("network")?.takeIf(String::isNotBlank),
                    updatedAt = syncedAt,
                ).takeIf(SupplementalCalendarEntry::hasValidShape)
            }.getOrNull()
        }
    // Identical duplicates collapse; conflicting dates/times are not silently guessed.
    val unique =
        entries
            .groupBy(SupplementalCalendarEntry::coordinate)
            .values
            .mapNotNull { rows ->
                rows.first().takeIf { rows.map { it.airDate to it.releaseAtUtc }.distinct().size == 1 }
            }.sortedBy(SupplementalCalendarEntry::coordinate)
    require(items.isEmpty() || unique.isNotEmpty()) { "RE0 calendar contains no usable rows" }
    return Re0CalendarSnapshot(unique, data.number("cache_ttl_seconds") ?: 21_600)
}

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull

private val RE0_ZONE = ZoneId.of("Asia/Shanghai")
private const val MAX_RE0_BYTES = 8 * 1024 * 1024
internal const val MAX_RE0_ENTRIES = 10_000
