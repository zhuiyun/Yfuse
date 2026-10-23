package com.yfuse.core.widget

import kotlinx.coroutines.CancellationException
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
        sources: suspend () -> List<BundledWidgetSource> = { seeds },
    ) = WidgetRepository(disk, runtime, scope, { "new-page" }, { "hash-${it.hashCode()}" }, { 0L }, sources)

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
            restarted.restoreBundledWidgets(setOf("one"))
            assertEquals(listOf(edited), restarted.database.value.widgets)
        }

    @Test fun bundled_installation_and_explicit_recovery_continue_beyond_previous_widget_limit() =
        runTest {
            val disk = Disk()
            val repo = repository(disk, backgroundScope, emptyList())
            repeat(24) { repo.install(repo.inspect(seed("personal-$it").source, "file:$it.js")) }
            val full = repository(disk, backgroundScope)
            full.awaitReady()
            assertEquals(26, full.database.value.widgets.size)
            assertEquals(setOf("one", "two"), full.database.value.bundledDigests.keys)
            assertNull(full.bundleError.value)
            full.uninstall("one")
            full.removePage("page-one")
            assertEquals(BundledWidgetRestoreResult(1, 1), full.restoreBundledWidgets(setOf("one")))
            val reopened = repository(disk, backgroundScope)
            reopened.awaitReady()
            assertEquals(26, reopened.database.value.widgets.size)
            assertEquals(full.database.value, reopened.database.value)
            assertTrue(
                (0..23).all { id ->
                    reopened.database.value.widgets
                        .any { it.metadata.id == "personal-$id" }
                },
            )
            assertEquals(setOf("one", "two"), reopened.database.value.bundledDigests.keys)
        }

    @Test fun bundled_pages_are_added_beyond_previous_page_limit_without_removing_personal_pages() =
        runTest {
            val pages = (1..32).map { WidgetPage("personal-$it", "Page $it") }
            val disk = Disk(widgetJson.encodeToString(WidgetDatabase(pages = pages)))
            val repo = repository(disk, backgroundScope)
            repo.awaitReady()
            assertEquals(pages + listOf(seed().page, seed("two").page), repo.database.value.pages)
            assertEquals(2, repo.database.value.widgets.size)
            repo.removePage("page-one")
            assertEquals(BundledWidgetRestoreResult(0, 1), repo.restoreBundledWidgets(setOf("one")))
            assertEquals(34, repo.database.value.pages.size)
            assertEquals(
                pages,
                repo.database.value.pages
                    .filter { it.id.startsWith("personal-") },
            )
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

    @Test fun invalid_bundle_does_not_block_the_other_preset_or_personal_edits() =
        runTest {
            val disk = Disk()
            val repo = repository(disk, backgroundScope, listOf(seed(), seed("two").copy(source = "invalid")))
            repo.awaitReady()
            assertEquals(
                listOf("one"),
                repo.database.value.widgets
                    .map { it.metadata.id },
            )
            assertEquals(listOf(seed().page), repo.database.value.pages)
            assertEquals(setOf("one"), repo.database.value.bundledDigests.keys)
            assertEquals(1, disk.writes)
            assertNotNull(repo.bundleError.value)
            repo.savePage(WidgetPage("personal", "Personal"))
            assertEquals(2, repo.database.value.pages.size)
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

    @Test fun resource_read_failure_is_isolated_and_cancellation_is_not_swallowed() =
        runTest {
            val sources =
                bundledWidgetSources { filename ->
                    if (filename == "movie-recommendations.js") error("missing resource") else "available source"
                }
            assertEquals(listOf(BUNDLED_TMDB_ID, BUNDLED_RECOMMENDATIONS_ID), sources.map { it.id })
            assertEquals("available source", sources.first().source)
            assertTrue(sources.last().source.isEmpty())
            assertFailsWith<CancellationException> {
                bundledWidgetSources { throw CancellationException("cancelled") }
            }
        }

    @Test fun failed_metadata_can_be_retried_without_restarting_or_replacing_the_other_preset() =
        runTest {
            var available = listOf(seed(), seed("two").copy(source = "invalid"))
            val disk = Disk()
            val repo = repository(disk, backgroundScope, sources = { available })
            repo.awaitReady()
            val personal = seed().page.copy(title = "Edited", sections = emptyList())
            repo.savePage(personal)
            repo.enable("one", false)
            available = listOf(seed(version = 2), seed("two"))
            val result = repo.restoreBundledWidgets(setOf("two"))
            assertEquals(BundledWidgetRestoreResult(1, 1), result)
            assertEquals(
                "1",
                repo.database.value.widgets
                    .first()
                    .metadata.version,
            )
            assertFalse(
                repo.database.value.widgets
                    .first()
                    .enabled,
            )
            assertEquals(
                personal,
                repo.database.value.pages
                    .first(),
            )
            assertNull(repo.bundleError.value)
        }

    @Test fun previously_deferred_pages_are_added_after_upgrade_without_requiring_deletion() =
        runTest {
            val pages = (1..32).map { WidgetPage("personal-$it", "Page $it") }
            val inspector = repository(Disk(), backgroundScope, emptyList())
            val widgets = listOf(seed(), seed("two")).map { inspector.inspect(it.source, it.origin) }
            val previous =
                WidgetDatabase(
                    widgets = widgets,
                    pages = pages,
                    bundledDigests = widgets.associate { it.metadata.id to it.digest },
                    bundledStateVersion = 1,
                )
            val disk = Disk(widgetJson.encodeToString(previous))
            val repo = repository(disk, backgroundScope)
            repo.awaitReady()
            assertEquals(1, repo.database.value.bundledStateVersion)
            assertTrue(
                repo.database.value.removedBundledPages
                    .isEmpty(),
            )
            assertEquals(pages + listOf(seed().page, seed("two").page), repo.database.value.pages)
            val reopened = repository(disk, backgroundScope)
            reopened.awaitReady()
            assertEquals(repo.database.value, reopened.database.value)
            assertEquals(2, reopened.database.value.widgets.size)
        }

    @Test fun legacy_ambiguous_missing_entries_stay_deleted_until_explicitly_selected_for_recovery() =
        runTest {
            val legacy = WidgetDatabase(bundledDigests = mapOf("one" to "old-one", "two" to "old-two"))
            val disk = Disk(widgetJson.encodeToString(legacy))
            val repo = repository(disk, backgroundScope)
            repo.awaitReady()
            assertTrue(
                repo.database.value.widgets
                    .isEmpty(),
            )
            assertTrue(
                repo.database.value.pages
                    .isEmpty(),
            )
            assertEquals(setOf("one", "two"), repo.database.value.removedBundledWidgets)
            assertEquals(setOf("page-one", "page-two"), repo.database.value.removedBundledPages)
            assertEquals(BundledWidgetRestoreResult(1, 1), repo.restoreBundledWidgets(setOf("one")))
            val restarted = repository(disk, backgroundScope)
            restarted.awaitReady()
            assertEquals(
                listOf("one"),
                restarted.database.value.widgets
                    .map { it.metadata.id },
            )
            assertEquals(listOf(seed().page), restarted.database.value.pages)
            assertEquals(setOf("two"), restarted.database.value.removedBundledWidgets)
            assertEquals(setOf("page-two"), restarted.database.value.removedBundledPages)
        }

    @Test fun explicit_recovery_respects_edited_imports_hidden_pages_disabled_state_and_default_home() =
        runTest {
            val disk = Disk()
            val repo = repository(disk, backgroundScope)
            repo.awaitReady()
            val imported = repo.inspect(seed(version = 99).source, "file:personal.js").copy(enabled = false)
            repo.install(imported)
            repo.enable("one", false)
            val hidden = seed().page.copy(title = "Personal title", hidden = true, sections = emptyList())
            repo.savePage(hidden)
            val personal = WidgetPage("personal", "Home")
            repo.savePage(personal)
            repo.setDefault(personal.id)
            val before = repo.database.value
            assertEquals(BundledWidgetRestoreResult(0, 0), repo.restoreBundledWidgets(setOf("one")))
            assertEquals(before, repo.database.value)
            assertEquals(
                imported,
                repo.database.value.widgets
                    .first { it.metadata.id == "one" },
            )
        }

    @Test fun compatible_same_id_import_gets_a_missing_default_page_only_on_explicit_recovery() =
        runTest {
            val disk = Disk()
            val old = repository(disk, backgroundScope, emptyList())
            old.install(old.inspect(seed(version = 99).source, "file:personal.js"))
            val imported =
                old.database.value.widgets
                    .single()
            val repo = repository(disk, backgroundScope, listOf(seed()))
            repo.awaitReady()
            assertTrue(
                repo.database.value.pages
                    .isEmpty(),
            )
            assertEquals(BundledWidgetRestoreResult(0, 1), repo.restoreBundledWidgets(setOf("one")))
            assertEquals(listOf(imported), repo.database.value.widgets)
            assertEquals(listOf(seed().page), repo.database.value.pages)
        }

    @Test fun incompatible_same_id_import_is_not_overwritten_or_given_invalid_default_sections() =
        runTest {
            val disk = Disk()
            val old = repository(disk, backgroundScope, emptyList())
            val custom = seed().source.replace("\"id\":\"list\"", "\"id\":\"custom\"")
            old.install(old.inspect(custom, "file:personal.js"))
            val imported =
                old.database.value.widgets
                    .single()
            val repo = repository(disk, backgroundScope, listOf(seed()))
            repo.awaitReady()
            val result = repo.restoreBundledWidgets(setOf("one"))
            assertEquals(listOf("one"), result.incompleteNames)
            assertEquals(listOf(imported), repo.database.value.widgets)
            assertTrue(
                repo.database.value.pages
                    .isEmpty(),
            )
        }

    @Test fun deleted_component_and_page_stay_deleted_on_startup_but_selected_recovery_restores_them() =
        runTest {
            val disk = Disk()
            val repo = repository(disk, backgroundScope)
            repo.awaitReady()
            repo.uninstall("one")
            repo.removePage("page-one")
            val restarted = repository(disk, backgroundScope)
            restarted.awaitReady()
            assertEquals(
                listOf("two"),
                restarted.database.value.widgets
                    .map { it.metadata.id },
            )
            assertEquals(listOf(seed("two").page), restarted.database.value.pages)
            assertEquals(BundledWidgetRestoreResult(1, 1), restarted.restoreBundledWidgets(setOf("one")))
            assertTrue(
                restarted.database.value.removedBundledWidgets
                    .isEmpty(),
            )
            assertTrue(
                restarted.database.value.removedBundledPages
                    .isEmpty(),
            )
        }

    @Test fun restoring_an_older_backup_reconciles_new_presets_without_a_process_restart() =
        runTest {
            val disk = Disk()
            val repo = repository(disk, backgroundScope)
            repo.awaitReady()
            val personal = WidgetPage("personal", "Personal")
            repo.restore(
                widgetJson.encodeToString(WidgetDatabase(pages = listOf(personal), defaultPageId = personal.id)),
            )
            assertEquals(
                listOf("one", "two"),
                repo.database.value.widgets
                    .map { it.metadata.id },
            )
            assertEquals(listOf(personal, seed().page, seed("two").page), repo.database.value.pages)
            assertEquals(personal.id, repo.database.value.defaultPageId)
        }

    @Test fun failed_explicit_recovery_write_does_not_clear_deletion_intent_or_publish_new_content() =
        runTest {
            val disk = Disk()
            val repo = repository(disk, backgroundScope)
            repo.awaitReady()
            repo.uninstall("one")
            repo.removePage("page-one")
            val before = repo.database.value
            val saved = disk.raw
            disk.fail = true
            assertFailsWith<IllegalStateException> { repo.restoreBundledWidgets(setOf("one")) }
            assertEquals(before, repo.database.value)
            assertEquals(saved, disk.raw)
        }

    @Test fun an_incomplete_new_snapshot_is_repaired_when_no_explicit_deletion_was_recorded() =
        runTest {
            val incomplete =
                WidgetDatabase(
                    bundledDigests = mapOf("one" to "previous-digest"),
                    bundledStateVersion = 1,
                    pages = listOf(seed().page),
                )
            val disk = Disk(widgetJson.encodeToString(incomplete))
            val repo = repository(disk, backgroundScope, listOf(seed(version = 2)))
            repo.awaitReady()
            assertEquals(
                listOf("one"),
                repo.database.value.widgets
                    .map { it.metadata.id },
            )
            assertEquals(
                "2",
                repo.database.value.widgets
                    .single()
                    .metadata.version,
            )
            assertEquals(listOf(seed().page), repo.database.value.pages)
            assertTrue(
                repo.database.value.removedBundledWidgets
                    .isEmpty(),
            )
            assertNull(repo.bundleError.value)
        }
}
