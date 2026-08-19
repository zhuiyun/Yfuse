package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManifestTrackDeduplicationTest {
    @Test
    fun identical_hls_rendition_is_collapsed_and_keeps_selected_concrete_id() {
        val tracks =
            collapseManifestTrackDuplicates(
                listOf(
                    candidate(id = "0:0", group = "audio", name = "main", selected = false),
                    candidate(id = "1:0", group = "audio", name = "main", selected = true),
                ),
            )

        assertEquals(1, tracks.size)
        assertEquals("1:0", tracks.single().id)
        assertTrue(tracks.single().selected)
    }

    @Test
    fun same_language_and_label_with_distinct_renditions_are_preserved() {
        val tracks =
            collapseManifestTrackDuplicates(
                listOf(
                    candidate(
                        id = "0:0",
                        group = "audio-main",
                        name = "English",
                        qualifier = "EAC3 · 6 声道",
                    ),
                    candidate(
                        id = "1:0",
                        group = "audio-commentary",
                        name = "Commentary",
                        qualifier = "AAC · 2 声道",
                    ),
                ),
            )

        assertEquals(2, tracks.size)
        assertEquals(
            listOf("English · EAC3 · 6 声道", "English · AAC · 2 声道"),
            tracks.map { it.label },
        )
    }

    @Test
    fun apparent_duplicates_without_manifest_identity_are_never_collapsed() {
        val tracks =
            collapseManifestTrackDuplicates(
                listOf(
                    candidate(id = "0:0", group = null, name = null),
                    candidate(id = "1:0", group = null, name = null),
                ),
            )

        assertEquals(2, tracks.size)
        assertEquals(listOf("English 1", "English 2"), tracks.map { it.label })
        assertFalse(tracks.any { it.selected })
    }

    @Test
    fun same_hls_group_with_different_names_remains_two_tracks() {
        val tracks =
            collapseManifestTrackDuplicates(
                listOf(
                    candidate(id = "0:0", group = "subs", name = "English SDH"),
                    candidate(id = "0:1", group = "subs", name = "English forced"),
                ),
            )

        assertEquals(2, tracks.size)
        assertEquals(setOf("0:0", "0:1"), tracks.map { it.id }.toSet())
    }

    private fun candidate(
        id: String,
        group: String?,
        name: String?,
        selected: Boolean = false,
        qualifier: String? = null,
    ) = ManifestTrackCandidate(
        id = id,
        label = "English",
        language = "en",
        selected = selected,
        manifestGroupId = group,
        manifestName = name,
        qualifier = qualifier,
    )
}
