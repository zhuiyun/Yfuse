package com.yfuse.feature.profile

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadSortMemoryTest {
    @Test
    fun explicit_choice_survives_reopening_the_page() {
        val settings = MapSettings()
        val first = DownloadSortMemory(settings)
        assertEquals(DownloadSort.Added, first.read())
        first.write(DownloadSort.Name)
        assertEquals(DownloadSort.Name, DownloadSortMemory(settings).read())
        first.write(DownloadSort.Size)
        assertEquals(DownloadSort.Size, DownloadSortMemory(settings).read())
    }

    @Test
    fun unknown_and_legacy_choices_fall_back_to_stable_addition_order() {
        val settings = MapSettings()
        for (value in listOf("Updated", "unknown")) {
            settings.putString("downloads.sort.v1", value)
            assertEquals(DownloadSort.Added, DownloadSortMemory(settings).read())
        }
    }
}
