package com.yfuse.core.widget

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetRepositoryTest {
    private class MemoryPersistence(
        var raw: String? = null,
    ) : WidgetPersistence {
        var fail = false

        override suspend fun read() = raw

        override suspend fun write(value: String) {
            check(!fail) { "disk full" }
            raw = value
        }
    }

    private class Runtime : WidgetRuntime {
        var calls = 0
        var received: JsonElement? = null

        override suspend fun metadata(source: String) = source

        override suspend fun invoke(
            widget: InstalledWidget,
            function: String,
            argument: JsonElement,
            timeoutSeconds: Int,
        ): String {
            calls++
            received = argument
            return """[{"id":"one","title":"Movie"}]"""
        }
    }

    private val metadata =
        """
        {"id":"demo","title":"Demo","version":"1.0","modules":[
          {"id":"list","title":"List","functionName":"load","params":[{"name":"page","type":"page"}]}
        ]}
        """.trimIndent()

    @Test fun installed_components_pages_and_sections_round_trip_beyond_previous_count_limits() =
        runTest {
            val disk = MemoryPersistence()
            val repo = WidgetRepository(disk, Runtime(), backgroundScope, { "id" }, { "hash:$it" }, { 0L })
            repeat(48) { index ->
                val source = metadata.replace("\"id\":\"demo\"", "\"id\":\"widget-$index\"")
                repo.install(repo.inspect(source, "file:widget-$index.js"))
            }
            val sections =
                (0 until 80).map { index ->
                    WidgetSection("section-$index", "widget-${index % 48}", "list", "Section $index")
                }
            repeat(48) { index ->
                repo.savePage(WidgetPage("page-$index", "Page $index", if (index == 0) sections else emptyList()))
            }
            repo.setDefault("page-0")
            repo.enable("widget-47", false)
            val expected = repo.database.value
            assertEquals(48, expected.widgets.size)
            assertEquals(48, expected.pages.size)
            assertEquals(sections, expected.pages.first().sections)

            val restoredDisk = MemoryPersistence()
            val restored = WidgetRepository(restoredDisk, Runtime(), backgroundScope, { "id" }, { "hash:$it" }, { 0L })
            restored.restore(repo.backup())
            assertEquals(expected, restored.database.value)
            val reopened = WidgetRepository(restoredDisk, Runtime(), backgroundScope, { "id" }, { "hash:$it" }, { 0L })
            reopened.awaitReady()
            assertNull(reopened.error.value)
            assertEquals(expected, reopened.database.value)
        }

    @Test fun restored_detail_waits_for_installed_components_to_finish_loading() =
        runTest {
            val snapshot = CompletableDeferred<String?>()
            val disk =
                object : WidgetPersistence {
                    override suspend fun read() = snapshot.await()

                    override suspend fun write(value: String) = Unit
                }
            val runtime = Runtime()
            val repo = WidgetRepository(disk, runtime, backgroundScope, { "id" }, { "hash:$it" }, { 0L })
            val widget = repo.inspect(metadata, "file:demo.js")
            val result =
                async { repo.detail("demo", WidgetMedia("film", "Film", link = "https://source.example/film")) }
            runCurrent()
            assertFalse(result.isCompleted)
            assertEquals(0, runtime.calls)
            snapshot.complete(
                widgetJson.encodeToString(WidgetDatabase.serializer(), WidgetDatabase(widgets = listOf(widget))),
            )
            runCurrent()
            assertEquals(
                "one",
                result
                    .await()
                    .children
                    .single()
                    .id,
            )
            assertEquals(1, runtime.calls)
        }

    @Test fun saved_sections_survive_more_than_48_feeds_and_temporary_browse_entries() =
        runTest {
            val runtime = Runtime()
            val repo = WidgetRepository(MemoryPersistence(), runtime, backgroundScope, { "id" }, { "hash:$it" }, { 0L })
            val widget = repo.inspect(metadata, "file:demo.js")
            repo.install(widget)
            val sections =
                (0 until 80).map { index ->
                    WidgetSection(
                        "section-$index",
                        "demo",
                        "list",
                        "Section $index",
                        params =
                            JsonObject(
                                mapOf(
                                    "topic" to JsonPrimitive(index),
                                ),
                            ),
                    )
                }
            val page = WidgetPage("many", "Many", sections)
            repo.savePage(page)
            val feeds = sections.map { repo.feed(it).also { feed -> feed.ensureLoaded() } }
            runCurrent()
            repeat(60) { repo.feed(sections.first().copy(id = "temporary-$it")) }
            sections.forEachIndexed { index, section ->
                assertSame(feeds[index], repo.feed(section))
                assertEquals(
                    "one",
                    feeds[index]
                        .state.value.items
                        .single()
                        .id,
                )
                feeds[index].ensureLoaded()
            }
            runCurrent()
            assertEquals(80, runtime.calls)

            val changed = sections.first().copy(params = JsonObject(mapOf("topic" to JsonPrimitive("new"))))
            repo.savePage(page.copy(sections = listOf(changed) + sections.drop(1)))
            assertTrue(
                feeds
                    .first()
                    .state.value.items
                    .isEmpty(),
            )
            assertNotSame(feeds.first(), repo.feed(changed))
            assertSame(feeds.last(), repo.feed(sections.last()))
            repo.removePage(page.id)
            assertTrue(
                feeds
                    .last()
                    .state.value.items
                    .isEmpty(),
            )
        }

    @Test fun presentation_edits_keep_cached_content_and_restore_revalidates_without_blanking_it() =
        runTest {
            val disk = MemoryPersistence()
            val runtime = Runtime()
            val repo = WidgetRepository(disk, runtime, backgroundScope, { "id" }, { "hash:$it" }, { 0L })
            val widget = repo.inspect(metadata, "file:demo.js")
            repo.install(widget)
            val section = repo.newSection(widget, widget.metadata.modules.single())
            val page = repo.newPage("Page").copy(sections = listOf(section))
            repo.savePage(page)
            val feed = repo.feed(section)
            feed.ensureLoaded()
            runCurrent()
            val edited = section.copy(title = "Edited", style = "poster", limit = 8)
            repo.savePage(page.copy(title = "Renamed", sections = listOf(edited)))
            repo.setDefault(page.id)
            assertSame(feed, repo.feed(edited))
            assertEquals(1, feed.state.value.items.size)
            repo.load(edited)
            assertEquals(1, runtime.calls)

            disk.fail = true
            assertFailsWith<IllegalStateException> { repo.enable(widget.metadata.id, false) }
            assertSame(feed, repo.feed(edited))
            assertEquals(1, feed.state.value.items.size)
            disk.fail = false
            val snapshot = repo.backup()
            repo.restore(snapshot)
            val restored = repo.feed(edited)
            assertNotSame(feed, restored)
            assertEquals(1, feed.state.value.items.size)
            assertEquals(feed.state.value.items, restored.state.value.items)
            assertFalse(restored.state.value.loaded)
            restored.ensureLoaded()
            runCurrent()
            assertEquals(2, runtime.calls)
            repo.enable(widget.metadata.id, false)
            assertTrue(
                restored.state.value.items
                    .isEmpty(),
            )
            val disabled = repo.feed(edited)
            disabled.ensureLoaded()
            runCurrent()
            assertTrue(
                disabled.state.value.error
                    .orEmpty()
                    .contains("已停用"),
            )
            assertEquals(2, runtime.calls)
        }

    @Test fun changing_one_widget_does_not_evict_another_widgets_loaded_feed_or_cache() =
        runTest {
            val runtime = Runtime()
            val repo = WidgetRepository(MemoryPersistence(), runtime, backgroundScope, { "id" }, { "hash:$it" }, { 0L })
            val first = repo.inspect(metadata, "file:first.js")
            val second = repo.inspect(metadata.replace("demo", "other"), "file:second.js")
            repo.install(first)
            repo.install(second)
            val section = repo.newSection(first, first.metadata.modules.single())
            val feed = repo.feed(section)
            feed.ensureLoaded()
            runCurrent()
            repo.enable(second.metadata.id, false)
            assertSame(feed, repo.feed(section))
            repo.load(section)
            assertEquals(1, runtime.calls)
            repo.install(repo.inspect(metadata.replace("1.0", "1.1"), "file:first.js"))
            val updated = repo.feed(section)
            assertNotSame(feed, updated)
            updated.ensureLoaded()
            runCurrent()
            assertEquals(2, runtime.calls)
        }

    @Test fun a_script_update_hands_off_old_content_and_only_replaces_it_after_a_successful_load() =
        runTest {
            val response = CompletableDeferred<String>()
            var calls = 0
            val runtime =
                object : WidgetRuntime {
                    override suspend fun metadata(source: String) = source

                    override suspend fun invoke(
                        widget: InstalledWidget,
                        function: String,
                        argument: JsonElement,
                        timeoutSeconds: Int,
                    ): String {
                        calls++
                        return when (calls) {
                            1 -> """[{"id":"old","title":"Previous film"}]"""
                            2 -> response.await()
                            else -> """[{"id":"new","title":"New film"}]"""
                        }
                    }
                }
            val repo = WidgetRepository(MemoryPersistence(), runtime, backgroundScope, { "id" }, { "hash:$it" }, { 0L })
            val widget = repo.inspect(metadata, "file:demo.js")
            repo.install(widget)
            val section = repo.newSection(widget, widget.metadata.modules.single())
            repo.savePage(repo.newPage("Page").copy(sections = listOf(section)))
            val original = repo.feed(section)
            original.ensureLoaded()
            runCurrent()
            repo.install(repo.inspect(metadata.replace("1.0", "1.1"), "file:demo.js"))
            val updated = repo.feed(section)
            assertNotSame(original, updated)
            assertEquals(
                "old",
                original.state.value.items
                    .single()
                    .id,
            )
            assertEquals(
                "old",
                updated.state.value.items
                    .single()
                    .id,
            )
            updated.ensureLoaded()
            runCurrent()
            assertTrue(updated.state.value.busy)
            assertEquals(
                "old",
                updated.state.value.items
                    .single()
                    .id,
            )
            response.completeExceptionally(IllegalStateException("offline"))
            runCurrent()
            assertEquals("offline", updated.state.value.error)
            assertEquals(
                "old",
                updated.state.value.items
                    .single()
                    .id,
            )
            updated.retry()
            runCurrent()
            assertEquals(
                "new",
                updated.state.value.items
                    .single()
                    .id,
            )
            assertEquals(3, calls)
        }

    @Test fun updating_a_script_preserves_page_configuration_and_disabled_state() =
        runTest {
            val disk = MemoryPersistence()
            val runtime = Runtime()
            var next = 0
            val repo = WidgetRepository(disk, runtime, backgroundScope, { "id-${next++}" }, { "hash:$it" }, { 0L })
            val installed = repo.inspect(metadata, "file:demo.js")
            repo.install(installed)
            val page =
                repo
                    .newPage(
                        "我的电影",
                    ).copy(
                        sections =
                            listOf(
                                repo.newSection(installed, installed.metadata.modules.single()).copy(style = "cinema"),
                            ),
                    )
            repo.savePage(page)
            repo.setDefault(page.id)
            repo.enable("demo", false)
            repo.install(repo.inspect(metadata.replace("1.0", "1.1"), "file:demo.js"))
            assertEquals(
                page,
                repo.database.value.pages
                    .single(),
            )
            assertFalse(
                repo.database.value.widgets
                    .single()
                    .enabled,
            )
            assertEquals(page.id, repo.database.value.defaultPageId)
            val restored = WidgetRepository(disk, runtime, backgroundScope, { "new" }, { "hash:$it" }, { 0L })
            restored.awaitReady()
            assertEquals(repo.database.value, restored.database.value)
        }

    @Test fun failed_write_does_not_change_live_state_or_previous_snapshot() =
        runTest {
            val disk = MemoryPersistence()
            val repo = WidgetRepository(disk, Runtime(), backgroundScope, { "page" }, { it }, { 0L })
            repo.savePage(repo.newPage("First"))
            val before = disk.raw
            disk.fail = true
            assertFailsWith<IllegalStateException> { repo.removePage("page") }
            assertEquals(
                "First",
                repo.database.value.pages
                    .single()
                    .title,
            )
            assertEquals(before, disk.raw)
        }

    @Test fun corrupt_storage_is_preserved_and_not_silently_overwritten() =
        runTest {
            val disk = MemoryPersistence("not json")
            val repo = WidgetRepository(disk, Runtime(), backgroundScope, { "id" }, { it }, { 0L })
            repo.awaitReady()
            assertNotNull(repo.error.value)
            assertFailsWith<IllegalStateException> { repo.savePage(repo.newPage("Page")) }
            assertEquals("not json", disk.raw)
        }

    @Test fun disabling_uninstalling_and_deleting_a_page_have_explicit_behaviour() =
        runTest {
            val disk = MemoryPersistence()
            val repo = WidgetRepository(disk, Runtime(), backgroundScope, { "id" }, { it }, { 0L })
            val widget = repo.inspect(metadata, "file:x.js")
            repo.install(widget)
            val section = repo.newSection(widget, widget.metadata.modules.single())
            repo.savePage(repo.newPage("Page").copy(sections = listOf(section)))
            repo.setDefault("id")
            repo.enable(widget.metadata.id, false)
            assertFailsWith<IllegalStateException> { repo.load(section) }
            repo.uninstall(widget.metadata.id)
            assertEquals(
                section,
                repo.database.value.pages
                    .single()
                    .sections
                    .single(),
            )
            assertFailsWith<IllegalStateException> { repo.load(section) }
            repo.removePage("id")
            assertNull(repo.database.value.defaultPageId)
        }

    @Test fun cache_is_scoped_to_version_parameters_and_page_and_refresh_bypasses_it() =
        runTest {
            val runtime = Runtime()
            val repo = WidgetRepository(MemoryPersistence(), runtime, backgroundScope, { "id" }, { it }, { 0L })
            val widget = repo.inspect(metadata, "file:x.js")
            repo.install(widget)
            val section = repo.newSection(widget, widget.metadata.modules.single())
            repo.load(section)
            repo.load(section)
            assertEquals(1, runtime.calls)
            repo.load(section, page = 2)
            assertEquals(JsonPrimitive(2), runtime.received!!.jsonObject["page"])
            repo.load(section, refresh = true)
            assertEquals(3, runtime.calls)
            repo.install(repo.inspect(metadata.replace("1.0", "1.1"), "file:x.js"))
            repo.load(section)
            assertEquals(4, runtime.calls)
        }

    @Test fun backup_is_validated_before_replacing_current_pages() =
        runTest {
            val disk = MemoryPersistence()
            val repo = WidgetRepository(disk, Runtime(), backgroundScope, { "id" }, { "hash:$it" }, { 0L })
            repo.install(repo.inspect(metadata, "file:x.js"))
            repo.savePage(repo.newPage("Saved"))
            val backup = repo.backup()
            assertFailsWith<IllegalArgumentException> { repo.restore(backup.replace("hash:", "tampered:")) }
            assertEquals(
                "Saved",
                repo.database.value.pages
                    .single()
                    .title,
            )
            repo.restore(backup)
            assertEquals(backup, repo.backup())
        }

    @Test fun restoring_a_compact_backup_cannot_write_a_snapshot_that_the_next_start_cannot_read() =
        runTest {
            val disk = MemoryPersistence()
            val repo = WidgetRepository(disk, Runtime(), backgroundScope, { "saved" }, { it }, { 0L })
            repo.savePage(repo.newPage("Saved"))
            val before = repo.backup()
            val prefix =
                """{"pages":[{"id":"p","title":"Imported","sections":[""" +
                    """{"id":"s","widgetId":"w","moduleId":"m","title":"Section","params":{"text":""""
            val suffix = """"}}]}]}"""
            val raw = prefix + "x".repeat(MAX_WIDGET_DATABASE_BYTES - prefix.length - suffix.length - 1) + suffix
            assertTrue(raw.encodeToByteArray().size < MAX_WIDGET_DATABASE_BYTES)

            val failure = assertFailsWith<IllegalArgumentException> { repo.restore(raw) }
            assertTrue(failure.message.orEmpty().contains("存储上限"))
            assertEquals(before, disk.raw)
            assertEquals(before, repo.backup())
            assertNull(repo.error.value)

            val restarted = WidgetRepository(disk, Runtime(), backgroundScope, { "new" }, { it }, { 0L })
            restarted.awaitReady()
            assertEquals(
                "Saved",
                restarted.database.value.pages
                    .single()
                    .title,
            )
            assertNull(restarted.error.value)
            repo.restore("""{"pages":[{"id":"p","title":"Valid"}]}""")
            assertEquals(
                "Valid",
                repo.database.value.pages
                    .single()
                    .title,
            )
        }

    @Test fun utf8_bom_is_normalized_before_hashing_and_backup_round_trip() =
        runTest {
            val repo =
                WidgetRepository(MemoryPersistence(), Runtime(), backgroundScope, { "id" }, { "hash:$it" }, { 0L })
            val widget = repo.inspect("\uFEFF$metadata", "file:bom.js")
            assertFalse(widget.source.startsWith("\uFEFF"))
            repo.install(widget)
            val backup = repo.backup()
            repo.restore(backup)
            assertEquals(backup, repo.backup())
        }
}
