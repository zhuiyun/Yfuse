package com.yfuse.feature.filesource

import com.yfuse.core.filesource.FileSourceEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class FolderBrowsePolicyTest {
    private val rows =
        listOf(
            row("第10集.mkv", size = 100, modified = 300),
            row("第2集.mkv", size = 200, modified = 100),
            row("第1集.mkv"),
            row("Extras", directory = true),
        )

    @Test fun descending_names_keep_folders_first_and_compare_episode_numbers_naturally() {
        assertEquals(listOf("Extras", "第10集.mkv", "第2集.mkv", "第1集.mkv"), names(FolderSort.Name, true))
        assertEquals(listOf("Extras", "第1集.mkv", "第2集.mkv", "第10集.mkv"), names(FolderSort.Name, false))
    }

    @Test fun unknown_metadata_is_last_in_both_directions() {
        assertEquals(listOf("Extras", "第2集.mkv", "第10集.mkv", "第1集.mkv"), names(FolderSort.Size, true))
        assertEquals(listOf("Extras", "第10集.mkv", "第2集.mkv", "第1集.mkv"), names(FolderSort.Size, false))
        assertEquals(listOf("Extras", "第10集.mkv", "第2集.mkv", "第1集.mkv"), names(FolderSort.Modified, true))
    }

    @Test fun search_matches_every_word_without_modifying_the_original_queue() {
        val found = browseFolderRows(rows, "  第2  MKV  ", FolderSort.Name, false)
        assertEquals(listOf("第2集.mkv"), found.map { it.entry.name })
        assertEquals(4, rows.size)
        assertEquals(emptyList(), browseFolderRows(rows, "unknown", FolderSort.Name, false))
    }

    private fun names(
        sort: FolderSort,
        descending: Boolean,
    ) = browseFolderRows(rows, "", sort, descending).map { it.entry.name }

    private fun row(
        name: String,
        directory: Boolean = false,
        size: Long? = null,
        modified: Long? = null,
    ) = FolderRow(FileSourceEntry(name, directory, size, modified), name, 0)
}
