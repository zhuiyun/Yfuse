package com.yfuse.core.offline

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineTransferResponseTest {
    @Test
    fun aShortRangeCannotPublishATruncatedFileAsCompleted() {
        val total = offlineTransferTotalBytes(true, "bytes 100-199/400", 100)
        assertEquals(400L, total)
        assertFailsWith<IOException> { requireCompleteOfflineTransfer(200, total) }
        requireCompleteOfflineTransfer(400, total)
    }

    @Test
    fun chunkedResumeStillUsesTheCompleteRepresentationLength() {
        assertTrue(canAppend("bytes 100-399/400", -1))
        val total = offlineTransferTotalBytes(true, "bytes 100-399/400", -1)
        assertEquals(400L, total)
        assertFailsWith<IOException> { requireCompleteOfflineTransfer(250, total) }
    }

    @Test
    fun invalidOrOverflowingRangesAreRejected() {
        listOf(
            "bytes 100-99/400",
            "bytes 100-400/400",
            "bytes 100-199/0",
            "bytes 100-199/999999999999999999999",
            "bytes 0-9223372036854775807/*",
            "bytes -1-199/400",
            "bytes 100-199/400, 200-399/400",
        ).forEach { assertNull(parseOfflineContentRange(it), it) }
    }

    @Test
    fun unknownRepresentationLengthRestartsInsteadOfGuessingCompleteness() {
        assertFalse(canAppend("bytes 100-199/*", 100))
        assertFailsWith<IOException> { offlineTransferTotalBytes(true, "bytes 100-199/*", 100) }
    }

    @Test
    fun contentLengthMustMatchTheRangeLengthWhenPresent() {
        assertTrue(canAppend("bytes 100-199/400", 100))
        assertFalse(canAppend("bytes 100-199/400", 99))
        assertFalse(canAppend("bytes 100-199/400", 101))
        assertFalse(canAppend("bytes 100-199/400", 0))
    }

    @Test
    fun fullResponseIgnoresAnUnsolicitedRangeHeader() {
        assertEquals(400L, offlineTransferTotalBytes(false, "bytes 100-199/400", 400))
        assertEquals(0L, offlineTransferTotalBytes(false, null, -1))
        requireCompleteOfflineTransfer(400, 0)
    }

    @Test
    fun emptyOrOversizedFilesCannotBePublished() {
        assertFailsWith<IOException> { requireCompleteOfflineTransfer(0, 0) }
        assertFailsWith<IOException> { requireCompleteOfflineTransfer(401, 400) }
    }

    @Test
    fun largestRepresentableCompleteFileDoesNotOverflow() {
        val range = parseOfflineContentRange("bytes 0-9223372036854775806/9223372036854775807")
        assertEquals(Long.MAX_VALUE, range?.responseBytes)
        assertEquals(Long.MAX_VALUE, range?.totalBytes)
    }

    private fun canAppend(
        range: String,
        length: Long,
    ): Boolean = canAppendOfflineRange(100, 206, range, "etag:v1", "etag:v1", length)
}
