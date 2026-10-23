package com.yfuse.core.widget

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BundledWidgetRepositoryTest {
    private class Disk(
        var raw: String? = null,
    ) : WidgetPersistence {
        var writes = 0
        var fail = false

        override suspend fun read() = raw

        override suspend fun write(value: String) {
            check(!fail) { "disk full" }
            writes++
            raw = value
        }
    }

    private val runtime =
        object : WidgetRuntime {
            override suspend fun metadata(source: String) = source

            override suspend fun invoke(
                widget: InstalledWidget,
                function: String,
                argument: JsonElement,
                timeoutSeconds: Int,
            ) = "[]"
        }

    private fun seed(
        id: String = "one",
        version: Int = 1,
    ) = BundledWidgetSource(
        id,
        """{"id":"$id","title":"$id","version":"$version","modules":[{"id":"list","title":"List","functionName":"list"}]}""",
        WidgetPage("page-$id", id, listOf(WidgetSection("section-$id", id, "list", "List", style = "poster"))),
    )

    private fun repository(
        disk: Disk,
        scope: CoroutineScope,
        seeds: List<BundledWidgetSource> = listOf(seed(), seed("two")),
    ) = WidgetRepository(disk, runtime, scope, { "new-page" }, { "hash-${it.hashCode()}" }, { 0L }, { seeds })

    @Test fun first_launch_installs_both_bundles_atomically_and_restart_does_not_write() =
        runTest {
            val disk = Disk()
            val repo = repository(disk, backgroundScope)
            repo.awaitReady()
            assertEquals(
                listOf("one", "two"),
                repo.database.value.widgets
                    .map { it.metadata.id },
            )
            assertEquals(listOf(seed().page, seed("two").page), repo.database.value.pages)
            assertNull(repo.database.value.defaultPageId)
            assertEquals(2, repo.database.value.bundledDigests.size)
            assertEquals(1, disk.writes)
            val restarted = repository(disk, backgroundScope)
            restarted.awaitReady()
            assertEquals(repo.database.value, restarted.database.value)
            assertEquals(1, disk.writes)
            assertEquals(repo.backup(), disk.raw)
        }

    @Test fun upgrade_preserves_existing_pages_and_default_home() =
        runTest {
            val page = WidgetPage("personal", "我的首页")
            val original = WidgetDatabase(pages = listOf(page), defaultPageId = page.id)
            val disk = Disk(widgetJson.encodeToString(original))
            val repo = repository(disk, backgroundScope)
            repo.awaitReady()
            assertEquals(
                page,
                repo.database.value.pages
                    .first(),
            )
            assertEquals(page.id, repo.database.value.defaultPageId)
            assertEquals(3, repo.database.value.pages.size)
        }

    @Test fun bundle_update_preserves_disabled_state_and_edited_or_deleted_pages() =
        runTest {
            val disk = Disk()
            val repo = repository(disk, backgroundScope)
            repo.awaitReady()
            repo.enable("one", false)
            val edited = seed().page.copy(title = "我的片单", sections = emptyList())
            repo.savePage(edited)
            repo.setDefault(edited.id)
            repo.removePage(seed("two").page.id)
            val restarted = repository(disk, backgroundScope, listOf(seed(version = 2), seed("two", 2)))
            restarted.awaitReady()
            assertEquals(listOf(edited), restarted.database.value.pages)
            assertEquals(edited.id, restarted.database.value.defaultPageId)
            assertFalse(
                restarted.database.value.widgets
                    .first()
                    .enabled,
            )
            assertTrue(
                restarted.database.value.widgets
                    .all { it.metadata.version == "2" },
            )
        }

    @Test fun uninstall_survives_restart_update_and_backup_restore() =
        runTest {
            val disk = Disk()
            val repo = repository(disk, backgroundScope)
            repo.awaitReady()
            repo.uninstall("one")
            val backup = repo.backup()
            val restoredDisk = Disk()
            val restored = repository(restoredDisk, backgroundScope)
            restored.restore(backup)
            val restarted = repository(restoredDisk, backgroundScope, listOf(seed(version = 2), seed("two", 2)))
            restarted.awaitReady()
            assertEquals(
                listOf("two"),
                restarted.database.value.widgets
                    .map { it.metadata.id },
            )
            assertEquals(
                seed().page,
                restarted.database.value.pages
                    .first(),
            )
            assertTrue("one" in restarted.database.value.bundledDigests)
        }

    @Test fun existing_same_id_import_is_preserved_and_does_not_create_a_page() =
        runTest {
            val disk = Disk()
            val repo = repository(disk, backgroundScope, emptyList())
            repo.install(repo.inspect(seed(version = 99).source, "file:my-widget.js"))
            val imported =
                repo.database.value.widgets
                    .single()
            val restarted = repository(disk, backgroundScope, listOf(seed()))
            restarted.awaitReady()
            assertEquals(listOf(imported), restarted.database.value.widgets)
            assertTrue(
                restarted.database.value.pages
                    .isEmpty(),
            )
            val upgraded = repository(disk, backgroundScope, listOf(seed(version = 2)))
            upgraded.awaitReady()
            assertEquals(listOf(imported), upgraded.database.value.widgets)
        }

    @Test fun edited_bundle_source_is_not_replaced_even_if_origin_was_preserved() =
        runTest {
            val disk = Disk()
            val repo = repository(disk, backgroundScope, listOf(seed()))
            repo.awaitReady()
            val edited = repo.inspect(seed(version = 99).source, seed().origin)
            repo.install(edited)
            val restarted = repository(disk, backgroundScope, listOf(seed(version = 2)))
            restarted.awaitReady()
            assertEquals(listOf(edited), restarted.database.value.widgets)
        }

    @Test fun full_widget_capacity_defers_install_until_space_is_available() =
        runTest {
            val disk = Disk()
            val repo = repository(disk, backgroundScope, emptyList())
            repeat(24) { repo.install(repo.inspect(seed("personal-$it").source, "file:$it.js")) }
            val full = repository(disk, backgroundScope)
            full.awaitReady()
            assertEquals(24, full.database.value.widgets.size)
            assertTrue(
                full.database.value.bundledDigests
                    .isEmpty(),
            )
            assertNull(full.bundleError.value)
            full.uninstall("personal-0")
            full.uninstall("personal-1")
            val reopened = repository(disk, backgroundScope)
            reopened.awaitReady()
            assertEquals(24, reopened.database.value.widgets.size)
            assertEquals(setOf("one", "two"), reopened.database.value.bundledDigests.keys)
        }

    @Test fun full_page_capacity_keeps_pages_and_still_installs_components() =
        runTest {
            val pages = (1..32).map { WidgetPage("personal-$it", "Page $it") }
            val disk = Disk(widgetJson.encodeToString(WidgetDatabase(pages = pages)))
            val repo = repository(disk, backgroundScope)
            repo.awaitReady()
            assertEquals(pages, repo.database.value.pages)
            assertEquals(2, repo.database.value.widgets.size)
        }

    @Test fun failed_bundle_write_keeps_existing_data_editable_and_retries_next_launch() =
        runTest {
            val original = WidgetDatabase(pages = listOf(WidgetPage("old", "Old")))
            val disk = Disk(widgetJson.encodeToString(original)).also { it.fail = true }
            val before = disk.raw
            val repo = repository(disk, backgroundScope)
            repo.awaitReady()
            assertEquals(before, disk.raw)
            assertEquals(original, repo.database.value)
            assertNull(repo.error.value)
            assertNotNull(repo.bundleError.value)
            assertEquals(before, repo.backup())
            disk.fail = false
            repo.savePage(WidgetPage("new", "New"))
            val restarted = repository(disk, backgroundScope)
            restarted.awaitReady()
            assertEquals(4, restarted.database.value.pages.size)
            assertNull(restarted.bundleError.value)
        }

    @Test fun invalid_bundle_never_commits_a_partial_install() =
        runTest {
            val disk = Disk()
            val repo = repository(disk, backgroundScope, listOf(seed(), seed("two").copy(source = "invalid")))
            repo.awaitReady()
            assertNull(disk.raw)
            assertEquals(WidgetDatabase(), repo.database.value)
            assertNotNull(repo.bundleError.value)
            repo.savePage(WidgetPage("personal", "Personal"))
            assertEquals(1, repo.database.value.pages.size)
        }

    @Test fun corrupt_existing_snapshot_is_preserved_and_not_replaced_with_bundles() =
        runTest {
            val disk = Disk("corrupt-backup")
            val repo = repository(disk, backgroundScope)
            repo.awaitReady()
            assertEquals("corrupt-backup", disk.raw)
            assertNotNull(repo.error.value)
            assertEquals(0, disk.writes)
            assertFailsWith<IllegalStateException> { repo.savePage(WidgetPage("new", "New")) }
        }

    @Test fun conflicting_page_id_is_preserved() =
        runTest {
            val page = seed().page.copy(title = "Existing page", sections = emptyList())
            val disk = Disk(widgetJson.encodeToString(WidgetDatabase(pages = listOf(page))))
            val repo = repository(disk, backgroundScope, listOf(seed()))
            repo.awaitReady()
            assertEquals(listOf(page), repo.database.value.pages)
            assertEquals(1, repo.database.value.widgets.size)
        }
}
