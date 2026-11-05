package com.yfuse.feature.filesource

import com.russhwolf.settings.MapSettings
import com.yfuse.core.filesource.FileSource
import com.yfuse.core.filesource.FileSourceClient
import com.yfuse.core.filesource.FileSourceCredentials
import com.yfuse.core.filesource.FileSourceEntry
import com.yfuse.core.filesource.FileSourceKind
import com.yfuse.core.filesource.FileSourceLibraryStorage
import com.yfuse.core.filesource.FileSourceLibraryStore
import com.yfuse.core.filesource.FileSourceRegistry
import com.yfuse.core.filesource.FileSourceScanner
import com.yfuse.core.filesource.TmdbTitleMatcher
import com.yfuse.core.security.TestSecureStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSourceScanOwnershipTest {
    private val source = FileSource("fs" + "1".repeat(24), FileSourceKind.WebDav, "NAS", "https://nas.local")
    private val registry = FileSourceRegistry(MapSettings(), TestSecureStore(), Dispatchers.Unconfined) { source.id }
    private val storage =
        object : FileSourceLibraryStorage {
            var saved: String? = null

            override fun read() = saved

            override fun write(text: String) {
                saved = text
            }
        }
    private val store = FileSourceLibraryStore(storage, Dispatchers.Unconfined)
    private val replies = List(2) { CompletableDeferred<Unit>() }
    private var calls = 0
    private val client =
        object : FileSourceClient {
            override suspend fun list(
                source: FileSource,
                credentials: FileSourceCredentials,
                path: List<String>,
            ): List<FileSourceEntry> {
                val reply = replies[calls++]
                withContext(NonCancellable) { reply.await() }
                return emptyList()
            }

            override suspend fun cacheSubtitle(
                source: FileSource,
                credentials: FileSourceCredentials,
                path: List<String>,
                entry: FileSourceEntry,
            ): String = error("unused")
        }
    private val scanner =
        FileSourceScanner(
            client,
            TmdbTitleMatcher({ _, _, _ -> Result.success(emptyList()) }, { _, _ -> Result.success(emptyList()) }),
            parseDispatcher = Dispatchers.Unconfined,
        )

    @Test fun cancellation_of_an_old_scan_cannot_remove_a_new_scans_progress() =
        runTest {
            registry.save(source, "pw")
            val scans = FileSourceLibraryScans(registry, store, scanner, backgroundScope) {}
            scans.scan(source)
            runCurrent()
            scans.cancel(source.id)
            scans.scan(source)
            runCurrent()
            replies[0].complete(Unit)
            runCurrent()
            assertTrue(source.id in scans.progress.value)
            assertTrue(store.libraries.value.isEmpty())
            replies[1].complete(Unit)
            runCurrent()
            assertFalse(source.id in scans.progress.value)
            assertTrue(source.id in store.libraries.value)
        }

    @Test fun changing_credentials_invalidates_the_inflight_scan_result() =
        runTest {
            val authenticated = source.copy(username = "alice")
            registry.save(authenticated, "old-password")
            val scans = FileSourceLibraryScans(registry, store, scanner, backgroundScope) {}
            scans.scan(authenticated)
            runCurrent()
            registry.save(authenticated, "new-password")
            replies[0].complete(Unit)
            runCurrent()
            assertTrue(store.libraries.value.isEmpty())
            assertTrue(scans.progress.value.isEmpty())
        }

    @Test fun editing_the_source_invalidates_the_inflight_scan_result() =
        runTest {
            registry.save(source, "pw")
            val scans = FileSourceLibraryScans(registry, store, scanner, backgroundScope) {}
            scans.scan(source)
            runCurrent()
            registry.save(source.copy(origin = "https://other.local"), "pw")
            replies[0].complete(Unit)
            runCurrent()
            assertTrue(store.libraries.value.isEmpty())
            assertTrue(scans.progress.value.isEmpty())
        }
}
