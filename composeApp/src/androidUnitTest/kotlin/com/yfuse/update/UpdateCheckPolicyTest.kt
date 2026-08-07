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
    fun startup_is_throttled_but_profile_check_remains_manual() {
        val mainSource = projectFile(
            "src/androidMain/kotlin/com/yfuse/MainActivity.kt",
        ).readText()
        val profileSource = projectFile(
            "src/androidMain/kotlin/com/yfuse/feature/profile/AppUpdateTools.android.kt",
        ).readText()

        assertTrue("updateManager.checkIfDue()" in mainSource)
        assertFalse("updateManager.check()" in mainSource)
        assertTrue("else -> manager::check" in profileSource)
    }

    private fun projectFile(moduleRelativePath: String): File =
        sequenceOf(
            File(moduleRelativePath),
            File("composeApp", moduleRelativePath),
        ).firstOrNull(File::isFile)
            ?: error("Cannot locate $moduleRelativePath from ${File(".").absolutePath}")
}
