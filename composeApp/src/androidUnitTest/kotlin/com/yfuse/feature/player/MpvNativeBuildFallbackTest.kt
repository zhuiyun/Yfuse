package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvNativeBuildFallbackTest {
    @Test
    fun verified_build_gate_restores_pinned_bluray_identity_when_runtime_marker_is_missing() {
        val capabilities = MpvNativeBuildCapabilities().withVerifiedBuildArtifactFallback()

        assertTrue(capabilities.pinnedYfuseBluRayArtifact)
        assertEquals(EXPECTED_LIBMPV_ANDROID_REVISION, capabilities.libmpvAndroidRevision)
        assertEquals(EXPECTED_LIBBLURAY_REVISION, capabilities.libblurayRevision)
        assertEquals(EXPECTED_LIBUDFREAD_REVISION, capabilities.libudfreadRevision)
        assertFalse(capabilities.dolbyVisionRpu)
        assertFalse(capabilities.dolbyVisionFel)
    }

    @Test
    fun runtime_marker_evidence_is_never_replaced_by_the_build_fallback() {
        val detected =
            MpvNativeBuildCapabilities(
                libbluray = true,
                libmpvAndroidRevision = "runtime-revision",
            )

        assertEquals(detected, detected.withVerifiedBuildArtifactFallback())
    }
}
