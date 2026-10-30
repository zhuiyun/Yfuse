package com.yfuse.core.logging

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerifiedDiagnosticExportTest {
    @Test
    fun destination_is_opened_only_after_a_complete_archive_is_available() {
        val cache = Files.createTempDirectory("export-test").toFile()
        val result = ByteArrayOutputStream()
        var finished = false
        try {
            exportVerifiedDiagnostics(cache, { output ->
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry("latest.jsonl"))
                    zip.write("latest error".toByteArray())
                    zip.closeEntry()
                }
                finished = true
            }) {
                assertTrue(finished)
                result
            }
            ZipInputStream(result.toByteArray().inputStream()).use {
                assertEquals("latest.jsonl", it.nextEntry.name)
                assertEquals("latest error", it.readBytes().decodeToString())
            }
            assertTrue(cache.listFiles()!!.isEmpty())
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun truncated_archive_never_reaches_the_destination_and_temp_file_is_removed() {
        val cache = Files.createTempDirectory("export-test").toFile()
        var opened = false
        try {
            assertFails {
                exportVerifiedDiagnostics(cache, { it.write(byteArrayOf(0x50, 0x4b, 3, 4)) }) {
                    opened = true
                    ByteArrayOutputStream()
                }
            }
            assertFalse(opened)
            assertTrue(cache.listFiles()!!.isEmpty())
        } finally {
            cache.deleteRecursively()
        }
    }
}
