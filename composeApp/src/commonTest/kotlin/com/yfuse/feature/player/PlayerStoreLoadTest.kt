package com.yfuse.feature.player

import com.yfuse.core.data.MediaVersionPreference
import com.yfuse.core.logging.playbackDiagnosticTrace
import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.SavedServer
import com.yfuse.feature.testRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class PlayerStoreLoadTest {
    private val server = SavedServer("id", "http://host:8096", "server", "u1", "user", "tok")

    private fun version(
        id: String,
        range: String? = null,
    ) = MediaVersion(
        id = id,
        name = id,
        container = "mkv",
        sizeBytes = 100L,
        bitrateBps = null,
        videoCodec = "hevc",
        videoHeight = 2160,
        videoRange = range,
        supportsDirectPlay = true,
    )

    /** A load that opened "e2". */
    private fun queue(
        negotiated: List<MediaVersion> = emptyList(),
        sessionId: String? = null,
        pickedMediaSourceId: String? = null,
    ) = PlayerQueueItemBuilder(
        server = server,
        currentItemId = "e2",
        currentMediaSourceId = pickedMediaSourceId,
        negotiatedVersions = negotiated,
        negotiatedSessionId = sessionId,
        mediaVersionPreference = MediaVersionPreference.HdrFirst,
    )

    @Test
    fun aStageLineNamesTheSettledEntryAndWhetherItsServerIsTheDefault() {
        val attributes =
            playbackPreparationStageAttributes(
                stage = "playback_info_ready",
                itemId = "e2",
                serverId = "backup",
                defaultServerId = "primary",
                requestSessionId = "request-session",
                sessionId = null,
                outcome = "timeout",
                elapsedMs = 1_234L,
            )
        assertEquals(
            listOf(
                "stage",
                "itemId",
                "serverId",
                "usesDefaultServer",
                "requestSessionId",
                "sessionId",
                "requestTrace",
                "playbackTrace",
                "outcome",
                "elapsedMs",
            ),
            attributes.keys.toList(),
        )
        assertEquals("e2", attributes["itemId"])
        assertEquals("false", attributes["usesDefaultServer"])
        assertEquals("", attributes["sessionId"])
        assertEquals(playbackDiagnosticTrace("request-session"), attributes["requestTrace"])
        assertEquals(playbackDiagnosticTrace(null), attributes["playbackTrace"])
        assertEquals("timeout", attributes["outcome"])
        assertEquals("1234", attributes["elapsedMs"])

        val onDefault =
            playbackPreparationStageAttributes(
                stage = "current_item_ready",
                itemId = "e2",
                serverId = "primary",
                defaultServerId = "primary",
                requestSessionId = "request-session",
                sessionId = "played-session",
                outcome = "ready",
                elapsedMs = 0L,
            )
        assertEquals("true", onDefault["usesDefaultServer"])
        assertEquals("played-session", onDefault["sessionId"])
    }

    @Test
    fun aStageOnTheSettledEntryCarriesOnTheTimingOfTheRequestedOne() {
        val timing = PlaybackLaunchTiming()
        PlaybackLaunchTimings.register("stages-primary", "stages-series", timing)
        try {
            val stages =
                PlaybackPreparationStages(
                    requestedItemId = "stages-series",
                    primaryServerId = "stages-primary",
                    registry = testRegistry(),
                    requestedSessionId = "request-session",
                    startedAt = TimeSource.Monotonic.markNow(),
                )
            stages.record("item_detail_ready", "stages-episode", "stages-backup")
            assertSame(timing, PlaybackLaunchTimings.find("stages-backup", "stages-episode"))
            assertNull(PlaybackLaunchTimings.find("stages-backup", "stages-episode", playSessionId = "played-session"))

            stages.record("current_item_ready", "stages-episode", "stages-backup", sessionId = "played-session")
            val bound = PlaybackLaunchTimings.find("stages-backup", "stages-episode", playSessionId = "played-session")
            assertSame(timing, bound)
        } finally {
            PlaybackLaunchTimings.remove("stages-primary", "stages-series")
            PlaybackLaunchTimings.remove("stages-backup", "stages-episode")
        }
    }

    @Test
    fun playbackInfoEndsReadyFailedOrTimedOut() {
        assertEquals("timeout", playbackNegotiationOutcome(null))
        assertEquals("failed", playbackNegotiationOutcome(Result.failure<Unit>(IllegalStateException("503"))))
        assertEquals("ready", playbackNegotiationOutcome(Result.success(Unit)))
    }

    @Test
    fun theOpenedEntryPlaysWhatWasNegotiatedAndItsSiblingsTheirOwnSources() {
        val items = queue(negotiated = listOf(version("e2-negotiated")), sessionId = "negotiated-session")

        val opened = items.itemOf(id = "e2", title = "二", versions = listOf(version("e2-listed")))
        assertEquals("e2-negotiated", opened.versionId)
        assertEquals("negotiated-session", opened.playSessionId)
        assertTrue("MediaSourceId=e2-negotiated" in opened.url, opened.url)
        assertTrue("PlaySessionId=negotiated-session" in opened.url, opened.url)
        assertEquals("id", opened.serverId)

        val sibling = items.itemOf(id = "e3", title = "三", versions = listOf(version("e3-listed")))
        assertEquals("e3-listed", sibling.versionId)
        assertNotEquals("negotiated-session", sibling.playSessionId)
        assertTrue("MediaSourceId=e3-listed" in sibling.url, sibling.url)
    }

    @Test
    fun theDetailPagesPickHoldsForTheOpenedEntryAndThePreferenceForEveryOther() {
        val versions = listOf(version("sdr"), version("hdr", range = "HDR10"))
        val items = queue(pickedMediaSourceId = "sdr")
        assertEquals("sdr", items.itemOf(id = "e2", title = "二", versions = versions).versionId)
        assertEquals("hdr", items.itemOf(id = "e3", title = "三", versions = versions).versionId)
    }

    @Test
    fun anEntryWithoutFetchedSourcesPlaysTheFileTheServerWouldPick() {
        val item = queue().itemOf(id = "e5", title = "五")
        assertNull(item.versionId)
        assertTrue(item.versions.isEmpty())
        assertTrue(item.url.startsWith("http://host:8096/Videos/e5/stream?static=true"), item.url)
        assertFalse("MediaSourceId=" in item.url, item.url)
        assertTrue("PlaySessionId=${item.playSessionId}" in item.url, item.url)
    }

    @Test
    fun anEntryIsAnEpisodeOnlyWithASeriesOrANumberAndItsRuntimeIsOnlyAHint() {
        val items = queue()
        val film = items.itemOf(id = "m1", title = "电影", runtimeTicks = 72_000_000_000L)
        assertEquals("Movie", film.mediaType)
        assertEquals(7_200_000L, film.durationMsHint)
        assertEquals("Episode", items.itemOf(id = "e7", title = "七", episodeNumber = 7).mediaType)
        assertEquals("Episode", items.itemOf(id = "e8", title = "八", seriesId = "s1").mediaType)
        assertEquals(0L, items.itemOf(id = "e9", title = "九", runtimeTicks = 0L).durationMsHint)
    }
}
