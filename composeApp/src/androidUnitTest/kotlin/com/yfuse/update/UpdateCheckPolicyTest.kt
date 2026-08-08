package com.yfuse.update

import com.russhwolf.settings.MapSettings
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateCheckPolicyTest {

    @Test
    fun automatic_check_attempt_is_persisted_across_manager_recreation_for_one_day() {
        val settings = MapSettings()
        var now = 1_000_000L

        assertTrue(AutomaticUpdateCheckGate(settings) { now }.tryAcquire())
        assertFalse(AutomaticUpdateCheckGate(settings) { now }.tryAcquire())

        now += AUTOMATIC_UPDATE_CHECK_INTERVAL_MS - 1L
        assertFalse(AutomaticUpdateCheckGate(settings) { now }.tryAcquire())

        now += 1L
        assertTrue(AutomaticUpdateCheckGate(settings) { now }.tryAcquire())
    }

    @Test
    fun wall_clock_rollback_allows_one_recovery_check_then_starts_a_new_interval() {
        val settings = MapSettings()
        var now = AUTOMATIC_UPDATE_CHECK_INTERVAL_MS * 2L
        assertTrue(AutomaticUpdateCheckGate(settings) { now }.tryAcquire())

        now = AUTOMATIC_UPDATE_CHECK_INTERVAL_MS
        assertTrue(AutomaticUpdateCheckGate(settings) { now }.tryAcquire())
        assertFalse(AutomaticUpdateCheckGate(settings) { now }.tryAcquire())
    }

    @Test
    fun entering_home_checks_automatically_while_the_profile_check_stays_manual() {
        val overlaySource = projectFile(
            "src/androidMain/kotlin/com/yfuse/update/AppUpdateOverlay.kt",
        ).readText()
        val mainSource = projectFile(
            "src/androidMain/kotlin/com/yfuse/MainActivity.kt",
        ).readText()
        val profileSource = projectFile(
            "src/androidMain/kotlin/com/yfuse/feature/profile/AppUpdateTools.android.kt",
        ).readText()

        assertTrue("RootComponent.Tab.Home) manager.checkIfDue()" in overlaySource)
        // Nothing may run the unthrottled check on the way in.
        assertFalse("manager.check()" in overlaySource)
        assertFalse("updateManager.check()" in mainSource)
        assertTrue("else -> manager::check" in profileSource)
    }

    @Test
    fun the_dialog_opens_automatically_once_a_day_for_a_version() {
        val settings = MapSettings()
        var today = 20_000L
        val gate = AutomaticUpdatePromptGate(settings) { today }

        assertTrue(gate.tryAcquire(versionCode = 80))
        assertFalse(gate.tryAcquire(versionCode = 80))
        // A check that runs many times a day may not re-open the dialog.
        assertFalse(AutomaticUpdatePromptGate(settings) { today }.tryAcquire(versionCode = 80))

        // A release published later the same day gets its own prompt.
        assertTrue(gate.tryAcquire(versionCode = 81))
        assertFalse(gate.tryAcquire(versionCode = 81))

        today += 1L
        assertTrue(AutomaticUpdatePromptGate(settings) { today }.tryAcquire(versionCode = 81))
        assertFalse(AutomaticUpdatePromptGate(settings) { today }.tryAcquire(versionCode = 81))
    }

    private fun projectFile(moduleRelativePath: String): File =
        sequenceOf(
            File(moduleRelativePath),
            File("composeApp", moduleRelativePath),
        ).firstOrNull(File::isFile)
            ?: error("Cannot locate $moduleRelativePath from ${File(".").absolutePath}")
}
