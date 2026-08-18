package com.yfuse.feature.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class NativeBluRayBuildContractTest {
    @Test
    fun build_lane_stamps_only_features_that_the_verifier_requires() {
        val build = repositoryFile("scripts/build-yfuse-mpv-bluray.sh").readText()
        val verifier = repositoryFile("scripts/verify-yfuse-mpv-bluray-aar.sh").readText()
        val anglePatch = repositoryFile("scripts/native/patch_yfuse_bluray_angle.py").readText()

        listOf(
            "remote-raw-bluray=true",
            "bdmv-vfs=true",
            "hdmv-menu=true",
            "multi-angle=true",
        ).forEach { capability ->
            assertTrue(capability in build, "build manifest must stamp $capability")
            assertTrue(capability.substringBefore('=') in verifier, "verifier must gate $capability")
        }

        assertTrue("MULTI_ANGLE = true" in anglePatch)
        assertTrue("nativeSelectAngle" in anglePatch)
        assertTrue("nativeSelectAngle" in verifier)
        assertTrue("YfuseBluRayRegistry" in verifier)
        assertTrue("YfuseBdmvRegistry" in verifier)
        assertTrue("yfusebd" in verifier)
        assertTrue("yfusebdmv" in verifier)
        assertTrue("16 KiB" in verifier)
    }

    @Test
    fun bdj_stays_explicitly_disabled_in_the_current_native_release_lane() {
        val build = repositoryFile("scripts/build-yfuse-mpv-bluray.sh").readText()
        val feasibility = repositoryFile("docs/BDJ_ANDROID_FEASIBILITY.md").readText()

        assertTrue("-Dbdj_jar=disabled" in build)
        assertTrue("public static final boolean BDJ = false" in build)
        assertTrue("No-Go" in feasibility)
    }

    private fun repositoryFile(path: String): File = File(repositoryRoot(), path)

    private fun repositoryRoot(): File {
        var current = File(System.getProperty("user.dir")).canonicalFile
        repeat(8) {
            if (File(current, "settings.gradle.kts").isFile && File(current, "composeApp").isDirectory) {
                return current
            }
            current = current.parentFile ?: return@repeat
        }
        error("Could not locate Yfuse repository root from ${System.getProperty("user.dir")}")
    }
}
