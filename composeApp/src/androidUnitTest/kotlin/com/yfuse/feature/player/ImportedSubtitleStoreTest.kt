package com.yfuse.feature.player

import com.yfuse.core2.subtitle.YSubtitleFormat
import kotlinx.coroutines.CancellationException
import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportedSubtitleStoreTest {
    private val owner = SubtitleItemKey("server-a", "movie", "version-1")
    private val text = "1\n00:00:01,000 --> 00:00:02,000\n中文字幕\n"

    @Test fun a_validated_copy_survives_reopening_and_is_scoped_to_server_and_version() =
        withStore { root, store ->
            val subtitle = store.save(owner, text, YSubtitleFormat.Srt)
            assertEquals(text, File(URI(subtitle.uri)).readText())
            assertEquals(listOf(subtitle), ImportedSubtitleStore(root).load(owner))
            assertTrue(store.load(owner.copy(serverId = "server-b")).isEmpty())
            assertTrue(store.load(owner.copy(versionId = "version-2")).isEmpty())
            assertFalse(subtitle.uri.contains("server-a"))
        }

    @Test fun duplicate_imports_do_not_use_extra_slots_and_clear_only_affects_this_version() =
        withStore { _, store ->
            val first = store.save(owner, text, YSubtitleFormat.Srt)
            assertEquals(first, store.save(owner, text, YSubtitleFormat.Srt))
            val other = owner.copy(versionId = "other")
            val untouched = store.save(other, text, YSubtitleFormat.Srt)
            store.clear(owner)
            assertTrue(store.load(owner).isEmpty())
            assertFalse(File(URI(first.uri)).exists())
            assertEquals(listOf(untouched), store.load(other))
        }

    @Test fun capacity_failure_preserves_existing_subtitles_and_leaves_no_partial_file() =
        withStore { root, _ ->
            val store = ImportedSubtitleStore(root, maximumBytes = text.encodeToByteArray().size.toLong())
            val existing = store.save(owner, text, YSubtitleFormat.Srt)
            assertFailsWith<IllegalStateException> { store.save(owner, text + "more", YSubtitleFormat.Srt) }
            assertEquals(listOf(existing), store.load(owner))
            assertEquals(1, root.walkTopDown().count(File::isFile))
        }

    @Test fun null_and_empty_identity_fields_do_not_share_a_directory() =
        withStore { _, store ->
            store.save(owner.copy(serverId = null), text, YSubtitleFormat.Srt)
            assertTrue(store.load(owner.copy(serverId = "")).isEmpty())
        }

    @Test fun cancelled_import_cannot_publish_a_copy_after_clear() =
        withStore { root, store ->
            var checks = 0
            assertFailsWith<CancellationException> {
                store.save(owner, text, YSubtitleFormat.Srt) {
                    if (++checks == 2) throw CancellationException("Import cancelled before publication")
                }
            }
            assertTrue(store.load(owner).isEmpty())
            assertEquals(0, root.walkTopDown().count(File::isFile))
        }

    @Test fun formats_keep_usable_file_extensions_and_per_media_limit_is_enforced() =
        withStore { _, store ->
            assertTrue(
                store.save(owner, "WEBVTT\n\n00:01.000 --> 00:02.000\nHi", YSubtitleFormat.WebVtt).uri.endsWith(".vtt"),
            )
            repeat(7) { store.save(owner, text + it, YSubtitleFormat.Srt) }
            assertFailsWith<IllegalStateException> { store.save(owner, text + "ninth", YSubtitleFormat.Srt) }
            assertEquals(8, store.load(owner).size)
        }

    private fun withStore(block: (File, ImportedSubtitleStore) -> Unit) {
        val root = Files.createTempDirectory("imported-subtitles-test").toFile()
        try {
            block(root, ImportedSubtitleStore(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
