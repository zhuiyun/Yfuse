package com.yfuse.core.filesource

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FileSourceLibraryFileStorageTest {
    private val folder: File = Files.createTempDirectory("filesource-library").toFile()

    @AfterTest
    fun cleanUp() {
        folder.deleteRecursively()
    }

    @Test
    fun nothing_is_read_before_anything_is_written() {
        assertNull(FileSourceLibraryFileStorage { folder }.read())
    }

    @Test
    fun a_write_replaces_the_document_and_leaves_no_staging_file() {
        val storage = FileSourceLibraryFileStorage { folder }

        storage.write("""{"a":1}""")
        storage.write("""{"b":2}""")

        assertEquals("""{"b":2}""", storage.read())
        assertFalse(File(folder, "filesource-library-v1.json.tmp").exists())
    }

    @Test
    fun a_failed_replacement_keeps_the_existing_target_and_removes_the_staging_file() {
        val target = File(folder, "filesource-library-v1.json").apply { mkdir() }
        val existing = File(target, "existing").apply { writeText("keep me") }
        val storage = FileSourceLibraryFileStorage { folder }

        assertFailsWith<IOException> { storage.write("{}") }

        assertEquals("keep me", existing.readText())
        assertFalse(File(folder, "filesource-library-v1.json.tmp").exists())
    }

    @Test
    fun without_a_files_directory_a_write_fails_loudly_and_a_read_finds_nothing() {
        val storage = FileSourceLibraryFileStorage { null }

        assertNull(storage.read())
        assertFailsWith<IllegalStateException> { storage.write("{}") }
    }
}
