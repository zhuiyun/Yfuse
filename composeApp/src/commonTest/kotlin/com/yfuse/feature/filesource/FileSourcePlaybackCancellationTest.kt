package com.yfuse.feature.filesource

import com.russhwolf.settings.MapSettings
import com.yfuse.core.filesource.FileSource
import com.yfuse.core.filesource.FileSourceClient
import com.yfuse.core.filesource.FileSourceCredentials
import com.yfuse.core.filesource.FileSourceEntry
import com.yfuse.core.filesource.FileSourceKind
import com.yfuse.core.filesource.FileSourceLibraryStorage
import com.yfuse.core.filesource.FileSourceLibraryStore
import com.yfuse.core.filesource.FileSourceProgressStore
import com.yfuse.core.filesource.FileSourceRegistry
import com.yfuse.core.filesource.FileSourceScanner
import com.yfuse.core.filesource.TmdbTitleMatcher
import com.yfuse.core.security.TestSecureStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Drive the real browser while subtitle preparation is suspended. */
class FileSourcePlaybackCancellationTest {
    private class DelayedClient : FileSourceClient {
        val subtitleGate = CompletableDeferred<Unit>()
        val video = FileSourceEntry("movie.mkv", directory = false)

        override suspend fun list(
            source: FileSource,
            credentials: FileSourceCredentials,
            path: List<String>,
        ) = listOf(video, FileSourceEntry("movie.srt", directory = false), FileSourceEntry("next", directory = true))

        override suspend fun cacheSubtitle(
            source: FileSource,
            credentials: FileSourceCredentials,
            path: List<String>,
            entry: FileSourceEntry,
        ): String {
            subtitleGate.await()
            return "file:///audit/movie.srt"
        }
    }

    private suspend fun TestScope.fixture(): Pair<FileSourcesController, DelayedClient> {
        val settings = MapSettings()
        val registry = FileSourceRegistry(settings, TestSecureStore(), ioDispatcher = Dispatchers.Unconfined)
        val source = FileSource("fs" + "1".repeat(24), FileSourceKind.WebDav, "Audit", "https://example.invalid")
        registry.save(source, password = "")
        val client = DelayedClient()
        val storage =
            object : FileSourceLibraryStorage {
                override fun read(): String? = null

                override fun write(text: String) = Unit
            }
        val scanner =
            FileSourceScanner(
                client,
                TmdbTitleMatcher(
                    search = { _, _, _ -> Result.success(emptyList()) },
                    alternativeTitles = { _, _ -> Result.success(emptyList()) },
                ),
                parseDispatcher = Dispatchers.Unconfined,
            )
        val controller =
            FileSourcesController(
                registry,
                client,
                FileSourceProgressStore(settings),
                FileSourceLibraryStore(storage, Dispatchers.Unconfined),
                scanner,
                backgroundScope,
            )
        controller.open(source)
        runCurrent()
        controller.openFolder("season")
        runCurrent()
        controller.play(client.video)
        runCurrent()
        assertEquals("movie.mkv", controller.browser.value?.preparing)
        assertNull(controller.launch.value)
        return controller to client
    }

    @Test
    fun navigatingUpMustCancelPendingPlayback() =
        runTest(StandardTestDispatcher()) {
            val (controller, client) = fixture()
            controller.navigateUp()
            assertEquals(emptyList(), controller.browser.value?.path)
            client.subtitleGate.complete(Unit)
            runCurrent()
            assertNull(controller.launch.value, "Returning to the parent must not later open the old video")
        }

    @Test
    fun breadcrumbNavigationMustCancelPendingPlayback() =
        runTest(StandardTestDispatcher()) {
            val (controller, client) = fixture()
            controller.navigateToDepth(0)
            client.subtitleGate.complete(Unit)
            runCurrent()
            assertNull(controller.launch.value, "Breadcrumb navigation must cancel the abandoned playback request")
        }

    @Test
    fun openingAnotherFolderMustCancelPendingPlayback() =
        runTest(StandardTestDispatcher()) {
            val (controller, client) = fixture()
            controller.openFolder("next")
            client.subtitleGate.complete(Unit)
            runCurrent()
            assertNull(controller.launch.value, "Entering another folder must not launch the abandoned video")
        }

    @Test
    fun closingBrowserAlreadyCancelsPendingPlayback() =
        runTest(StandardTestDispatcher()) {
            val (controller, client) = fixture()
            controller.closeBrowser()
            client.subtitleGate.complete(Unit)
            runCurrent()
            assertNull(controller.browser.value)
            assertNull(controller.launch.value)
        }

    @Test
    fun repeatingPlayMustKeepTheNewLoadingIndicator() =
        runTest(StandardTestDispatcher()) {
            val (controller, client) = fixture()
            controller.play(client.video)
            runCurrent()
            assertNull(controller.launch.value)
            assertEquals(
                "movie.mkv",
                controller.browser.value?.preparing,
                "The cancelled request must not clear the new request's indicator",
            )
        }

    @Test
    fun stayingOnTheSamePageStillLaunchesPlayback() =
        runTest(StandardTestDispatcher()) {
            val (controller, client) = fixture()
            client.subtitleGate.complete(Unit)
            runCurrent()
            assertNotNull(controller.launch.value)
        }

    @Test
    fun navigationDiscardsAnUnconsumedReadyLaunch() =
        runTest(StandardTestDispatcher()) {
            val (controller, client) = fixture()
            client.subtitleGate.complete(Unit)
            runCurrent()
            assertNotNull(controller.launch.value)
            controller.navigateUp()
            assertNull(controller.launch.value)
        }

    @Test
    fun reopeningTheSourceDiscardsThePreviousLaunch() =
        runTest(StandardTestDispatcher()) {
            val (controller, client) = fixture()
            client.subtitleGate.complete(Unit)
            runCurrent()
            assertNotNull(controller.launch.value)
            controller.open(controller.browser.value!!.source)
            assertNull(controller.launch.value)
            runCurrent()
            assertNull(controller.launch.value)
        }
}
