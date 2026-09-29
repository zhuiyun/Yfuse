package com.yfuse.feature.filesource

import com.russhwolf.settings.MapSettings
import com.yfuse.core.filesource.FileSource
import com.yfuse.core.filesource.FileSourceAddress
import com.yfuse.core.filesource.FileSourceClient
import com.yfuse.core.filesource.FileSourceCredentials
import com.yfuse.core.filesource.FileSourceEntry
import com.yfuse.core.filesource.FileSourceException
import com.yfuse.core.filesource.FileSourceFailure
import com.yfuse.core.filesource.FileSourceKind
import com.yfuse.core.filesource.FileSourceProgressStore
import com.yfuse.core.filesource.FileSourceRegistry
import com.yfuse.core.filesource.fileSourceItemId
import com.yfuse.core.filesource.resolveAddress
import com.yfuse.core.security.TestSecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSourcesControllerTest {
    private val settings = MapSettings()
    private val secureStore = TestSecureStore()
    private val registry =
        FileSourceRegistry(settings, secureStore, ioDispatcher = Dispatchers.Unconfined) { "fs" + "1".repeat(24) }
    private val progress = FileSourceProgressStore(settings)
    private val client = FakeClient()

    private fun TestScope.controller() = FileSourcesController(registry, client, progress, backgroundScope)

    @Test
    fun an_invalid_address_is_explained_without_connecting() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            controller.openAdd()

            controller.submit()

            assertEquals("请输入地址", controller.form.value?.error)
            assertTrue(client.listed.isEmpty())
        }

    @Test
    fun adding_tests_the_login_saves_it_securely_and_opens_the_share() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            controller.openAdd()
            controller.editDraft {
                it.copy(
                    kind = FileSourceKind.Alist,
                    https = false,
                    host = "192.168.1.2",
                    username = "alice",
                    password = "pw",
                )
            }

            controller.submit()

            assertNull(controller.form.value)
            val saved = registry.sources.value.single()
            assertEquals("http://192.168.1.2:5244", saved.origin)
            assertEquals(listOf("dav"), saved.rootSegments)
            assertEquals("pw", registry.credentials(saved.id)?.password)
            assertEquals(listOf("alice:pw@root"), client.listed.take(1))
            val browser = assertIs<FileBrowserState>(controller.browser.value)
            assertIs<FolderListing.Loaded>(browser.listing)
            assertEquals("已添加「Alist · 192.168.1.2」", controller.notice.value)
        }

    @Test
    fun a_refused_login_keeps_the_form_open_and_saves_nothing() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            client.failure = FileSourceFailure.Unauthorized
            controller.openAdd()
            controller.editDraft { it.copy(host = "nas.local", username = "alice", password = "wrong") }

            controller.submit()

            assertEquals("用户名或密码错误", controller.form.value?.error)
            assertEquals(false, controller.form.value?.submitting)
            assertTrue(registry.sources.value.isEmpty())
            assertTrue(secureStore.storedKeys().isEmpty())
        }

    @Test
    fun renaming_keeps_the_password_and_does_not_reconnect() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            val source = savedSource()
            registry.save(source, password = "pw")
            controller.openEdit(source)
            controller.editDraft { it.copy(name = "客厅 NAS") }

            controller.submit()

            assertEquals("客厅 NAS", registry.source(source.id)?.name)
            assertEquals("pw", registry.credentials(source.id)?.password)
            assertTrue(client.listed.isEmpty())
        }

    @Test
    fun folders_push_and_pop_and_the_root_closes_the_browser() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            val source = savedSource()
            registry.save(source, password = "pw")

            controller.open(source)
            controller.openFolder("剧集")
            assertEquals(listOf("剧集"), controller.browser.value?.path)
            assertIs<FolderListing.Loaded>(controller.browser.value?.listing)

            controller.navigateUp()
            assertEquals(emptyList(), controller.browser.value?.path)
            controller.navigateUp()
            assertNull(controller.browser.value)
        }

    @Test
    fun playing_hands_a_resumed_queue_to_the_player_or_starts_over_on_request() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            val source = savedSource()
            registry.save(source, password = "pw")
            val itemId = fileSourceItemId(source.id, listOf("E01.mkv"))
            progress.record(itemId, positionMs = 900_000L, durationMs = 2_400_000L, persistNow = true)
            controller.open(source)
            val video = client.root.first { it.name == "E01.mkv" }

            controller.play(video)
            val launch = controller.launch.value
            assertEquals(900_000L, launch?.startPositionMs)
            assertEquals(listOf("E01", "E02"), launch?.items?.map { it.title })
            assertNull(controller.browser.value?.preparing)

            controller.consumeLaunch()
            controller.play(video, fromStart = true)
            assertEquals(0L, controller.launch.value?.startPositionMs)
        }

    @Test
    fun removing_a_source_forgets_its_progress_and_closes_it() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = controller()
            val source = savedSource()
            registry.save(source, password = "pw")
            val itemId = fileSourceItemId(source.id, listOf("E01.mkv"))
            progress.record(itemId, positionMs = 900_000L, durationMs = 2_400_000L, persistNow = true)
            controller.open(source)

            controller.remove(source)

            assertTrue(registry.sources.value.isEmpty())
            assertNull(progress.get(itemId))
            assertNull(controller.browser.value)
        }

    @Test
    fun a_saved_source_splits_back_into_its_form_fields() {
        val draft =
            FileSource("fs1", FileSourceKind.WebDav, "n", "https://[fe80::1]:5006", listOf("dav", "影视"), "bob")
                .toDraft()

        assertEquals("[fe80::1]", draft.host)
        assertEquals("5006", draft.port)
        assertEquals("dav/影视", draft.path)
        assertTrue(draft.https)
        assertEquals("", draft.password)
    }

    @Test
    fun an_alist_on_the_plain_http_port_keeps_it_when_edited() {
        val source =
            FileSource("fs1", FileSourceKind.Alist, "盘", "http://nas.local", listOf("dav", "阿里云盘"), "bob")

        val draft = source.toDraft()

        assertEquals("80", draft.port)
        val resolved = assertIs<FileSourceAddress.Valid>(draft.resolveAddress())
        assertEquals("http://nas.local", resolved.origin)
        assertEquals(listOf("dav", "阿里云盘"), resolved.rootSegments)
        assertEquals("", FileSource("fs1", FileSourceKind.WebDav, "n", "http://nas.local").toDraft().port)
    }

    private fun savedSource() =
        FileSource(
            id = "fs" + "1".repeat(24),
            kind = FileSourceKind.WebDav,
            name = "NAS",
            origin = "https://nas.local:5006",
            rootSegments = listOf("dav"),
            username = "alice",
        )

    private class FakeClient : FileSourceClient {
        val listed = mutableListOf<String>()
        var failure: FileSourceFailure? = null
        val root =
            listOf(
                FileSourceEntry("剧集", directory = true),
                FileSourceEntry("E01.mkv", directory = false),
                FileSourceEntry("E02.mkv", directory = false),
            )

        override suspend fun list(
            source: FileSource,
            credentials: FileSourceCredentials,
            path: List<String>,
        ): List<FileSourceEntry> {
            listed += "${credentials.username}:${credentials.password}@${path.joinToString("/").ifEmpty { "root" }}"
            failure?.let { throw FileSourceException(it) }
            return if (path.isEmpty()) root else listOf(FileSourceEntry("S01E01.mkv", directory = false))
        }

        override suspend fun cacheSubtitle(
            source: FileSource,
            credentials: FileSourceCredentials,
            path: List<String>,
            entry: FileSourceEntry,
        ): String = "cache://${entry.name}"
    }
}
