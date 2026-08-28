package com.yfuse.watch

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Base64

private val calendarJson = Json { encodeDefaults = true }
private val calendarPublicationLock = Any()
private var cachedPublicationSourceKey: String? = null
private var cachedPublicationModifiedAt: Long = Long.MIN_VALUE
private var cachedPublication: CalendarPublication? = null
private var cachedSignedPublication: CalendarPublication? = null
private var cachedSignedEnvelopeJson: String? = null

@Serializable
private data class CalendarEnvelope(
    val schemaVersion: Int = 1,
    val revision: String,
    val generatedAt: String,
    val payload: String,
    val signature: String,
)

@Serializable
private data class CalendarPayload(
    val schedules: List<CalendarSeries>,
)

@Serializable
internal data class CalendarPublication(
    val revision: String,
    val generatedAt: String,
    val schedules: List<CalendarSeries>,
)

@Serializable
internal data class CalendarSeries(
    val tmdbId: Int,
    val title: String,
    val seasonNumber: Int,
    val posterPath: String? = null,
    val airTime: String? = null,
    val timeZoneId: String? = null,
    val platforms: List<String>,
    val accessTier: String,
    val origin: String = "Domestic",
    val availabilityRegion: String? = null,
    val releaseMode: String = "Scheduled",
    val sourceUrl: String,
    val revision: String,
    val updatedAt: String,
    val authority: String = "Official",
    val confidence: Int = 100,
    val evidence: List<CalendarEvidence> = emptyList(),
    val episodes: List<CalendarEpisode>,
)

@Serializable
internal data class CalendarEvidence(
    val type: String,
    val publisher: String,
    val sourceUrl: String,
    val capturedAt: String,
    val contentHash: String,
    val extractionMethod: String,
)

@Serializable
internal data class CalendarEpisode(
    val episodeNumber: Int,
    val airDate: String,
    val releaseAtUtc: String? = null,
    val releaseAtBeijing: String? = null,
)

internal class CalendarScheduleSigner private constructor(
    private val privateKey: PrivateKey,
) {
    fun sign(payload: ByteArray): String =
        Base64.getEncoder().encodeToString(
            Signature.getInstance("Ed25519").run {
                initSign(privateKey)
                update(payload)
                sign()
            },
        )

    companion object {
        fun fromPkcs8Base64(value: String): CalendarScheduleSigner {
            val key =
                KeyFactory
                    .getInstance("Ed25519")
                    .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(value.trim())))
            return CalendarScheduleSigner(key)
        }

        fun fromEnvironment(): CalendarScheduleSigner? =
            System
                .getenv("YFUSE_CALENDAR_PRIVATE_KEY_PKCS8")
                ?.takeIf(String::isNotBlank)
                ?.let(::fromPkcs8Base64)
    }
}

internal fun Route.calendarScheduleRoutes(
    signer: CalendarScheduleSigner?,
    scheduleStore: CalendarScheduleStore = NoOpCalendarScheduleStore,
) {
    get("/api/v1/calendar/ingestion/status") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(
            text = calendarJson.encodeToString(CalendarIngestionHealth.snapshot()),
            contentType = ContentType.Application.Json,
        )
    }
    get("/api/v1/calendar/schedules") {
        if (signer == null) {
            call.respondText(
                text = "{\"error\":\"calendar_signing_unavailable\"}",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.ServiceUnavailable,
            )
            return@get
        }
        val publication =
            runCatching {
                withContext(Dispatchers.IO) {
                    scheduleStore.current() ?: loadCalendarPublication()
                }
            }.getOrElse {
                call.respondText(
                    text = "{\"error\":\"calendar_publication_invalid\"}",
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.ServiceUnavailable,
                )
                return@get
            }
        val etag = "\"calendar-${publication.revision}\""
        call.response.header(HttpHeaders.ETag, etag)
        call.response.header(HttpHeaders.CacheControl, "public, max-age=300, must-revalidate")
        if (call.request.header(HttpHeaders.IfNoneMatch) == etag) {
            call.respondText("", status = HttpStatusCode.NotModified)
            return@get
        }
        call.respondText(
            text = signedCalendarEnvelopeJson(publication, signer),
            contentType = ContentType.Application.Json,
        )
    }
}

