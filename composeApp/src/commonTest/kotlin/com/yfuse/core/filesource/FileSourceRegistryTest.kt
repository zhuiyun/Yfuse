package com.yfuse.core.filesource

import com.russhwolf.settings.MapSettings
import com.yfuse.core.security.TestSecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSourceRegistryTest {
    private val settings = MapSettings()
    private val secureStore = TestSecureStore()
    private var ids = 0

    private fun registry() =
        FileSourceRegistry(
            settings = settings,
            secureStore = secureStore,
            ioDispatcher = Dispatchers.Unconfined,
            newId = { "fs" + (++ids).toString(16).padStart(24, '0') },
        )

    private fun source(
        id: String,
        username: String = "alice",
    ) = FileSource(
        id = id,
        kind = FileSourceKind.Alist,
        name = "家里 NAS",
        origin = "http://192.168.1.2:5244",
        rootSegments = listOf("dav"),
        username = username,
    )

    @Test
    fun the_password_goes_to_the_secure_store_and_never_to_settings() =
        runTest {
            val registry = registry()
            val id = registry.newSourceId()

            registry.save(source(id), password = "s3cret-pass")

            assertTrue(settings.keys.none { settings.getStringOrNull(it)?.contains("s3cret-pass") == true })
            assertEquals(setOf("file-source-password:$id"), secureStore.storedKeys())
            val credentials = registry.credentials(id)
            assertEquals("alice", credentials?.username)
            assertEquals("s3cret-pass", credentials?.password)
        }

    @Test
    fun metadata_survives_a_restart_and_the_password_is_read_on_demand() =
        runTest {
            val first = registry()
            val id = first.newSourceId()
            first.save(source(id), password = "pw")

            val second = registry()

            assertEquals(listOf(source(id)), second.sources.value)
            assertEquals("pw", second.credentials(id)?.password)
        }

    @Test
    fun an_edit_without_a_password_keeps_the_stored_one() =
        runTest {
            val registry = registry()
            val id = registry.newSourceId()
            registry.save(source(id), password = "pw")

            registry.save(source(id).copy(name = "改名"), password = null)

            assertEquals("改名", registry.source(id)?.name)
            assertEquals("pw", registry.credentials(id)?.password)
            assertEquals(1, registry.sources.value.size)
        }

    @Test
    fun a_guest_source_stores_no_password() =
        runTest {
            val registry = registry()
            val id = registry.newSourceId()
            registry.save(source(id), password = "pw")

            registry.save(source(id, username = ""), password = "ignored")

            assertTrue(secureStore.storedKeys().isEmpty())
            assertTrue(registry.credentials(id)?.anonymous == true)
        }

    @Test
    fun removing_a_source_deletes_its_password() =
        runTest {
            val registry = registry()
            val id = registry.newSourceId()
            registry.save(source(id), password = "pw")

            registry.remove(id)

            assertTrue(registry.sources.value.isEmpty())
            assertTrue(secureStore.storedKeys().isEmpty())
            assertNull(registry.credentials(id))
        }

    @Test
    fun an_unreadable_password_reads_as_empty_instead_of_failing() =
        runTest {
            val registry = registry()
            val id = registry.newSourceId()
            registry.save(source(id), password = "pw")
            secureStore.corruptedKeys += "file-source-password:$id"

            assertEquals("", registry.credentials(id)?.password)
        }

    @Test
    fun a_malformed_id_or_document_is_refused() =
        runTest {
            val registry = registry()

            assertFailsWith<IllegalArgumentException> { registry.save(source("../etc"), password = "pw") }
            settings.putString("filesources.v1", "{broken")
            assertTrue(registry().sources.value.isEmpty())
            assertFalse(randomFileSourceId() == randomFileSourceId())
            assertTrue(randomFileSourceId().matches(Regex("fs[0-9a-f]{24}")))
        }
}
