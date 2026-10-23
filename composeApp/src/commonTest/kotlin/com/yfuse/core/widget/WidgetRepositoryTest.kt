package com.yfuse.core.widget

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
