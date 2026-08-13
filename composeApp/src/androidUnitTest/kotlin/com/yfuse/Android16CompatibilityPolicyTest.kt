package com.yfuse

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class Android16CompatibilityPolicyTest {
    @Test
    fun app_targets_api_36_but_keeps_the_product_back_animation_opt_out() {
        val build = projectFile("build.gradle.kts").readText()
        val manifest = projectFile("src/androidMain/AndroidManifest.xml").readText()

        assertTrue(Regex("""targetSdk\s*=\s*36""").containsMatchIn(build))
        assertTrue("android:enableOnBackInvokedCallback=\"false\"" in manifest)
    }

    @Test
    fun lan_features_declare_the_android_16_nearby_devices_permission() {
        val manifest = projectFile("src/androidMain/AndroidManifest.xml").readText()
        val discovery =
            projectFile(
                "src/androidMain/kotlin/com/yfuse/core/network/LanDiscovery.android.kt",
            ).readText()
        val permission =
            projectFile(
                "src/androidMain/kotlin/com/yfuse/core/network/LocalNetworkPermission.android.kt",
            ).readText()

        assertTrue("android.permission.NEARBY_WIFI_DEVICES" in manifest)
        assertTrue("android:usesPermissionFlags=\"neverForLocation\"" in manifest)
        assertTrue("requireLocalNetworkPermission()" in discovery)
        assertTrue("Build.VERSION.SDK_INT < 36" in permission)
    }

    @Test
    fun phone_orientation_is_preserved_while_api_36_large_screens_can_override_it() {
        val manifest = projectFile("src/androidMain/AndroidManifest.xml").readText()
        val playerBlock =
            manifest
                .substringAfter("com.yfuse.feature.player.PlayerActivity")
                .substringBefore("</activity>")
        val scannerBlock =
            manifest
                .substringAfter("com.yfuse.feature.profile.QrScannerActivity")
                .substringBefore("</activity>")

        assertTrue("android:screenOrientation=\"sensorLandscape\"" in playerBlock)
        assertTrue("android:screenOrientation=\"portrait\"" in scannerBlock)
        assertTrue("smallestScreenSize" in playerBlock)
    }

    private fun projectFile(moduleRelativePath: String): File =
        sequenceOf(
            File(moduleRelativePath),
            File("composeApp", moduleRelativePath),
        ).firstOrNull(File::isFile)
            ?: error("Cannot locate $moduleRelativePath from ${File(".").absolutePath}")
}
