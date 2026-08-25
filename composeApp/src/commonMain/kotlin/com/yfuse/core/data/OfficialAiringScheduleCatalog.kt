package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.account.ACCOUNT_BASE_URL
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.AiringAccessTier
import com.yfuse.core.model.AiringScheduleAuthority
import com.yfuse.core.model.ShowOrigin
import com.yfuse.core.security.verifyEd25519Signature
import com.yfuse.core.util.currentEpochMillis
import com.yfuse.core.util.scheduledEpochMillis
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Small, reviewed overlay for platform schedules that are published as poster images rather
 * than a stable API. Entries are keyed by provider id, never by a localized title, so the
 * live-action series cannot be confused with the animation 《师兄啊师兄》.
 *
 * A platform revision replaces the corresponding block after its official calendar has been
 * checked. TMDB remains the broad discovery source; this catalog only carries dates whose
 * official authority is known.
 */
class OfficialAiringScheduleCatalog(
    private val client: HttpClient,
    private val settings: Settings,
    private val endpoint: String = "$ACCOUNT_BASE_URL/api/v1/calendar/schedules",
    private val nowEpochMs: () -> Long = ::currentEpochMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var schedules: Map<Int, OfficialSeriesSchedule> = loadCachedSchedules() ?: FALLBACK_SCHEDULES

    fun series(
        tmdbId: Int,
        fallbackTitle: String,
    ): List<AiringEpisode>? =
        schedules[tmdbId]
            ?.episodes
            ?.map { slot -> schedules.getValue(tmdbId).toEpisode(slot, fallbackTitle) }

    fun between(
        fromDate: String,
        toDate: String,
    ): List<AiringEpisode> =
        schedules.values
            .flatMap { schedule -> schedule.episodes.map { schedule.toEpisode(it, schedule.title) } }
            .filter { it.airDate in fromDate..toDate }

    /**
     * Refreshes a signed remote overlay at most once per interval.
     *
     * A failed, malformed or unsigned response never replaces the last verified payload. The
     * bundled schedule remains a complete offline fallback for the rows known at release time.
     */
    suspend fun refreshIfDue(force: Boolean = false): Result<Boolean> {
        val now = nowEpochMs()
        val lastAttempt = settings.getLong(KEY_LAST_ATTEMPT_EPOCH_MS, 0L)
        val lastSuccess = settings.getLong(KEY_LAST_SUCCESS_EPOCH_MS, 0L)
        val retryInterval =
            if (lastAttempt > lastSuccess) {
                FAILURE_RETRY_INTERVAL_MS
            } else {
                REFRESH_INTERVAL_MS
            }
        if (!force && now - lastAttempt in 0 until retryInterval) return Result.success(false)
        settings.putLong(KEY_LAST_ATTEMPT_EPOCH_MS, now)
        return runCatching {
            val response =
                withTimeout(REMOTE_DEADLINE_MS) {
                    client.get(endpoint) {
                        settings.getString(KEY_REVISION, "")
                            .takeIf(String::isNotBlank)
                            ?.let { revision ->
                                header(HttpHeaders.IfNoneMatch, "\"calendar-$revision\"")
                            }
                    }
                }
            if (response.status == HttpStatusCode.NotModified) {
                settings.putLong(KEY_LAST_SUCCESS_EPOCH_MS, now)
                return@runCatching false
            }
            val envelope = response.body<OfficialScheduleEnvelope>()
            require(envelope.schemaVersion == SCHEMA_VERSION) { "Unsupported calendar schema" }
            require(
                verifyEd25519Signature(
                    publicKeyBase64 = CALENDAR_PUBLIC_KEY,
                    payload = envelope.payload.encodeToByteArray(),
                    signatureBase64 = envelope.signature,
                ),
            ) { "Calendar signature verification failed" }
            val payload = json.decodeFromString<OfficialSchedulePayload>(envelope.payload)
            val verified =
                validate(payload, expectedRevision = envelope.revision)
                    .associateBy(OfficialSeriesSchedule::tmdbId)
            val existingRevision = settings.getString(KEY_REVISION, "")
            require(existingRevision.isBlank() || calendarRevisionIsAtLeast(envelope.revision, existingRevision)) {
                "Calendar revision rollback rejected"
            }
            val updatedSchedules = FALLBACK_SCHEDULES + verified
            if (existingRevision.isNotBlank() && envelope.revision != existingRevision) {
                val changes = detectScheduleChanges(schedules, updatedSchedules, now)
                if (changes.isNotEmpty()) {
                    settings.putString(KEY_CHANGES, json.encodeToString(changes))
                }
            }
            schedules = updatedSchedules
            settings.putString(KEY_ENVELOPE, json.encodeToString(OfficialScheduleEnvelope.serializer(), envelope))
            settings.putString(KEY_REVISION, envelope.revision)
            settings.putLong(KEY_LAST_SUCCESS_EPOCH_MS, now)
            true
        }.onFailure { error ->
            AppLog.warning(
                category = "feature.calendar",
                event = "official_schedule_refresh_failed",
                message = "Signed official calendar overlay could not be refreshed; verified cache remains active",
                throwable = error,
            )
        }
    }

    fun recentChanges(): List<OfficialScheduleChange> =
        settings.getStringOrNull(KEY_CHANGES)
            ?.let { raw ->
                runCatching { json.decodeFromString<List<OfficialScheduleChange>>(raw) }.getOrNull()
            }.orEmpty()

    fun acknowledgeChanges() {
        settings.remove(KEY_CHANGES)
    }

    private fun detectScheduleChanges(
        previous: Map<Int, OfficialSeriesSchedule>,
        updated: Map<Int, OfficialSeriesSchedule>,
        detectedAtEpochMs: Long,
    ): List<OfficialScheduleChange> =
        updated.values
            .flatMap { next ->
                val old = previous[next.tmdbId] ?: return@flatMap emptyList()
                val oldSlots = old.episodes.associateBy(OfficialEpisodeSlot::episodeNumber)
                val nextSlots = next.episodes.associateBy(OfficialEpisodeSlot::episodeNumber)
                buildList {
                    nextSlots.forEach { (episodeNumber, slot) ->
                        val previousSlot = oldSlots[episodeNumber]
                        when {
                            previousSlot == null ->
                                add("新增第 ${episodeNumber} 集：${slot.airDate}")
                            previousSlot.airDate != slot.airDate ->
                                add(
                                    "第 ${episodeNumber} 集由 ${previousSlot.airDate} 调整为 ${slot.airDate}",
                                )
                        }
                    }
                    oldSlots.keys.filterNot(nextSlots::containsKey).forEach { episodeNumber ->
                        add("第 ${episodeNumber} 集已从官方排期移除")
                    }
                    if (old.airTime != next.airTime || old.timeZoneId != next.timeZoneId) {
                        add("播出时间由 ${old.airTime} 调整为 ${next.airTime}")
                    }
                }.map { message ->
                    OfficialScheduleChange(
                        tmdbId = next.tmdbId,
                        title = next.title,
                        message = message,
                        revision = next.revision,
                        detectedAtEpochMs = detectedAtEpochMs,
                    )
                }
            }.take(MAX_RECORDED_CHANGES)

    fun diagnostics(): OfficialScheduleDiagnostics =
        OfficialScheduleDiagnostics(
            revision = settings.getString(KEY_REVISION, BUNDLED_REVISION),
            lastSuccessfulRefreshEpochMs = settings.getLong(KEY_LAST_SUCCESS_EPOCH_MS, 0L),
            seriesCount = schedules.size,
            remoteConfigured = endpoint.startsWith("https://"),
        )

    private fun loadCachedSchedules(): Map<Int, OfficialSeriesSchedule>? {
        val raw = settings.getStringOrNull(KEY_ENVELOPE) ?: return null
        return runCatching {
            val envelope = json.decodeFromString(OfficialScheduleEnvelope.serializer(), raw)
            require(envelope.schemaVersion == SCHEMA_VERSION)
            require(
                verifyEd25519Signature(
                    CALENDAR_PUBLIC_KEY,
                    envelope.payload.encodeToByteArray(),
                    envelope.signature,
                ),
            )
            (
                FALLBACK_SCHEDULES.values +
                    validate(
                        json.decodeFromString<OfficialSchedulePayload>(envelope.payload),
                        expectedRevision = envelope.revision,
                    )
            ).associateBy(OfficialSeriesSchedule::tmdbId)
        }.onFailure {
            settings.remove(KEY_ENVELOPE)
            settings.remove(KEY_REVISION)
        }.getOrNull()
    }

    private fun validate(
        payload: OfficialSchedulePayload,
        expectedRevision: String,
    ): List<OfficialSeriesSchedule> {
        require(payload.schedules.size <= MAX_SERIES)
        require(payload.schedules.distinctBy(OfficialSeriesSchedule::tmdbId).size == payload.schedules.size)
        payload.schedules.forEach { schedule ->
            require(schedule.tmdbId > 0)
            require(schedule.seasonNumber > 0)
            require(schedule.title.isNotBlank() && schedule.title.length <= 120)
            require(schedule.episodes.isNotEmpty() && schedule.episodes.size <= MAX_EPISODES_PER_SERIES)
            require(schedule.sourceUrl.startsWith("https://") && schedule.sourceUrl.length <= 2_048)
            require(schedule.airTime.matches(TIME_PATTERN))
            require(schedule.timeZoneId.matches(ZONE_PATTERN))
            require(schedule.platforms.isNotEmpty() && schedule.platforms.size <= 10)
            require(schedule.platforms.all { it.isNotBlank() && it.length <= 40 })
            require(schedule.revision == expectedRevision)
            require(schedule.updatedAt.length in 10..64)
            require(schedule.episodes.distinctBy(OfficialEpisodeSlot::episodeNumber).size == schedule.episodes.size)
            require(
                schedule.episodes.all {
                    it.airDate.matches(DATE_PATTERN) &&
                        it.episodeNumber > 0 &&
                        scheduledEpochMillis(
                            date = it.airDate,
                            time = schedule.airTime,
                            zoneId = schedule.timeZoneId,
                        ) != null
                },
            )
        }
        return payload.schedules
    }

    @Serializable
    internal data class OfficialSeriesSchedule(
        val tmdbId: Int,
        val title: String,
        val seasonNumber: Int,
        val posterPath: String?,
        val airTime: String,
        val timeZoneId: String,
        val platforms: List<String>,
        val accessTier: AiringAccessTier,
        val sourceUrl: String,
        val revision: String,
        val updatedAt: String,
        val episodes: List<OfficialEpisodeSlot>,
    ) {
        fun toEpisode(
            slot: OfficialEpisodeSlot,
            fallbackTitle: String,
        ) = AiringEpisode(
            showTmdbId = tmdbId,
            showTitle = title.ifBlank { fallbackTitle },
            posterPath = posterPath,
            seasonNumber = seasonNumber,
            episodeNumber = slot.episodeNumber,
            episodeTitle = null,
            airDate = slot.airDate,
            origin = ShowOrigin.Domestic,
            scheduleAuthority = AiringScheduleAuthority.Official,
            airTime = airTime,
            timeZoneId = timeZoneId,
            platforms = platforms,
            accessTier = accessTier,
            sourceUrl = sourceUrl,
            scheduleRevision = revision,
            scheduleUpdatedAt = updatedAt,
        )
    }

    @Serializable
    internal data class OfficialEpisodeSlot(
        val episodeNumber: Int,
        val airDate: String,
    )

    private companion object {
        const val SCHEMA_VERSION = 1
        const val BUNDLED_REVISION = "2026-08-23-r2"
        const val KEY_ENVELOPE = "calendar.official.envelope"
        const val KEY_REVISION = "calendar.official.revision"
        const val KEY_LAST_ATTEMPT_EPOCH_MS = "calendar.official.lastAttemptEpochMs"
        const val KEY_LAST_SUCCESS_EPOCH_MS = "calendar.official.lastSuccessEpochMs"
        const val KEY_CHANGES = "calendar.official.changes.v1"
        const val REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1_000L
        const val FAILURE_RETRY_INTERVAL_MS = 15 * 60 * 1_000L
        const val REMOTE_DEADLINE_MS = 1_500L
        const val MAX_SERIES = 1_000
        const val MAX_EPISODES_PER_SERIES = 500
        const val MAX_RECORDED_CHANGES = 50
        val DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")
        val TIME_PATTERN = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")
        val ZONE_PATTERN = Regex("[A-Za-z_]+(?:/[A-Za-z0-9_+\\-]+)+")

        // The matching private key is deployment-only and is never stored in the app or repo.
        const val CALENDAR_PUBLIC_KEY =
            "MCowBQYDK2VwAyEAC6w4zSGYRGAsf0ITQvKyALSGygZpjCgoH118qQK7hzk="

        /**
         * 《师兄太稳健》会员追剧日历, revised by the official series account on 2026-08-23.
         * Beijing time 12:00, simulcast on Youku and iQIYI. Episodes beyond 21 are omitted
         * until an official calendar publishes their dates.
         */
        val FALLBACK_SCHEDULES =
            listOf(
                OfficialSeriesSchedule(
                    tmdbId = 272938,
                    title = "师兄太稳健",
                    seasonNumber = 1,
                    posterPath = "/pV38dHjE2fPWmd0ltJQpBdbpz7g.jpg",
                    airTime = "12:00",
                    timeZoneId = "Asia/Shanghai",
                    platforms = listOf("优酷", "爱奇艺"),
                    accessTier = AiringAccessTier.Member,
                    sourceUrl = "https://weibo.com/7758737065",
                    revision = "2026-08-23-r2",
                    updatedAt = "2026-08-23T12:00:00+08:00",
                    episodes =
                        buildList {
                            addEpisodes("2026-08-19", 1..3)
                            addEpisodes("2026-08-20", 4..5)
                            addEpisodes("2026-08-21", 6..7)
                            addEpisodes("2026-08-22", 8..9)
                            addEpisodes("2026-08-23", 10..11)
                            addEpisodes("2026-08-24", 12..12)
                            addEpisodes("2026-08-25", 13..14)
                            addEpisodes("2026-08-26", 15..15)
                            addEpisodes("2026-08-27", 16..17)
                            addEpisodes("2026-08-28", 18..18)
                            addEpisodes("2026-08-29", 19..20)
                            addEpisodes("2026-08-30", 21..21)
                        },
                ),
            ).associateBy { it.tmdbId }

        fun MutableList<OfficialEpisodeSlot>.addEpisodes(
            date: String,
            episodeNumbers: IntRange,
        ) {
            episodeNumbers.forEach { add(OfficialEpisodeSlot(it, date)) }
        }
    }
}

