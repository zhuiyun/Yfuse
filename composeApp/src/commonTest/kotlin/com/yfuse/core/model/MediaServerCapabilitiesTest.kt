package com.yfuse.core.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaServerCapabilitiesTest {
    @Test
    fun `emby family exposes user data and server task capabilities`() {
        listOf(MediaServerKind.Emby, MediaServerKind.Jellyfin).forEach { kind ->
            val capabilities = kind.capabilities()
            assertTrue(capabilities.favorites)
            assertTrue(capabilities.scheduledTasks)
            assertTrue(capabilities.subtitleStore)
            assertFalse(capabilities.itemAnalysis)
        }
    }

    @Test
    fun `plex exposes analysis without pretending to support emby APIs`() {
        val capabilities = MediaServerKind.Plex.capabilities()
        assertTrue(capabilities.itemAnalysis)
        assertTrue(capabilities.metadataRefresh)
        assertFalse(capabilities.favorites)
        assertFalse(capabilities.scheduledTasks)
        assertFalse(capabilities.subtitleStore)
    }
}
