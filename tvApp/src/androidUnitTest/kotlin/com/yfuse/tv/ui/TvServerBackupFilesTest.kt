package com.yfuse.tv.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvServerBackupFilesTest {
    @Test
    fun reader_accepts_exact_limit_and_rejects_the_next_byte() {
        assertEquals("1234", ByteArrayInputStream("1234".toByteArray()).readTvBackupText(limit = 4))
        assertFailsWith<IllegalArgumentException> {
            ByteArrayInputStream("12345".toByteArray()).readTvBackupText(limit = 4)
        }
        assertFailsWith<IllegalArgumentException> {
            ByteArrayInputStream(byteArrayOf()).readTvBackupText()
        }
    }

    @Test
    fun streaming_reader_does_not_trust_available_and_preserves_cancellation() {
        val stream =
            object : ByteArrayInputStream("12345".toByteArray()) {
                override fun available(): Int = 0
            }
        assertFailsWith<IllegalArgumentException> { stream.readTvBackupText(limit = 4) }
        assertFailsWith<CancellationException> {
            ByteArrayInputStream("1234".toByteArray()).readTvBackupText {
                throw CancellationException("Selection changed")
            }
        }
    }

    @Test
    fun selected_payload_is_a_snapshot_and_oversized_files_are_not_parsed() =
        runTest {
            val file = File.createTempFile("tv-backup", ".json")
            try {
                file.writeText("relay-v3")
                val selected = loadTvBackupFile(file) { it == "relay-v3" }
                file.writeText("different-file")
                assertEquals("relay-v3", selected.payload)
                assertTrue(selected.isRelay)

                file.writeBytes(ByteArray(MAX_TV_BACKUP_BYTES + 1))
                var parsed = false
                assertFailsWith<IllegalArgumentException> {
                    loadTvBackupFile(file) {
                        parsed = true
                        false
                    }
                }
                assertFalse(parsed)
            } finally {
                file.delete()
            }
        }
}
