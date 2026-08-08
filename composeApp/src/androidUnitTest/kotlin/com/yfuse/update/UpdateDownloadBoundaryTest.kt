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
        val current = File(directory, updatePackageFileName(versionCode = 72)).apply {
            writeText("current")
        }
        // The partial file of the version being fetched is the resume checkpoint, so the
        // sweep is asked to keep it.
        val currentPartial = File(directory, "${current.name}.part").apply {
            writeText("partial")
        }
        val staleApk = File(directory, "Yfuse-71.apk").apply { writeText("stale") }
        val legacyApk = File(directory, "Yfuse-0.2.18.apk").apply { writeText("legacy") }
        val stalePartial = File(directory, "Yfuse-0.2.17.apk.part").apply {
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
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, 3))
        }
        val output = ByteArrayOutputStream()

        assertFailsWith<IllegalStateException> {
            copyUpdatePackage(input, output, expectedBytes = 3L)
        }

        assertContentEquals(byteArrayOf(1, 2, 3), output.toByteArray())
    }

    @Test
    fun a_truncated_download_keeps_the_partial_file_for_the_next_attempt() {
        val partial = File.createTempFile("yfuse-update-", ".part")

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

            assertFailsWith<IllegalStateException> {
                verifyUpdatePackage(partial, expectedBytes = 3L, expectedSha256 = SHA256_ABC)
            }
            assertFalse(partial.exists())
        } finally {
            partial.delete()
        }
    }

    @Test
    fun digest_failure_removes_the_completed_file() {
        val partial = File.createTempFile("yfuse-update-", ".part")

        try {
            partial.writeText("abc")
            assertFailsWith<IllegalStateException> {
                verifyUpdatePackage(partial, expectedBytes = 3L, expectedSha256 = "0".repeat(64))
            }
            assertFalse(partial.exists())
        } finally {
            partial.delete()
        }
    }

    @Test
    fun exact_verified_download_keeps_the_file() {
        val partial = File.createTempFile("yfuse-update-", ".part")

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
            assertEquals(
                partial,
                verifyUpdatePackage(partial, expectedBytes = 3L, expectedSha256 = SHA256_ABC),
            )
            assertTrue(partial.exists())
            assertEquals(3L, partial.length())
        } finally {
            partial.delete()
        }
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

    private companion object {
        const val SHA256_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
