package com.yfuse.core.personal

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PersonalScopedSettingsTest {
    @Test
    fun clearKeepsItsOriginalProfileIfProfileChangesDuringRemoval() {
        var namespace = "first"
        val storage = MapSettings()
        val switchingStorage =
            object : Settings by storage {
                override fun remove(key: String) {
                    storage.remove(key)
                    namespace = "second"
                }
            }
        for (profile in listOf("first", "second")) {
            for (key in listOf("filters", "reminders")) {
                storage.putString("personal.scope.$profile.$key", "$profile:$key")
            }
        }
        storage.putString("unscoped", "keep")

        PersonalScopedSettings(switchingStorage) { namespace }.clear()

        assertFalse(storage.hasKey("personal.scope.first.filters"))
        assertFalse(storage.hasKey("personal.scope.first.reminders"))
        assertEquals("second:filters", storage.getStringOrNull("personal.scope.second.filters"))
        assertEquals("second:reminders", storage.getStringOrNull("personal.scope.second.reminders"))
        assertEquals("keep", storage.getStringOrNull("unscoped"))
    }
}
