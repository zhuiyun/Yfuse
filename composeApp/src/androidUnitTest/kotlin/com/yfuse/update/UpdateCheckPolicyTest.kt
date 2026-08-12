package com.yfuse.update

import com.russhwolf.settings.MapSettings
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun process_launch_can_force_one_manifest_check_inside_the_persisted_interval() {
        val settings = MapSettings()
        var now = 1_000_000L
        val firstProcess = AutomaticUpdateCheckGate(settings) { now }
        assertTrue(firstProcess.tryAcquire())

        now += 5_000L
        val reopenedProcess = AutomaticUpdateCheckGate(settings) { now }
        assertFalse(reopenedProcess.tryAcquire())
        assertTrue(reopenedProcess.tryAcquire(force = true))
        assertFalse(reopenedProcess.tryAcquire())
    }

    @Test
    fun release_0_2_47_is_newer_than_installed_0_2_46_by_version_code() {
        // Production 0.2.46 is build 108 and 0.2.47 is build 109. The display names never
        // participate in ordering, so a patch release cannot be lost to string comparison.
        assertTrue(isPublishedUpdateAvailable(publishedVersionCode = 109, installedVersionCode = 108))
        assertFalse(isPublishedUpdateAvailable(publishedVersionCode = 108, installedVersionCode = 108))
        assertFalse(isPublishedUpdateAvailable(publishedVersionCode = 107, installedVersionCode = 108))
    }

    @Test
    fun initial_foreground_waits_for_launch_check_before_enabling_resume_checks() {
        // onActivityResumed precedes composition of the splash-gated AppUpdateOverlay. Letting
        // it check here can spend the daily prompt allowance on a dialog that the launch check
        // immediately clears.
        assertFalse(
            shouldCheckForUpdateOnForeground(
                wasBackground = true,
                launchCheckStarted = false,
            ),
        )
        assertTrue(
            shouldCheckForUpdateOnForeground(
                wasBackground = true,
                launchCheckStarted = true,
            ),
        )
        assertFalse(
            shouldCheckForUpdateOnForeground(
                wasBackground = false,
                launchCheckStarted = true,
            ),
        )
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
        val managerSource = projectFile(
            "src/androidMain/kotlin/com/yfuse/update/AppUpdateManager.kt",
        ).readText()

        assertTrue("LaunchedEffect(Unit) { manager.checkOnLaunch() }" in overlaySource)
        assertTrue("RootComponent.Tab.Home) manager.checkIfDue()" in overlaySource)
        assertTrue(
            "shouldCheckForUpdateOnForeground(wasBackground, launchCheckStarted)" in managerSource,
        )
        assertFalse("if (wasBackground) checkIfDue()" in managerSource)
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

    @Test
    fun an_old_download_is_replaced_when_the_manifest_publishes_a_new_version() {
        assertEquals(
            ActiveDownloadManifestAction.Replace,
            activeDownloadManifestAction(
                active = manifest(versionCode = 80),
                published = manifest(versionCode = 81),
                installedVersionCode = 79,
            ),
        )
    }

    @Test
    fun an_old_download_is_invalidated_when_the_published_version_is_no_longer_an_update() {
        assertEquals(
            ActiveDownloadManifestAction.Invalidate,
            activeDownloadManifestAction(
                active = manifest(versionCode = 80),
                published = manifest(versionCode = 79),
                installedVersionCode = 79,
            ),
        )
    }

    @Test
    fun only_manifest_metadata_may_change_without_replacing_the_active_download() {
        val active = manifest(versionCode = 80, notes = "old notes")

        assertEquals(
            ActiveDownloadManifestAction.Keep,
            activeDownloadManifestAction(
                active = active,
                published = active.copy(versionName = "0.2.80-hotfix", notes = "new notes"),
                installedVersionCode = 79,
            ),
        )
        assertEquals(
            ActiveDownloadManifestAction.Replace,
            activeDownloadManifestAction(
                active = active,
                published = active.copy(sha256 = "b".repeat(64)),
                installedVersionCode = 79,
            ),
        )
    }

    @Test
    fun finishing_a_download_keeps_same_package_metadata_refreshed_by_a_check() {
        val downloaded = manifest(versionCode = 80, notes = "old notes")
        val refreshed = downloaded.copy(versionName = "0.2.80-hotfix", notes = "new notes")

        assertEquals(
            refreshed,
            latestManifestForFinishedDownload(
                downloaded,
                UpdateState.Downloading(refreshed, 999L, refreshed.size),
            ),
        )
        assertEquals(
            downloaded,
            latestManifestForFinishedDownload(
                downloaded,
                UpdateState.Downloading(manifest(versionCode = 81), 1L, 1_000L),
            ),
        )
    }

    @Test
    fun refreshing_a_resume_validator_preserves_newer_manifest_metadata() {
        val started = manifest(versionCode = 80, notes = "old notes")
        val refreshed = started.copy(versionName = "0.2.80-hotfix", notes = "new notes")

        assertEquals(
            UpdateDownloadRecord(refreshed, validator = "\"etag-2\""),
            mergeDownloadRecordValidator(
                current = UpdateDownloadRecord(refreshed, validator = "\"etag-1\""),
                attempted = UpdateDownloadRecord(started, validator = "\"etag-2\""),
            ),
        )
        assertEquals(
            null,
            mergeDownloadRecordValidator(
                current = UpdateDownloadRecord(refreshed),
                attempted = UpdateDownloadRecord(manifest(versionCode = 81)),
            ),
        )
    }

    @Test
    fun null_download_snapshot_is_stale_when_restore_or_download_publishes_owned_state() {
        val manifest = manifest(versionCode = 80)

        assertTrue(
            updateCheckSnapshotStillCurrent(
                startGeneration = 7,
                currentGeneration = 7,
                startedWithOwnedState = false,
                currentState = UpdateState.Checking,
            ),
        )
        assertFalse(
            updateCheckSnapshotStillCurrent(
                startGeneration = 7,
                currentGeneration = 7,
                startedWithOwnedState = false,
                currentState = UpdateState.Paused(manifest, 10L, manifest.size),
            ),
        )
        assertFalse(
            updateCheckSnapshotStillCurrent(
                startGeneration = 7,
                currentGeneration = 8,
                startedWithOwnedState = false,
                currentState = UpdateState.Downloading(manifest, 10L, manifest.size),
            ),
        )
    }

    @Test
    fun check_started_from_an_owned_state_allows_same_generation_progress_only() {
        val manifest = manifest(versionCode = 80)

        assertTrue(
            updateCheckSnapshotStillCurrent(
                startGeneration = 7,
                currentGeneration = 7,
                startedWithOwnedState = true,
                currentState = UpdateState.Ready(manifest, File("Yfuse-80.apk")),
            ),
        )
        assertFalse(
            updateCheckSnapshotStillCurrent(
                startGeneration = 7,
                currentGeneration = 8,
                startedWithOwnedState = true,
                currentState = UpdateState.Paused(manifest, 10L, manifest.size),
            ),
        )
    }

    private fun manifest(
        versionCode: Int,
        notes: String = "",
    ): UpdateManifest = UpdateManifest(
        versionCode = versionCode,
        versionName = "0.2.$versionCode",
        apkUrl = "https://47.112.219.60/yfuse/Yfuse-$versionCode.apk",
        sha256 = "a".repeat(64),
        size = 1_000L,
        notes = notes,
    )

    private fun projectFile(moduleRelativePath: String): File =
        sequenceOf(
            File(moduleRelativePath),
            File("composeApp", moduleRelativePath),
        ).firstOrNull(File::isFile)
            ?: error("Cannot locate $moduleRelativePath from ${File(".").absolutePath}")
}
