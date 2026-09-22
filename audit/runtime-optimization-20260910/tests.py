from edit import *

p='composeApp/src/commonTest/kotlin/com/yfuse/core2/legacy/YPlayerVideoEngineAdapterTest.kt'
replace(p, 'import com.yfuse.core2.api.YTrackType', 'import com.yfuse.core2.api.YTrack\nimport com.yfuse.core2.api.YTrackType')
replace(p, 'class YPlayerVideoEngineAdapterTest {', '''class YPlayerVideoEngineAdapterTest {
    @Test
    fun position_ticks_reuse_track_lists_but_selection_changes_are_published() {
        val player = FakeYPlayer()
        player.mutableState.value = YPlayerState(
            audioTracks = listOf(YTrack("a", YTrackType.Audio, "Audio")),
            subtitleTracks = listOf(YTrack("s", YTrackType.Subtitle, "Subtitle")),
        )
        val flow = player.asPlaybackStateFlow()
        val first = flow.value
        assertSame(first, flow.value)
        player.mutableState.value = player.mutableState.value.copy(positionMs = 500L)
        val tick = flow.value
        assertEquals(500L, tick.positionMs)
        assertSame(first.audioTracks, tick.audioTracks)
        assertSame(first.subtitleTracks, tick.subtitleTracks)
        player.mutableState.value = player.mutableState.value.copy(
            subtitleTracks = listOf(YTrack("s", YTrackType.Subtitle, "Subtitle", selected = true)),
        )
        assertTrue(flow.value.subtitleTracks.single().selected)
        assertSame(tick.audioTracks, flow.value.audioTracks)
    }
''')
p='composeApp/src/commonTest/kotlin/com/yfuse/core/data/PlaybackEventOutboxTest.kt'
replace(p, 'class PlaybackEventOutboxTest {', '''class PlaybackEventOutboxTest {
    @Test
    fun terminal_loss_survives_restart_and_acknowledgement_preserves_new_losses() {
        val settings = MapSettings()
        val outbox = PlaybackEventOutbox(settings, maxEvents = 1)
        fun stop(id: String) = outbox.enqueue(PlaybackOutboxEventKind.Stopped, "a", id, id, 1L, true, "DirectPlay")
        stop("one")
        stop("two")
        assertEquals(1L, outbox.droppedTerminalEvents.value)
        assertEquals(1L, PlaybackEventOutbox(settings).droppedTerminalEvents.value)
        val observed = outbox.droppedTerminalEvents.value
        stop("three")
        outbox.acknowledgeDroppedReports(observed)
        assertEquals(1L, outbox.droppedTerminalEvents.value)
        outbox.acknowledgeDroppedReports(1L)
        assertEquals(0L, PlaybackEventOutbox(settings).droppedTerminalEvents.value)
        assertEquals("three", outbox.events.value.single().itemId)
    }

    @Test
    fun evicting_replaceable_progress_does_not_report_lost_terminal_events() {
        val outbox = PlaybackEventOutbox(MapSettings(), maxEvents = 1)
        outbox.enqueue(PlaybackOutboxEventKind.Progress, "a", "one", "one", 1L, false, "DirectPlay")
        outbox.enqueue(PlaybackOutboxEventKind.Stopped, "a", "two", "two", 1L, true, "DirectPlay")
        assertEquals(0L, outbox.droppedTerminalEvents.value)
    }
''')
p='composeApp/src/commonTest/kotlin/com/yfuse/core/data/LibraryCacheTest.kt'
replace(p, 'class LibraryCacheTest {', '''class LibraryCacheTest {
    @Test
    fun separated_cache_opens_lazily_and_does_not_write_general_preferences() {
        val general = MapSettings()
        val feeds = MapSettings()
        var opens = 0
        val cache = LibraryCache(general, storage = { opens++; feeds })
        assertEquals(0, opens)
        cache.write("server", HomeContent(rows = listOf(HomeRow("library", "Movies", listOf(item("one"))))), 42L)
        assertEquals(1, opens)
        assertTrue(general.keys.isEmpty())
        assertEquals(42L, cache.readSnapshot("server")?.updatedAtEpochMs)
        assertEquals(1, opens)
    }
''')
p='composeApp/src/androidMain/kotlin/com/yfuse/feature/player/AndroidNativeCrashMonitor.kt'
replace(p,'    fun diagnosticSummary(): String {', '    fun diagnosticSummary(): String {\n        if (!::appContext.isInitialized) return "nativeCrash.status=initializing\\n"')

# Revisit coalescing preserves explicit user refresh and server-switch semantics.
p='composeApp/src/commonMain/kotlin/com/yfuse/feature/home/HomeStore.kt'
replace(p, '        private var nextUpJob: Job? = null', '        private var nextUpJob: Job? = null\n        private var lastLibraryRevisit: kotlin.time.TimeMark? = null')
replace(p, '''                HomeIntent.RefreshLibrary -> {
                    loadResume''', '''                HomeIntent.RefreshLibrary -> {
                    if (resumeJob?.isActive == true || nextUpJob?.isActive == true) return
                    if (lastLibraryRevisit?.elapsedNow()?.inWholeMilliseconds?.let { it < 15_000L } == true) return
                    lastLibraryRevisit = kotlin.time.TimeSource.Monotonic.markNow()
                    loadResume''')
