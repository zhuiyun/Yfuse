package com.yfuse.core.playback

import com.russhwolf.settings.MapSettings
import com.yfuse.core.security.TestSecureStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlaybackOfflineLicenseCatalogTest {
    @Test
    fun key_set_is_kept_out_of_plain_settings_and_can_be_restored() {
        val settings = MapSettings()
        val secrets = TestSecureStore()
        val catalog = PlaybackOfflineLicenseCatalog(settings, secrets) { 1_000L }
        val keySetId = "super-secret-key-set".encodeToByteArray()
        val license =
            PlaybackOfflineLicense(
                id = "license-1",
                scheme = PlaybackDrmScheme.Widevine,
                acquiredAtEpochMs = 1_000L,
                updatedAtEpochMs = 1_000L,
            )

        catalog.put(license, keySetId)

        assertContentEquals(keySetId, catalog.keySetId("license-1"))
        assertEquals(setOf("license.license-1"), secrets.storedKeys())
        assertFalse(settings.keys.any { key -> settings.getString(key, "").contains("super-secret") })
    }

    @Test
    fun expiry_and_renewal_states_follow_persisted_deadlines() {
        var now = 1_000L
        val catalog = PlaybackOfflineLicenseCatalog(MapSettings(), TestSecureStore()) { now }
        catalog.put(
            PlaybackOfflineLicense(
                id = "license-2",
                scheme = PlaybackDrmScheme.Widevine,
                acquiredAtEpochMs = now,
                updatedAtEpochMs = now,
                licenseExpiresAtEpochMs = now + 8L * 24L * 60L * 60L * 1_000L,
            ),
            byteArrayOf(1),
        )

        assertEquals(PlaybackOfflineLicenseState.Usable, catalog.get("license-2").state)
        now += 2L * 24L * 60L * 60L * 1_000L
        assertEquals(PlaybackOfflineLicenseState.RenewalRequired, catalog.get("license-2").state)
        now += 7L * 24L * 60L * 60L * 1_000L
        assertEquals(PlaybackOfflineLicenseState.Expired, catalog.get("license-2").state)
    }

    @Test
    fun removal_deletes_both_catalog_and_encrypted_key() {
        val secrets = TestSecureStore()
        val catalog = PlaybackOfflineLicenseCatalog(MapSettings(), secrets) { 0L }
        catalog.put(
            PlaybackOfflineLicense(
                id = "license-3",
                scheme = PlaybackDrmScheme.Widevine,
                acquiredAtEpochMs = 0L,
                updatedAtEpochMs = 0L,
            ),
            byteArrayOf(1),
        )

        catalog.remove("license-3")

        assertEquals(emptyList(), catalog.list())
        assertEquals(emptySet(), secrets.storedKeys())
    }

    @Test
    fun failed_metadata_write_restores_the_previous_encrypted_key() {
        val settings = MapSettings()
        val secrets = TestSecureStore()
        val catalog = PlaybackOfflineLicenseCatalog(settings, secrets) { 0L }
        val license =
            PlaybackOfflineLicense(
                id = "license-4",
                scheme = PlaybackDrmScheme.Widevine,
                acquiredAtEpochMs = 0L,
                updatedAtEpochMs = 0L,
            )
        catalog.put(license, byteArrayOf(1, 2, 3))
        val brokenSettings =
            object : com.russhwolf.settings.Settings by settings {
                override fun putString(
                    key: String,
                    value: String,
                ) = error("disk full")
            }
        val brokenCatalog = PlaybackOfflineLicenseCatalog(brokenSettings, secrets) { 0L }

        kotlin.test.assertFailsWith<IllegalStateException> {
            brokenCatalog.put(license, byteArrayOf(4, 5, 6))
        }

        assertContentEquals(byteArrayOf(1, 2, 3), catalog.keySetId(license.id))
    }

    @Test
    fun failed_removal_metadata_write_keeps_the_catalog_and_encrypted_key() {
        val settings = MapSettings()
        val secrets = TestSecureStore()
        val catalog = PlaybackOfflineLicenseCatalog(settings, secrets) { 0L }
        val first =
            PlaybackOfflineLicense(
                id = "license-5",
                scheme = PlaybackDrmScheme.Widevine,
                acquiredAtEpochMs = 0L,
                updatedAtEpochMs = 0L,
            )
        val second = first.copy(id = "license-6")
        catalog.put(first, byteArrayOf(1))
        catalog.put(second, byteArrayOf(2))
        val brokenSettings =
            object : com.russhwolf.settings.Settings by settings {
                override fun putString(
                    key: String,
                    value: String,
                ) = error("disk full")
            }
        val brokenCatalog = PlaybackOfflineLicenseCatalog(brokenSettings, secrets) { 0L }

        kotlin.test.assertFailsWith<IllegalStateException> { brokenCatalog.remove(first.id) }

        assertEquals(setOf(first.id, second.id), catalog.list().map { it.id }.toSet())
        assertContentEquals(byteArrayOf(1), catalog.keySetId(first.id))
    }
}
