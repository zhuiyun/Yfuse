package com.yfuse.update

import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 断点续传: what may be appended to a partial file, and what has to start over. */
class UpdateResumePolicyTest {
    @Test
    fun only_the_exact_remaining_window_continues_a_partial_file() {
        assertTrue(updateContentRangeContinues("bytes 400-999/1000", 400L, 1000L))
        assertTrue(updateContentRangeContinues("bytes 400-999/*", 400L, 1000L))

        // Wrong offset, short window, mismatched total, or a whole-object response.
        assertFalse(updateContentRangeContinues("bytes 399-999/1000", 400L, 1000L))
        assertFalse(updateContentRangeContinues("bytes 400-998/1000", 400L, 1000L))
        assertFalse(updateContentRangeContinues("bytes 400-999/1001", 400L, 1000L))
        assertFalse(updateContentRangeContinues(null, 400L, 1000L))
        assertFalse(updateContentRangeContinues("bytes 0-999/1000", 0L, 1000L))
    }

    @Test
    fun appending_requires_a_partial_response_that_proves_the_package_is_unchanged() {
        fun canAppend(
            statusCode: Int = HttpURLConnection.HTTP_PARTIAL,
            contentRange: String? = "bytes 400-999/1000",
            expectedValidator: String? = "etag:\"v80\"",
            responseValidator: String? = "etag:\"v80\"",
            existingBytes: Long = 400L,
        ) = canAppendUpdateRange(
            existingBytes = existingBytes,
            expectedTotalBytes = 1000L,
            statusCode = statusCode,
            contentRange = contentRange,
            expectedValidator = expectedValidator,
            responseValidator = responseValidator,
        )

        assertTrue(canAppend())
        // A 200 means the server ignored the range: the body is the whole package.
        assertFalse(canAppend(statusCode = HttpURLConnection.HTTP_OK))
        // The package changed under us.
        assertFalse(canAppend(responseValidator = "etag:\"v81\""))
        // A server that offers no validator can still be resumed from — the manifest digest
        // is what actually decides whether the assembled package is usable.
        assertTrue(canAppend(expectedValidator = null, responseValidator = null))
        assertTrue(canAppend(expectedValidator = null))
        // Nothing on disk to append to.
        assertFalse(canAppend(existingBytes = 0L, contentRange = "bytes 0-999/1000"))
    }

    @Test
    fun a_resumed_download_appends_to_the_bytes_already_on_disk() {
        val partial = File.createTempFile("yfuse-update-", ".part")

        try {
            partial.writeText("abc")
            assertEquals(
                6L,
                appendUpdatePackage(
                    input = ByteArrayInputStream("def".encodeToByteArray()),
                    partialFile = partial,
                    startBytes = 3L,
                    expectedBytes = 6L,
                ),
            )
            assertEquals("abcdef", partial.readText())
        } finally {
            partial.delete()
        }
    }

    @Test
    fun a_partial_file_that_no_longer_matches_the_resume_offset_is_refused() {
        val partial = File.createTempFile("yfuse-update-", ".part")

        try {
            partial.writeText("ab")
            assertFailsWith<IllegalStateException> {
                appendUpdatePackage(
                    input = ByteArrayInputStream("def".encodeToByteArray()),
                    partialFile = partial,
                    startBytes = 3L,
                    expectedBytes = 6L,
                )
            }
            assertEquals("ab", partial.readText())
        } finally {
            partial.delete()
        }
    }

    @Test
    fun a_paused_transfer_stops_at_a_chunk_boundary_and_keeps_what_it_wrote() {
        val partial = File.createTempFile("yfuse-update-", ".part")
        val body = ByteArray(200 * 1024) { 'x'.code.toByte() }

        try {
            var paused = false
            val copied =
                appendUpdatePackage(
                    input = ByteArrayInputStream(body),
                    partialFile = partial,
                    startBytes = 0L,
                    expectedBytes = body.size.toLong(),
                    shouldContinue = { !paused },
                ) { paused = true }

            assertEquals(64L * 1024L, copied)
            assertEquals(copied, partial.length())

            // The next attempt starts exactly where the paused one stopped.
            assertEquals(
                body.size.toLong(),
                appendUpdatePackage(
                    input = ByteArrayInputStream(body, copied.toInt(), body.size - copied.toInt()),
                    partialFile = partial,
                    startBytes = copied,
                    expectedBytes = body.size.toLong(),
                ),
            )
            assertEquals(body.size.toLong(), partial.length())
        } finally {
            partial.delete()
        }
    }

    @Test
    fun a_resume_offset_outside_the_package_is_rejected() {
        val partial = File.createTempFile("yfuse-update-", ".part")

        try {
            partial.writeText("abc")
            assertFailsWith<IllegalArgumentException> {
                appendUpdatePackage(
                    input = ByteArrayInputStream(ByteArray(0)),
                    partialFile = partial,
                    startBytes = 3L,
                    expectedBytes = 3L,
                )
            }
        } finally {
            partial.delete()
        }
    }

    @Test
    fun a_range_response_is_measured_against_the_remaining_bytes() {
        validateUpdateContentLength(contentLength = 600L, expectedBytes = 600L)
        assertFailsWith<IllegalArgumentException> {
            validateUpdateContentLength(contentLength = 1000L, expectedBytes = 600L)
        }
    }

    @Test
    fun progress_is_reported_against_the_whole_package() {
        assertEquals(0f, updateProgress(downloadedBytes = 0L, totalBytes = 1000L))
        assertEquals(0.4f, updateProgress(downloadedBytes = 400L, totalBytes = 1000L))
        assertEquals(1f, updateProgress(downloadedBytes = 1000L, totalBytes = 1000L))
        // A missing total must not divide by zero, and stale bytes must not overshoot.
        assertEquals(0f, updateProgress(downloadedBytes = 400L, totalBytes = 0L))
        assertEquals(1f, updateProgress(downloadedBytes = 1400L, totalBytes = 1000L))
    }
}