private const val CALENDAR_REVISION = "2026-08-23-r2"

private fun signedCalendarEnvelopeJson(
    publication: CalendarPublication,
    signer: CalendarScheduleSigner,
): String =
    synchronized(calendarPublicationLock) {
        if (cachedSignedPublication == publication) {
            cachedSignedEnvelopeJson?.let { return@synchronized it }
        }
        val payload = calendarJson.encodeToString(CalendarPayload(publication.schedules))
        calendarJson
            .encodeToString(
                CalendarEnvelope(
                    revision = publication.revision,
                    generatedAt = publication.generatedAt,
                    payload = payload,
                    signature = signer.sign(payload.encodeToByteArray()),
                ),
            ).also { encoded ->
                cachedSignedPublication = publication
                cachedSignedEnvelopeJson = encoded
            }
    }

internal fun loadCalendarPublication(): CalendarPublication {
    val inline = System.getenv("YFUSE_CALENDAR_SCHEDULES_JSON")?.takeIf(String::isNotBlank)
    val file =
        System
            .getenv("YFUSE_CALENDAR_SCHEDULES_PATH")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
    val sourceKey =
        when {
            inline != null -> "inline:" + inline.length + ":" + inline.hashCode()
            file != null -> "file:" + file.absolutePath
            else -> "bundled"
        }
    val modifiedAt = file?.takeIf(File::isFile)?.lastModified() ?: 0L

    return synchronized(calendarPublicationLock) {
        if (cachedPublicationSourceKey == sourceKey && cachedPublicationModifiedAt == modifiedAt) {
            cachedPublication?.let { return@synchronized it }
        }
        val raw = inline ?: file?.takeIf(File::isFile)?.readText()
        val publication =
            raw?.let {
                calendarJson.decodeFromString(CalendarPublication.serializer(), it)
            } ?: CalendarPublication(
                revision = CALENDAR_REVISION,
                generatedAt = "2026-08-23T04:00:00Z",
                schedules = DEFAULT_CALENDAR_SCHEDULES,
            )
        validateCalendarPublication(publication)
        cachedPublicationSourceKey = sourceKey
        cachedPublicationModifiedAt = modifiedAt
        cachedPublication = publication
        if (cachedSignedPublication != publication) {
            cachedSignedPublication = null
            cachedSignedEnvelopeJson = null
        }
        publication
    }
}

