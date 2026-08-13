package com.yfuse.update

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateDownloadBoundaryTest {
    @Test
    fun manifest_reader_rejects_after_only_the_limit_plus_one_bytes() {
        val exact = ByteArrayInputStream("12345678".encodeToByteArray())
        assertEquals("12345678", exact.readUpdateManifestText(maxBytes = 8))

        val oversized = ByteArrayInputStream(ByteArray(100) { 'x'.code.toByte() })
        assertFailsWith<IllegalStateException> {
            oversized.readUpdateManifestText(maxBytes = 8)
        }
        assertEquals(91, oversized.available())
    }

    @Test
    fun stale_update_files_are_cleaned_without_touching_the_current_or_unrelated_files() {
        val directory = Files.createTempDirectory("yfuse-updates-").toFile()
        val current =
            File(directory, updatePackageFileName(versionCode = 72)).apply {
                writeText("current")
            }
        // The partial file of the version being fetched is the resume checkpoint, so the
        // sweep is asked to keep it.
        val currentPartial =
            File(directory, "${current.name}.part").apply {
                writeText("partial")
            }
        val staleApk = File(directory, "Yfuse-71.apk").apply { writeText("stale") }
        val legacyApk = File(directory, "Yfuse-0.2.18.apk").apply { writeText("legacy") }
        val stalePartial =
            File(directory, "Yfuse-0.2.17.apk.part").apply {
                writeText("stale partial")
            }
        val unrelated = File(directory, "release-notes.txt").apply { writeText("keep") }
        val malformed = File(directory, "Yfuse-.apk").apply { writeText("keep") }
        val nestedDirectory = File(directory, "nested").apply { mkdir() }
        val nestedApk = File(nestedDirectory, "Yfuse-70.apk").apply { writeText("keep") }

        try {
            assertEquals(
                3,
                cleanupStaleUpdateFiles(
                    directory,
                    keepFileNames = setOf(current.name, currentPartial.name),
                ),
            )
            assertTrue(current.exists())
            assertTrue(currentPartial.exists())
            assertFalse(staleApk.exists())
            assertFalse(legacyApk.exists())
            assertFalse(stalePartial.exists())
            assertTrue(unrelated.exists())
            assertTrue(malformed.exists())
            assertTrue(nestedApk.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun declared_content_length_must_match_the_manifest_when_present() {
        validateUpdateContentLength(contentLength = -1L, expectedBytes = 3L)
        validateUpdateContentLength(contentLength = 3L, expectedBytes = 3L)

        assertFailsWith<IllegalArgumentException> {
            validateUpdateContentLength(contentLength = 2L, expectedBytes = 3L)
        }
        assertFailsWith<IllegalArgumentException> {
            validateUpdateContentLength(contentLength = 4L, expectedBytes = 3L)
        }
    }

    @Test
    fun oversized_stream_is_rejected_before_the_offending_chunk_is_written() {
        val input =
            object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) {
                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = super.read(buffer, offset, minOf(length, 3))
            }
        val output = ByteArrayOutputStream()

        assertFailsWith<IllegalStateException> {
            copyUpdatePackage(input, output, expectedBytes = 3L)
        }

        assertContentEquals(byteArrayOf(1, 2, 3), output.toByteArray())
    }

    @Test
    fun a_truncated_download_is_rejected_without_unowned_cleanup() {
        val partial = File.createTempFile("yfuse-update-", ".part")
        val manifest = testManifest(size = 3L, sha256 = SHA256_ABC)

        try {
            assertEquals(
                2L,
                appendUpdatePackage(
                    input = ByteArrayInputStream("ab".encodeToByteArray()),
                    partialFile = partial,
                    startBytes = 0L,
                    expectedBytes = 3L,
                ),
            )
            assertTrue(partial.exists())
            assertEquals("ab", partial.readText())

            assertFalse(cachedUpdatePackageMatches(partial, manifest))
            assertTrue(partial.exists())
        } finally {
            partial.delete()
        }
    }

    @Test
    fun digest_failure_is_read_only_until_the_owner_is_rechecked() {
        val partial = File.createTempFile("yfuse-update-", ".part")
        val manifest = testManifest(size = 3L, sha256 = "0".repeat(64))

        try {
            partial.writeText("abc")
            assertFalse(cachedUpdatePackageMatches(partial, manifest))
            assertTrue(partial.exists())
        } finally {
            partial.delete()
        }
    }

    @Test
    fun exact_completed_download_matches_the_production_verifier() {
        val partial = File.createTempFile("yfuse-update-", ".part")
        val manifest = testManifest(size = 3L, sha256 = SHA256_ABC)

        try {
            assertEquals(
                3L,
                appendUpdatePackage(
                    input = ByteArrayInputStream("abc".encodeToByteArray()),
                    partialFile = partial,
                    startBytes = 0L,
                    expectedBytes = 3L,
                ),
            )
            assertTrue(cachedUpdatePackageMatches(partial, manifest))
            assertTrue(partial.exists())
            assertEquals(3L, partial.length())
        } finally {
            partial.delete()
        }
    }

    @Test
    fun equal_length_cached_apk_is_rejected_when_the_same_version_is_republished() {
        val cached = File.createTempFile("yfuse-update-", ".apk")
        val original =
            UpdateManifest(
                versionCode = 80,
                versionName = "0.2.80",
                apkUrl = "https://47.112.219.60/yfuse/Yfuse-80.apk",
                sha256 = SHA256_ABC,
                size = 3L,
            )

        try {
            cached.writeText("abc")
            assertTrue(cachedUpdatePackageMatches(cached, original))

            val republished = original.copy(sha256 = "0".repeat(64))
            assertFalse(cachedUpdatePackageMatches(cached, republished))
            // Checks and restore verification are read-only. Cleanup happens only after an
            // owner token is checked again, so a stale task cannot erase a newer generation.
            assertTrue(cached.exists())
        } finally {
            cached.delete()
        }
    }

    @Test
    fun stale_restore_does_not_delete_a_new_generation_partial_with_the_same_version_code() {
        val partial =
            File.createTempFile("yfuse-update-", ".part").apply {
                writeText("new generation bytes")
            }
        val oldManifest =
            UpdateManifest(
                versionCode = 80,
                versionName = "0.2.80",
                apkUrl = "https://47.112.219.60/yfuse/Yfuse-80.apk",
                sha256 = SHA256_ABC,
                size = 3L,
            )
        val republished =
            oldManifest.copy(
                apkUrl = "https://47.112.219.60/yfuse/Yfuse-80-republished.apk",
                sha256 = "0".repeat(64),
            )

        try {
            assertEquals(
                OwnedUpdateCacheDeleteResult.StaleOwner,
                deleteUpdateCacheFileIfOwned(
                    file = partial,
                    expectedGeneration = 7,
                    currentGeneration = 8,
                    expectedManifest = oldManifest,
                    currentRecord = UpdateDownloadRecord(republished),
                ),
            )
            assertTrue(partial.exists())
            assertEquals(
                OwnedUpdateCacheDeleteResult.StaleOwner,
                deleteUpdateCacheFileIfOwned(
                    file = partial,
                    expectedGeneration = 8,
                    currentGeneration = 8,
                    expectedManifest = oldManifest,
                    currentRecord = UpdateDownloadRecord(republished),
                ),
            )
            assertTrue(partial.exists())
            assertEquals("new generation bytes", partial.readText())
        } finally {
            partial.delete()
        }
    }

    @Test
    fun stale_download_owner_cannot_promote_a_verified_partial() {
        val manifest =
            UpdateManifest(
                versionCode = 80,
                versionName = "0.2.80",
                apkUrl = "https://47.112.219.60/yfuse/Yfuse-80.apk",
                sha256 = SHA256_ABC,
                size = 3L,
            )
        val record = UpdateDownloadRecord(manifest)

        assertTrue(updateDownloadOwnerStillCurrent(7, 7, false, manifest, record))
        assertFalse(updateDownloadOwnerStillCurrent(7, 8, false, manifest, record))
        assertFalse(updateDownloadOwnerStillCurrent(7, 7, true, manifest, record))
        assertFalse(
            updateDownloadOwnerStillCurrent(
                expectedGeneration = 7,
                currentGeneration = 7,
                pauseRequested = false,
                expectedManifest = manifest,
                currentRecord = UpdateDownloadRecord(manifest.copy(sha256 = "0".repeat(64))),
            ),
        )
    }

    @Test
    fun storage_preflight_preserves_the_reserve_without_overflow() {
        assertTrue(
            hasSufficientUpdateStorage(
                usableSpace = 13L,
                requiredBytes = 3L,
                reserveBytes = 10L,
            ),
        )
        assertFalse(
            hasSufficientUpdateStorage(
                usableSpace = 12L,
                requiredBytes = 3L,
                reserveBytes = 10L,
            ),
        )
        assertFalse(
            hasSufficientUpdateStorage(
                usableSpace = Long.MAX_VALUE,
                requiredBytes = Long.MAX_VALUE,
                reserveBytes = 1L,
            ),
        )
        assertEquals(
            Long.MAX_VALUE,
            missingUpdateStorageBytes(
                usableSpace = 0L,
                requiredBytes = Long.MAX_VALUE,
                reserveBytes = 1L,
            ),
        )
    }

    private fun testManifest(
        size: Long,
        sha256: String,
    ) = UpdateManifest(
        versionCode = 80,
        versionName = "0.2.80",
        apkUrl = "https://47.112.219.60/yfuse/Yfuse-80.apk",
        sha256 = sha256,
        size = size,
    )

    private companion object {
        const val SHA256_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
