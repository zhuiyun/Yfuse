package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackFailureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpvNativeBuildCapabilitiesTest {
    @Test
    fun custom_aar_marker_is_read_without_a_compile_time_dependency() {
        val capabilities =
            detectMpvNativeBuildCapabilities(
                className = FakeCustomMpvMarker::class.java.name,
                classLoader = FakeCustomMpvMarker::class.java.classLoader,
            )

        assertTrue(capabilities.libbluray)
        assertFalse(capabilities.bdj)
        assertTrue(capabilities.remoteRawBluRay)
        assertTrue(capabilities.bdmvVfs)
        assertTrue(capabilities.hdmvMenu)
        assertTrue(capabilities.nativeBluRay)
        assertTrue(capabilities.description.contains("ISO/BDMV"))
        assertEquals("mpv-rev", capabilities.libmpvAndroidRevision)
        assertEquals("bluray-rev", capabilities.libblurayRevision)
        assertEquals("udf-rev", capabilities.libudfreadRevision)
    }

    @Test
    fun older_custom_marker_without_new_fields_stays_conservative() {
        val capabilities =
            detectMpvNativeBuildCapabilities(
                className = FakeLegacyCustomMpvMarker::class.java.name,
                classLoader = FakeLegacyCustomMpvMarker::class.java.classLoader,
            )

        assertTrue(capabilities.libbluray)
        assertFalse(capabilities.remoteRawBluRay)
        assertFalse(capabilities.bdmvVfs)
        assertFalse(capabilities.hdmvMenu)
    }

    @Test
    fun marker_absence_means_stock_mpv_and_never_guesses_blu_ray_support() {
        val capabilities =
            detectMpvNativeBuildCapabilities(
                className = "missing.yfuse.NativeCapabilityMarker",
                classLoader = FakeCustomMpvMarker::class.java.classLoader,
            )

        assertFalse(capabilities.libbluray)
        assertFalse(capabilities.bdj)
        assertFalse(capabilities.remoteRawBluRay)
        assertFalse(capabilities.bdmvVfs)
        assertFalse(capabilities.hdmvMenu)
        assertFalse(capabilities.nativeBluRay)
        assertTrue(capabilities.description.contains("无 libbluray"))
    }

    @Test
    fun known_local_bluray_is_rejected_when_binary_has_no_libbluray() {
        val item = localDiscItem(container = "BLURAY", label = "UHD Blu-ray")

        val reason =
            missingNativeBluRayCapability(
                items = listOf(item),
                startIndex = 0,
                nativeCapabilities = MpvNativeBuildCapabilities(libbluray = false),
            )

        assertNotNull(reason)
        assertTrue(reason.contains("libbluray"))
        assertTrue(reason.contains("Blu-ray"))
    }

    @Test
    fun custom_libbluray_binary_removes_the_gate_and_generic_iso_is_not_misclassified() {
        val bluray = localDiscItem(container = "BLURAY", label = "UHD Blu-ray")
        val genericIso = localDiscItem(container = "ISO", label = "Disc Image")
        val capable = MpvNativeBuildCapabilities(libbluray = true)

        assertNull(missingNativeBluRayCapability(listOf(bluray), 0, capable))
        assertNull(
            missingNativeBluRayCapability(
                listOf(genericIso),
                0,
                MpvNativeBuildCapabilities(libbluray = false),
            ),
        )
    }

    @Test
    fun remote_bluray_is_not_blocked_by_the_local_binary_gate() {
        val version =
            PlayerMediaVersion(
                id = "remote-disc",
                label = "UHD Blu-ray",
                detail = "4K",
                url = "https://example.invalid/main-feature.m2ts",
                transcodeUrl = "",
                fallbackTranscodeUrl = "",
                container = "BLURAY",
                discSource = true,
            )
        val item =
            PlayerMediaItem(
                id = "movie",
                url = version.url,
                transcodeUrl = "",
                title = "Movie",
                versions = listOf(version),
                versionId = version.id,
            )

        assertNull(
            missingNativeBluRayCapability(
                listOf(item),
                0,
                MpvNativeBuildCapabilities(libbluray = false),
            ),
        )
    }

    @Test
    fun missing_binary_engine_is_terminal_but_does_not_poison_backend_failure_memory() {
        val engine =
            MissingNativeCapabilityVideoEngine(
                message = "missing native Blu-ray capability",
                startIndex = 0,
                itemCount = 1,
                startPositionMs = 12_345L,
            )
        val state = engine.state.value

        assertEquals(12_345L, state.positionMs)
        assertFalse(state.buffering)
        assertTrue(state.fallbacksExhausted)
        assertEquals(PlaybackFailureKind.Unknown, state.errorKind)
        assertEquals("missing native Blu-ray capability", state.error)
    }

    private fun localDiscItem(
        container: String,
        label: String,
    ): PlayerMediaItem {
        val version =
            PlayerMediaVersion(
                id = "local-disc",
                label = label,
                detail = "disc",
                url = "file:///storage/emulated/0/movie.iso",
                transcodeUrl = "",
                fallbackTranscodeUrl = "",
                container = container,
                discSource = true,
            )
        return PlayerMediaItem(
            id = "movie",
            url = version.url,
            transcodeUrl = "",
            title = "Movie",
            versions = listOf(version),
            versionId = version.id,
        )
    }
}

private class FakeCustomMpvMarker {
    companion object {
        @JvmField
        val LIBBLURAY = true

        @JvmField
        val BDJ = false

        @JvmField
        val REMOTE_RAW_BLURAY = true

        @JvmField
        val BDMV_VFS = true

        @JvmField
        val HDMV_MENU = true

        @JvmField
        val LIBMPV_ANDROID_REVISION = "mpv-rev"

        @JvmField
        val LIBBLURAY_REVISION = "bluray-rev"

        @JvmField
        val LIBUDFREAD_REVISION = "udf-rev"
    }
}

private class FakeLegacyCustomMpvMarker {
    companion object {
        @JvmField
        val LIBBLURAY = true

        @JvmField
        val BDJ = false

        @JvmField
        val LIBMPV_ANDROID_REVISION = "mpv-rev"

        @JvmField
        val LIBBLURAY_REVISION = "bluray-rev"

        @JvmField
        val LIBUDFREAD_REVISION = "udf-rev"
    }
}
