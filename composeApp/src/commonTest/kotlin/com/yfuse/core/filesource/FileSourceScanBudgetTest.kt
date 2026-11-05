package com.yfuse.core.filesource

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSourceScanBudgetTest {
    private val source = FileSource("fs" + "a".repeat(24), FileSourceKind.WebDav, "NAS", "http://nas.local")
    private val login = FileSourceCredentials("", "")
    private val matcher =
        TmdbTitleMatcher(search = {
                _,
                _,
                _,
            ->
            Result.success(emptyList())
        }, alternativeTitles = { _, _ -> Result.success(emptyList()) })

    @Test fun checkpoint_resumes_pending_folders_and_changed_source_restarts_at_root() =
        runTest {
            val paths = mutableListOf<List<String>>()
            val client =
                reader { path ->
                    paths += path
                    if (path.isEmpty()) listOf(FileSourceEntry("child", true)) else emptyList()
                }
            val scanner = FileSourceScanner(client, matcher)
            var saved: FileSourceScanCheckpoint? = null
            assertFailsWith<CancellationException> {
                scanner.scan(source, login, null, onCheckpoint = {
                    saved = it
                    throw CancellationException("stop after durable boundary")
                })
            }
            assertEquals(listOf(FileSourceScanFolder(listOf("child"), 1)), saved!!.pending)
            paths.clear()
            val previous = FileSourceLibrary(0, checkpoint = saved)
            scanner.scan(source, login, previous)
            assertEquals(listOf(listOf("child")), paths)
            paths.clear()
            scanner.scan(source.copy(revision = 1), login, previous)
            assertEquals(emptyList(), paths.first())
        }

    @Test fun folder_frontier_is_bounded_before_tasks_are_created() =
        runTest {
            var reads = 0
            val client =
                reader { path ->
                    reads++
                    if (path.isEmpty()) List(2000) { FileSourceEntry("folder$it", true) } else emptyList()
                }
            var largestPending = 0
            val library =
                FileSourceScanner(client, matcher).scan(source, login, null, onCheckpoint = {
                    largestPending =
                        maxOf(largestPending, it.pending.size)
                })
            assertEquals(600, reads)
            assertTrue(largestPending <= 599)
            assertTrue(library.partial)
        }

    @Test fun bounded_producer_root_failure_propagates_without_publishing_checkpoint() =
        runTest {
            var checkpointed = false
            val client =
                object : FileSourceClient by reader({ emptyList() }) {
                    override suspend fun listBounded(
                        source: FileSource,
                        credentials: FileSourceCredentials,
                        path: List<String>,
                        maxEntries: Int,
                    ): FileSourceListing = throw FileSourceException(FileSourceFailure.Unauthorized)
                }
            val failure =
                assertFailsWith<FileSourceException> {
                    FileSourceScanner(client, matcher).scan(source, login, null, onCheckpoint = { checkpointed = true })
                }
            assertEquals(FileSourceFailure.Unauthorized, failure.failure)
            assertFalse(checkpointed)
        }

    @Test fun incompatible_source_does_not_merge_previous_files_into_partial_results() =
        runTest {
            val previous =
                FileSourceLibrary(
                    0,
                    files = listOf(FileSourceLibraryFile(listOf("old.mkv"), "movie:1")),
                    sourceVersion = source.scanVersion(),
                )
            val client =
                object : FileSourceClient by reader({ emptyList() }) {
                    override suspend fun listBounded(
                        source: FileSource,
                        credentials: FileSourceCredentials,
                        path: List<String>,
                        maxEntries: Int,
                    ) = FileSourceListing(emptyList(), truncated = true)
                }
            val result = FileSourceScanner(client, matcher).scan(source.copy(revision = 1), login, previous)
            assertTrue(result.partial)
            assertTrue(result.files.isEmpty())
        }

    private fun reader(block: (List<String>) -> List<FileSourceEntry>) =
        object : FileSourceClient {
            override suspend fun list(
                source: FileSource,
                credentials: FileSourceCredentials,
                path: List<String>,
            ) = block(path)

            override suspend fun cacheSubtitle(
                source: FileSource,
                credentials: FileSourceCredentials,
                path: List<String>,
                entry: FileSourceEntry,
            ): String = error("unused")
        }
}
