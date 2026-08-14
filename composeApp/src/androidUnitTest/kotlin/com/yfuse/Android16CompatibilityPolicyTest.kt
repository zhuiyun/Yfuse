package com.yfuse

import com.yfuse.core.util.shouldLockCompactScreenOrientation
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
        val player =
            projectFile(
                "src/androidMain/kotlin/com/yfuse/feature/player/PlayerActivity.kt",
            ).readText()
        val scanner =
            projectFile(
                "src/androidMain/kotlin/com/yfuse/feature/profile/QrScannerActivity.kt",
            ).readText()

        assertTrue("android:screenOrientation" !in manifest)
        assertTrue("SCREEN_ORIENTATION_SENSOR_LANDSCAPE" in player)
        assertTrue("SCREEN_ORIENTATION_PORTRAIT" in scanner)
        assertTrue(shouldLockCompactScreenOrientation(599))
        assertTrue(!shouldLockCompactScreenOrientation(600))
    }

    @Test
    fun large_downloads_use_android_allocatable_storage_instead_of_raw_filesystem_space() {
        val updateManager =
            projectFile(
                "src/androidMain/kotlin/com/yfuse/update/AppUpdateManager.kt",
            ).readText()
        val offlineManager =
            projectFile(
                "src/androidMain/kotlin/com/yfuse/core/offline/OfflineMedia.android.kt",
            ).readText()

        listOf(updateManager, offlineManager).forEach { source ->
            assertTrue("getAllocatableBytes" in source)
            val codeWithoutLineComments =
                source.lineSequence().joinToString("\n") { line -> line.substringBefore("//") }
            assertTrue(!Regex("""\.\s*usableSpace\b""").containsMatchIn(codeWithoutLineComments))
        }
    }

    private fun projectFile(moduleRelativePath: String): File =
        sequenceOf(
            File(moduleRelativePath),
            File("composeApp", moduleRelativePath),
        ).firstOrNull(File::isFile)
            ?: error("Cannot locate $moduleRelativePath from ${File(".").absolutePath}")
}
