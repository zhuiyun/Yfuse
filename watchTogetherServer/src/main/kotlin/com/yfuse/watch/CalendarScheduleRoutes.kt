package com.yfuse.watch

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64

private val calendarJson = Json { encodeDefaults = true }

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
private data class CalendarSeries(
    val tmdbId: Int,
    val title: String,
    val seasonNumber: Int,
    val posterPath: String? = null,
    val airTime: String,
    val timeZoneId: String,
    val platforms: List<String>,
    val accessTier: String,
    val sourceUrl: String,
    val revision: String,
    val updatedAt: String,
    val episodes: List<CalendarEpisode>,
)

@Serializable
private data class CalendarEpisode(
    val episodeNumber: Int,
    val airDate: String,
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
            System.getenv("YFUSE_CALENDAR_PRIVATE_KEY_PKCS8")
                ?.takeIf(String::isNotBlank)
                ?.let(::fromPkcs8Base64)
    }
}

internal fun Route.calendarScheduleRoutes(signer: CalendarScheduleSigner?) {
    get("/api/v1/calendar/schedules") {
        if (signer == null) {
            call.respondText(
                text = "{\"error\":\"calendar_signing_unavailable\"}",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.ServiceUnavailable,
            )
            return@get
        }
        val payload = calendarJson.encodeToString(CalendarPayload(SCHEDULES))
        val envelope =
            CalendarEnvelope(
                revision = CALENDAR_REVISION,
                generatedAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
                payload = payload,
                signature = signer.sign(payload.encodeToByteArray()),
            )
        call.respondText(
            text = calendarJson.encodeToString(envelope),
            contentType = ContentType.Application.Json,
        )
    }
}

private const val CALENDAR_REVISION = "2026-08-23-r2"

private val SCHEDULES =
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

private fun MutableList<CalendarEpisode>.addEpisodes(date: String, episodes: IntRange) {
    episodes.forEach { add(CalendarEpisode(it, date)) }
}
