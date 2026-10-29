package com.yfuse.core.widget

import com.yfuse.feature.player.PlaybackReportingTarget
import com.yfuse.feature.player.playbackReportingTarget
import com.yfuse.feature.player.widgetPlaybackItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WidgetPlaybackIdentityTest {
    private val source =
        WidgetSourceContext(
            "catalog",
            WidgetMedia("series-42", "同一部剧", tmdbId = "42", mediaType = "tv", type = "tmdb", year = "2026"),
        )

    @Test fun rotatingUrlsAndDifferentResourcesRetainTheEpisodeIdentity() {
        val identity = source.playbackIdentity(season = 2, episode = 3)
        val first =
            widgetPlaybackItem(
                source.media.copy(videoUrl = "https://a.example/one?token=old"),
                WidgetPlaybackRequest(identity),
            )
        val next =
            widgetPlaybackItem(
                source.media.copy(videoUrl = "https://b.example/two?token=new"),
                WidgetPlaybackRequest(identity),
            )
        assertEquals(first.id, next.id)
        assertEquals("tmdb:42/s2e3", next.watchKey)
        assertEquals("tmdb:42", next.seriesKey)
        assertNotEquals(identity.mediaKey, source.playbackIdentity(season = 2, episode = 4).mediaKey)
        assertEquals(PlaybackReportingTarget.LocalOnly, playbackReportingTarget(next))
        assertEquals("Episode", next.mediaType)
        assertEquals(
            com.yfuse.feature.player.LocalPlaybackEventSink,
            com.yfuse.feature.player.playbackEventSinkFor(
                playbackReportingTarget(next),
            ) { error("Must never resolve a server") },
        )
    }

    @Test fun anUnmatchedTitleNeverCollidesWithAnotherWidgetOrRemake() {
        val unknown = source.copy(media = source.media.copy(tmdbId = "", type = "url"))
        val original = unknown.playbackIdentity(season = 1, episode = 1)
        assertNotEquals(
            original.mediaKey,
            unknown.copy(widgetId = "other").playbackIdentity(season = 1, episode = 1).mediaKey,
        )
        assertNotEquals(
            original.mediaKey,
            unknown.copy(media = unknown.media.copy(year = "2000")).playbackIdentity(season = 1, episode = 1).mediaKey,
        )
        assertTrue(original.mediaKey in source.playbackIdentity(season = 1, episode = 1).aliases)
    }

    @Test fun historyRetainsOnlyCatalogContextAndReopensTheSameWork() {
        val context =
            source.copy(
                media =
                    source.media.copy(
                        link = "https://user:password@example.com/title?id=42&access_token=secret#fragment",
                        videoUrl = "https://cdn.example/private?token=secret",
                        headers =
                            mapOf(
                                "Authorization" to "Bearer secret",
                            ),
                        children = listOf(source.media.copy(videoUrl = "secret")),
                    ),
                params = buildJsonObject { put("apiKey", "secret") },
            )
        val request = WidgetPlaybackRequest(context.playbackIdentity(season = 1, episode = 3), 123_000, context)
        val item = widgetPlaybackItem(context.media, request)
        val retained = requireNotNull(item.widgetSource)
        val json = Json.encodeToString(WidgetSourceContext.serializer(), retained)
        assertFalse("secret" in json)
        assertFalse("password" in json)
        assertEquals("https://example.com/title?id=42", retained.media.link)
        assertEquals(
            context.playbackIdentity(season = 1, episode = 3),
            retained.playbackIdentity(season = 1, episode = 3),
        )
    }
}
