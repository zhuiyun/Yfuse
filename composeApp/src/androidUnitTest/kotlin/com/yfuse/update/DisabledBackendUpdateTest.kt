package com.yfuse.update

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.russhwolf.settings.MapSettings
import com.yfuse.backend.BackendAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DisabledBackendUpdateTest {
    @Test
    fun removing_backend_discards_pending_transfer_without_opening_cached_files() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val settings = MapSettings().apply { putString("update.download.v1", "previous transfer") }
                val manager = AppUpdateManager(NoUpdateSideEffectsContext(), settings, BackendAccess(enabled = false))

                assertNull(settings.getStringOrNull("update.download.v1"))
                assertEquals(UpdateState.Idle, manager.state.value)
                assertFalse(manager.enabled)
                assertFalse(manager.promptVisible.value)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun disabled_backend_ignores_automatic_manual_and_old_download_actions() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val settings = MapSettings()
                val manager = AppUpdateManager(NoUpdateSideEffectsContext(), settings, BackendAccess(enabled = false))
                // A stale UI or service can still deliver actions from a previously enabled installation.
                val manifest =
                    UpdateManifest(
                        versionCode = Int.MAX_VALUE,
                        versionName = "old-backend-release",
                        apkUrl = "https://removed-backend.invalid/old.apk",
                        sha256 = "0".repeat(64),
                        size = 3L,
                    )
                manager.checkOnLaunch()
                manager.checkIfDue()
                manager.check()
                manager.download(manifest)
                manager.runActiveDownload()
                manager.showPrompt()
                manager.install(File("old.apk"))
                manager.resumeInstall()
                advanceUntilIdle()

                assertEquals(UpdateState.Idle, manager.state.value)
                assertFalse(manager.promptVisible.value)
                assertEquals(emptySet(), settings.keys)
            } finally {
                Dispatchers.resetMain()
            }
        }

    private class NoUpdateSideEffectsContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this

        override fun getCacheDir(): File = error("Disabled updates must not inspect an old download")

        override fun startForegroundService(service: Intent): ComponentName =
            error("Disabled updates must not start a transfer")

        override fun startActivity(intent: Intent) = error("Disabled updates must not launch an installer")
    }
}
