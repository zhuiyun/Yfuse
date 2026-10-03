package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.model.SavedServer
import com.yfuse.core.sync.playback.PlaybackMutationKind
import com.yfuse.core.sync.playback.PlaybackSyncStore
import com.yfuse.core.sync.playback.PlaybackSyncTrigger
import com.yfuse.feature.homeRoutes
import com.yfuse.feature.json
import com.yfuse.feature.testRepo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalHistoryWindowTest {
    private val server = SavedServer("id", "http://host:8096", "zhuiyun", "u1", "zhuiyun", "tok")

    @Test
    fun keeps_the_newest_entry_of_each_work_in_order() {
        val entries = listOf("a3", "a2", "b1", "a1", "c1", "b0")

        val newest = newestPerWork(entries, limit = 10) { it.take(1) }

        assertEquals(listOf("a3", "b1", "c1"), newest)
    }

    @Test
    fun the_limit_counts_works_not_entries() {
        val entries = (60 downTo 1).map { "drama:$it" } + listOf("film:1", "show:9")

        val newest = newestPerWork(entries, limit = 2) { it.substringBefore(':') }

        assertEquals(listOf("drama:60", "film:1"), newest)
    }

    @Test
    fun entries_without_a_known_work_are_skipped() {
        val newest = newestPerWork(listOf("x", "", "y"), limit = 5) { it.takeIf { id -> id != "x" } }

        assertEquals(listOf("y"), newest)
    }

    @Test
    fun continue_watching_reaches_a_show_watched_before_a_long_binge() =
        runTest {
            var now = 1_000L
            val progressStore = PlaybackSyncStore(MapSettings()) { now }

            fun watch(itemId: String) {
                now += 1_000L
                progressStore.updatePlayback(
                    mediaKey = "emby:$itemId",
                    aliases = emptyList(),
                    positionMs = 30_000L,
                    durationMs = 100_000L,
                    played = false,
                    sessionId = "local",
                    serverId = server.id,
                    serverItemId = itemId,
                    mutationKind = PlaybackMutationKind.AutoProgress,
                    trigger = PlaybackSyncTrigger.Periodic,
                )
            }
            watch("other1")
            (1..20).forEach { watch("drama$it") }
            val repo =
                testRepo(progressProjection = PlaybackProgressProjection(progressStore) { true }) { request ->
                    val ids = request.url.parameters["Ids"]
                    if (ids != null) {
                        json("""{"Items":[${ids.split(",").joinToString(",", transform = ::episodeJson)}]}""")
                    } else {
                        homeRoutes(request)
                    }
                }

            val resume = repo.homeContent(server).getOrThrow().resume

            assertEquals(listOf("drama20", "other1"), resume.map { it.id })
        }

    @Test
    fun next_up_reaches_a_show_finished_before_a_long_binge() =
        runTest {
            var now = 1_000L
            val progressStore = PlaybackSyncStore(MapSettings()) { now }

            fun finish(itemId: String) {
                now += 1_000L
                progressStore.updatePlayback(
                    mediaKey = "emby:$itemId",
                    aliases = emptyList(),
                    positionMs = 100_000L,
                    durationMs = 100_000L,
                    played = true,
                    sessionId = "local",
                    serverId = server.id,
                    serverItemId = itemId,
                    mutationKind = PlaybackMutationKind.AutoFinished,
                    trigger = PlaybackSyncTrigger.Completed,
                )
            }
            finish("other1")
            (1..40).forEach { finish("drama$it") }
            val repo =
                testRepo(progressProjection = PlaybackProgressProjection(progressStore) { true }) { request ->
                    val path = request.url.encodedPath
                    val ids = request.url.parameters["Ids"]
                    when {
                        ids != null ->
                            json("""{"Items":[${ids.split(",").joinToString(",", transform = ::episodeJson)}]}""")
                        path.endsWith("/Shows/s-drama/Episodes") ->
                            json("""{"Items":[${(1..41).joinToString(",") { episodeJson("drama$it") }}]}""")
                        path.endsWith("/Shows/s-other/Episodes") ->
                            json("""{"Items":[${episodeJson("other1")},${episodeJson("other2")}]}""")
                        else -> homeRoutes(request)
                    }
                }

            val next = repo.nextUpEpisodes(server, 8).getOrThrow()

            assertEquals(listOf("drama41", "other2"), next.map { it.id })
        }

    private fun episodeJson(id: String): String {
        val series = if (id.startsWith("other")) "s-other" else "s-drama"
        val number = id.dropWhile { !it.isDigit() }
        return """{"Id":"$id","Name":"第${number}集","Type":"Episode","SeriesName":"$series",""" +
            """"SeriesId":"$series","IndexNumber":$number,"ParentIndexNumber":1}"""
    }
}