internal fun validateCalendarPublication(publication: CalendarPublication) {
    require(publication.revision.matches(Regex("\\d{4}-\\d{2}-\\d{2}-r\\d+")))
    require(publication.generatedAt.length in 10..64)
    require(runCatching { Instant.parse(publication.generatedAt) }.isSuccess)
    require(publication.schedules.size <= 1_000)
    publication.schedules.forEach { schedule ->
        require(schedule.tmdbId > 0)
        require(schedule.seasonNumber > 0)
        require(schedule.title.isNotBlank() && schedule.title.length <= 120)
        require(schedule.episodes.isNotEmpty() && schedule.episodes.size <= 500)
        require((schedule.airTime == null) == (schedule.timeZoneId == null))
        schedule.airTime?.let {
            require(it.matches(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")))
            require(runCatching { LocalTime.parse(it) }.isSuccess)
        }
        schedule.timeZoneId?.let { require(runCatching { ZoneId.of(it) }.isSuccess) }
        require(schedule.platforms.isNotEmpty() && schedule.platforms.size <= 10)
        require(schedule.platforms.all { it.isNotBlank() && it.length <= 40 })
        require(schedule.accessTier in setOf("Unknown", "Free", "Member", "SviP"))
        require(schedule.origin in setOf("Domestic", "Foreign"))
        require(schedule.availabilityRegion?.matches(Regex("[A-Z]{2}|GLOBAL")) != false)
        require(schedule.releaseMode in setOf("Scheduled", "Weekly", "Batch", "DateOnly", "Unknown"))
        require(schedule.sourceUrl.startsWith("https://"))
        require(schedule.revision == publication.revision)
        require(runCatching { OffsetDateTime.parse(schedule.updatedAt) }.isSuccess)
        require(schedule.authority in setOf("Official", "Verified", "Estimated"))
        require(
            schedule.authority == "Official" && schedule.confidence in 80..100 ||
                schedule.authority == "Verified" && schedule.confidence in 80..89 ||
                schedule.authority == "Estimated" && schedule.confidence in 60..79,
        )
        require(schedule.evidence.size <= 20)
        require(schedule.evidence.isNotEmpty())
        schedule.evidence.forEach { evidence ->
            require(
                evidence.type in
                    setOf(
                        "PlatformPage",
                        "VerifiedAccount",
                        "OcrConsensus",
                        "TmdbIdentity",
                        "TmdbSchedule",
                        "TvmazeSchedule",
                    ),
            )
            require(evidence.publisher.isNotBlank() && evidence.publisher.length <= 80)
            require(evidence.sourceUrl.startsWith("https://") && evidence.sourceUrl.length <= 2_048)
            require(runCatching { OffsetDateTime.parse(evidence.capturedAt) }.isSuccess)
            require(evidence.contentHash.matches(Regex("[a-f0-9]{64}")))
            require(evidence.extractionMethod.isNotBlank() && evidence.extractionMethod.length <= 80)
        }
        require(
            schedule.episodes.all {
                it.episodeNumber > 0 &&
                    it.airDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) &&
                    runCatching { LocalDate.parse(it.airDate) }.isSuccess &&
                    (it.releaseAtUtc == null) == (it.releaseAtBeijing == null) &&
                    it.releaseAtUtc?.let { timestamp -> runCatching { Instant.parse(timestamp) }.isSuccess } != false &&
                    it.releaseAtBeijing?.let { timestamp -> runCatching { OffsetDateTime.parse(timestamp) }.isSuccess } != false
            },
        )
        require(
            schedule.episodes.distinctBy(CalendarEpisode::episodeNumber).size ==
                schedule.episodes.size,
        )
    }
    require(publication.schedules.distinctBy(CalendarSeries::tmdbId).size == publication.schedules.size)
}

internal val DEFAULT_CALENDAR_SCHEDULES =
    listOf(
        CalendarSeries(
            tmdbId = 272938,
            title = "师兄太稳健",
            seasonNumber = 1,
            posterPath = "/pV38dHjE2fPWmd0ltJQpBdbpz7g.jpg",
            airTime = "12:00",
            timeZoneId = "Asia/Shanghai",
            platforms = listOf("优酷", "爱奇艺"),
            accessTier = "Member",
            sourceUrl = "https://weibo.com/7758737065",
            revision = CALENDAR_REVISION,
            updatedAt = "2026-08-23T12:00:00+08:00",
            confidence = 100,
            evidence =
                listOf(
                    CalendarEvidence(
                        type = "VerifiedAccount",
                        publisher = "师兄太稳健官微",
                        sourceUrl = "https://weibo.com/7758737065",
                        capturedAt = "2026-08-23T12:00:00+08:00",
                        contentHash = "0f9e53e8c823fd09c3b56d03079fe0a634e700597b10ba138c8d55b675d0b93e",
                        extractionMethod = "reviewed-official-text",
                    ),
                ),
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
    )

private fun MutableList<CalendarEpisode>.addEpisodes(
    date: String,
    episodes: IntRange,
) {
    episodes.forEach { add(CalendarEpisode(it, date)) }
}