/**
 * Compares date-based revisions such as 2026-08-23-r10 numerically.
 *
 * Plain string comparison considers r10 older than r2 and permanently rejects a legitimate
 * tenth correction. Unknown formats retain conservative lexical ordering for compatibility.
 */
internal fun calendarRevisionIsAtLeast(candidate: String, existing: String): Boolean {
    fun parse(value: String): Pair<String, Int>? {
        val marker = value.lastIndexOf("-r")
        if (marker <= 0) return null
        val sequence = value.substring(marker + 2).toIntOrNull() ?: return null
        return value.substring(0, marker) to sequence
    }
    val next = parse(candidate)
    val current = parse(existing)
    return if (next != null && current != null) {
        next.first > current.first || next.first == current.first && next.second >= current.second
    } else {
        candidate >= existing
    }
}

@Serializable
internal data class OfficialScheduleEnvelope(
    val schemaVersion: Int,
    val revision: String,
    val generatedAt: String,
    /** Exact JSON bytes (UTF-8) covered by [signature]. */
    val payload: String,
    val signature: String,
)

@Serializable
internal data class OfficialSchedulePayload(
    val schedules: List<OfficialAiringScheduleCatalog.OfficialSeriesSchedule>,
)

@Serializable
data class OfficialScheduleChange(
    val tmdbId: Int,
    val title: String,
    val message: String,
    val revision: String,
    val detectedAtEpochMs: Long,
)

data class OfficialScheduleDiagnostics(
    val revision: String,
    val lastSuccessfulRefreshEpochMs: Long,
    val seriesCount: Int,
    val remoteConfigured: Boolean,
)
