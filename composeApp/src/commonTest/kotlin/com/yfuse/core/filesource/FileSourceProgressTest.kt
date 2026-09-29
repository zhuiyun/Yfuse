package com.yfuse.core.filesource

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSourceProgressTest {
    private val movie = fileSourceItemId("fs000000000000000000000001", listOf("电影", "Dune.mkv"))

    @Test
    fun item_ids_are_stable_hashed_and_name_their_source() {
        val again = fileSourceItemId("fs000000000000000000000001", listOf("电影", "Dune.mkv"))
        val shifted = fileSourceItemId("fs000000000000000000000001", listOf("电影Dune.mkv"))

        assertEquals(movie, again)
        assertNotEquals(movie, shifted)
        assertTrue(movie.startsWith("filesource:fs000000000000000000000001:"))
        assertFalse("Dune" in movie)
        assertEquals("fs000000000000000000000001", fileSourceIdOf(movie))
        assertNull(fileSourceIdOf("external-123"))
        assertTrue(isFileSourceItemId(movie))
        assertFalse(isFileSourceItemId("filesource:missing-hash"))
    }

    @Test
    fun a_position_resumes_until_the_last_tenth() {
        val store = FileSourceProgressStore(MapSettings()) { 1_000L }

        store.record(movie, positionMs = 20_000L, durationMs = 7_200_000L, persistNow = true)
        assertEquals(0L, store.get(movie)?.resumePositionMs)

        store.record(movie, positionMs = 3_600_000L, durationMs = 7_200_000L, persistNow = true)
        assertEquals(3_600_000L, store.get(movie)?.resumePositionMs)
        assertEquals(0.5f, store.get(movie)?.fraction)

        store.record(movie, positionMs = 6_600_000L, durationMs = 7_200_000L, persistNow = true)
        val finished = store.get(movie)
        assertEquals(0L, finished?.resumePositionMs)
        assertEquals(true, finished?.watched)
        assertNull(finished?.fraction)
    }

    @Test
    fun a_rewatch_keeps_the_watched_mark_and_records_its_own_point() {
        val store = FileSourceProgressStore(MapSettings())

        store.markWatched(movie, durationMs = 7_200_000L)
        store.record(movie, positionMs = 600_000L, durationMs = 0L, persistNow = true)

        val entry = store.get(movie)
        assertEquals(true, entry?.watched)
        assertEquals(600_000L, entry?.resumePositionMs)
        assertEquals(7_200_000L, entry?.durationMs)
    }

    @Test
    fun held_back_writes_survive_a_flush_and_a_reload() {
        val settings = MapSettings()
        val store = FileSourceProgressStore(settings)

        store.record(movie, positionMs = 90_000L, durationMs = 1_000_000L, persistNow = false)
        assertNull(FileSourceProgressStore(settings).get(movie))

        store.flush()
        assertEquals(90_000L, FileSourceProgressStore(settings).get(movie)?.positionMs)
    }

    @Test
    fun the_recorder_writes_on_pause_and_on_its_interval_only() {
        val settings = MapSettings()
        val store = FileSourceProgressStore(settings)
        var now = 0L
        val recorder = FileSourceProgressRecorder(store) { now }

        recorder.onPosition(movie, 40_000L, 1_000_000L, playing = true)
        assertEquals(40_000L, FileSourceProgressStore(settings).get(movie)?.positionMs)

        now = 10_000L
        recorder.onPosition(movie, 50_000L, 1_000_000L, playing = true)
        assertEquals(40_000L, FileSourceProgressStore(settings).get(movie)?.positionMs)

        now = 11_000L
        recorder.onPosition(movie, 51_000L, 1_000_000L, playing = false)
        assertEquals(51_000L, FileSourceProgressStore(settings).get(movie)?.positionMs)

        now = 45_000L
        recorder.onPosition(movie, 52_000L, 1_000_000L, playing = true)
        assertEquals(52_000L, FileSourceProgressStore(settings).get(movie)?.positionMs)
    }

    @Test
    fun removing_a_source_forgets_only_its_files() {
        val store = FileSourceProgressStore(MapSettings())
        val other = fileSourceItemId("fs000000000000000000000002", listOf("a.mkv"))
        store.record(movie, 60_000L, 1_000_000L, persistNow = true)
        store.record(other, 60_000L, 1_000_000L, persistNow = true)

        store.removeSource("fs000000000000000000000001")

        assertNull(store.get(movie))
        assertEquals(60_000L, store.get(other)?.positionMs)
    }

    @Test
    fun unreadable_or_foreign_entries_are_dropped_on_load() {
        val settings = MapSettings()
        settings.putString("filesources.progress.v1", """{"emby-123":{"p":5},"$movie":{"p":60000}}""")

        val store = FileSourceProgressStore(settings)

        assertEquals(setOf(movie), store.progress.value.keys)
        settings.putString("filesources.progress.v1", "not json")
        assertEquals(emptyMap(), FileSourceProgressStore(settings).progress.value)
    }
}
