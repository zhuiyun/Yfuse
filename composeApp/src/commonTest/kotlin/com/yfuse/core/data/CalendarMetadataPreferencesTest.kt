package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.security.SecureStoreException
import com.yfuse.core.security.TestSecureStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarMetadataPreferencesTest {
    @Test
    fun credentials_round_trip_only_through_secure_storage_and_can_be_removed() {
        val settings = MapSettings()
        val secrets = TestSecureStore()
        val preferences = CalendarMetadataPreferences(settings, secrets)
        preferences.saveTvdb(" key ", " pin ")
        assertEquals(
            TvdbCalendarCredentials("key", "pin"),
            CalendarMetadataPreferences(settings, secrets).tvdbCredentials(),
        )
        assertTrue(settings.keys.isEmpty())
        preferences.clearTvdb()
        assertNull(preferences.tvdbCredentials())
        assertTrue(secrets.storedKeys().isEmpty())
    }

    @Test
    fun secure_storage_failure_never_falls_back_to_plain_settings() {
        val settings = MapSettings()
        val secrets = TestSecureStore().apply { failWrites = true }
        val preferences = CalendarMetadataPreferences(settings, secrets)
        assertFailsWith<SecureStoreException> { preferences.saveTvdb("key", "pin") }
        assertNull(preferences.tvdbCredentials())
        assertTrue(settings.keys.isEmpty())
    }

    @Test
    fun corrupted_credentials_disable_tvdb_without_crashing_the_calendar() {
        val secrets = TestSecureStore()
        val preferences = CalendarMetadataPreferences(MapSettings(), secrets)
        preferences.saveTvdb("key", "pin")
        secrets.corruptedKeys += secrets.storedKeys()
        assertNull(preferences.tvdbCredentials())
    }
}
