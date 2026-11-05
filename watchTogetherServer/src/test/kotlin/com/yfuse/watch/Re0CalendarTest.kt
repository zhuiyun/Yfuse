package com.yfuse.watch

import com.yfuse.watch.protocol.SupplementalCalendarEntry
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Re0CalendarTest {
    private val today = LocalDate.parse("2026-10-30")
    private val row =
        SupplementalCalendarEntry(
            tmdbId = 123,
            title = "Example",
            kind = "tv",
            seasonNumber = 2,
            episodeNumber = 3,
            airDate = "2026-10-30",
            updatedAt = "2026-10-30T03:00:00Z",
        )

    @Test
    fun parses_tv_and_movie_without_inventing_seasons_and_uses_instant_for_local_day() {
        val parsed = parseRe0Calendar(payload("${item()},${item(kind = "movie")},${item(season = 0)}"), today)
        assertEquals(2, parsed.entries.size)
        assertEquals(setOf("movie:123", "tv:123:2:3"), parsed.entries.map { it.coordinate }.toSet())
        assertEquals(setOf("2026-10-31"), parsed.entries.map { it.airDate }.toSet())
        assertEquals(setOf("/poster.jpg"), parsed.entries.map { it.posterPath }.toSet())
    }

    @Test
    fun deduplicates_identical_rows_but_discards_conflicting_coordinates() {
        assertEquals(1, parseRe0Calendar(payload("${item()},${item()}"), today).entries.size)
        val conflict = item().replace("16:00:00Z", "17:00:00Z")
        assertEquals(
            listOf(
                "movie:123",
            ),
            parseRe0Calendar(payload("${item()},$conflict,${item(kind = "movie")}"), today).entries.map {
                it.coordinate
            },
        )
    }

    @Test
    fun stale_partial_and_invalid_payloads_do_not_replace_cache() {
        for (text in listOf(
            payloadText(item()).replace("\"is_stale\":false", "\"is_stale\":true"),
            payloadText(item()).replace("\"is_partial\":false", "\"is_partial\":true"),
            payloadText(item(season = 0)),
        )) {
            assertFailsWith<IllegalArgumentException> {
                parseRe0Calendar(ingestionJson.parseToJsonElement(text) as JsonObject, today)
            }
        }
        assertTrue(parseRe0Calendar(payload(""), today).entries.isEmpty())
    }

    @Test
    fun feed_respects_ttl_retains_cache_on_failure_and_clears_when_disabled() {
        var instant = Instant.parse("2026-10-30T04:00:00Z")
        var calls = 0
        val feed =
            Re0CalendarFeed(fetch = {
                calls++
                if (calls > 1) error("unavailable")
                Re0CalendarSnapshot(listOf(row), 21_600)
            }, now = { instant })
        val config = Re0CalendarConfig(enabled = true)
        assertEquals(listOf(row), feed.entries(config, emptyList()))
        assertEquals(listOf(row), feed.entries(config, emptyList()))
        assertEquals(1, calls)
        instant = instant.plusSeconds(21_600)
        assertEquals(listOf(row), feed.entries(config, emptyList()))
        assertEquals(2, calls)
        assertEquals(listOf(row), feed.entries(config, emptyList()))
        assertEquals(2, calls)
        assertTrue(feed.entries(config.copy(enabled = false), listOf(row)).isEmpty())
    }

    @Test
    fun failure_after_restart_retains_persisted_rows_and_success_removes_cancelled_future_rows() {
        val instant = Instant.parse("2026-10-30T04:00:00Z")
        val config = Re0CalendarConfig(enabled = true)
        val failed = Re0CalendarFeed(fetch = { error("offline") }, now = { instant })
        assertEquals(listOf(row), failed.entries(config, listOf(row)))
        val empty = Re0CalendarFeed(fetch = { Re0CalendarSnapshot(emptyList(), 21_600) }, now = { instant })
        assertTrue(empty.entries(config, listOf(row)).isEmpty())
    }

    @Test
    fun supplemental_entries_survive_sqlite_reopen_and_are_covered_by_signature() {
        val directory = Files.createTempDirectory("re0-calendar").toFile()
        try {
            val file = directory.resolve("calendar.db")
            val publication = CalendarPublication("2026-10-30-r1", "2026-10-30T04:00:00Z", emptyList(), listOf(row))
            CalendarScheduleStore.sqlite(file).use { assertTrue(it.replace(publication)) }
            CalendarScheduleStore.sqlite(file).use { assertEquals(publication, it.current()) }
            val keys =
                java.security.KeyPairGenerator
                    .getInstance("Ed25519")
                    .generateKeyPair()
            val signer =
                CalendarScheduleSigner.fromPkcs8Base64(
                    java.util.Base64
                        .getEncoder()
                        .encodeToString(keys.private.encoded),
                )
            val envelope = ingestionJson.parseToJsonElement(signer.envelopeJson(publication)) as JsonObject
            val payload = (envelope["payload"] as kotlinx.serialization.json.JsonPrimitive).content
            assertTrue(payload.contains("supplemental") && payload.contains("RE0"))
            val verifier = java.security.Signature.getInstance("Ed25519")
            verifier.initVerify(keys.public)
            verifier.update(payload.encodeToByteArray())
            assertTrue(
                verifier.verify(
                    java.util.Base64.getDecoder().decode(
                        (envelope["signature"] as kotlinx.serialization.json.JsonPrimitive).content,
                    ),
                ),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun credentials_only_travel_to_explicit_https_or_loopback_origins() {
        Re0CalendarConfig(enabled = true).validate()
        Re0CalendarConfig(enabled = true, baseUrl = "https://calendar.example").validate()
        for (url in listOf(
            "http://example.com",
            "https://user:pass@example.com",
            "https://example.com/?token=secret",
            "https://example.com/path",
        )) {
            assertFailsWith<IllegalArgumentException> { Re0CalendarConfig(enabled = true, baseUrl = url).validate() }
        }
    }

    @Test
    fun http_client_logs_in_and_reads_calendar_with_session_cookie_without_forcing_refresh() {
        val server =
            com.sun.net.httpserver.HttpServer
                .create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val loginBody =
            java.util.concurrent.atomic
                .AtomicReference<String>()
        val calendarCookie =
            java.util.concurrent.atomic
                .AtomicReference<String>()
        val calendarQuery =
            java.util.concurrent.atomic
                .AtomicReference<String>()
        server.createContext("/api/login") { exchange ->
            loginBody.set(exchange.requestBody.use { it.readBytes().decodeToString() })
            exchange.responseHeaders.add("Set-Cookie", "session=fixture; Path=/; HttpOnly")
            val bytes = """{"success":true}""".encodeToByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/media/calendar") { exchange ->
            calendarCookie.set(exchange.requestHeaders.getFirst("Cookie"))
            calendarQuery.set(exchange.requestURI.query)
            val bytes = payloadText("").encodeToByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val config = Re0CalendarConfig(enabled = true, baseUrl = "http://127.0.0.1:${server.address.port}")
            val client = Re0CalendarClient { if (it == config.usernameEnvironment) "fixture-user" else "fixture-pass" }
            assertTrue(client.fetch(config).entries.isEmpty())
            assertTrue(loginBody.get().contains("fixture-user"))
            assertTrue(calendarCookie.get().contains("session=fixture"))
            assertEquals("days=30", calendarQuery.get())
        } finally {
            server.stop(0)
        }
    }

    private fun payload(items: String) = ingestionJson.parseToJsonElement(payloadText(items)) as JsonObject

    private fun payloadText(items: String) =
        """{"success":true,"data":{"source":"hdhive","feed_status":"ready","is_stale":false,"is_partial":false,"upstream_synced_at":"2026-10-30T03:00:00Z","items":[$items]}}"""

    private fun item(
        kind: String = "tv",
        season: Int = 2,
    ) =
        """{"origin":"hdhive","source":"tmdb","tmdb_id":123,"title":"Example","media_type":"$kind","calendar_date":"2026-10-30","first_aired":"2026-10-30T16:00:00Z","poster_url":"https://image.tmdb.org/t/p/w500/poster.jpg","calendar_episode":{"season_number":$season,"episode_number":3}}"""
}
