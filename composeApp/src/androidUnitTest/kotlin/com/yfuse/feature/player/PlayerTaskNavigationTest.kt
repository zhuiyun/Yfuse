package com.yfuse.feature.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerTaskNavigationTest {

    @Test
    fun player_is_pushed_onto_the_main_task_and_finishing_only_pops_the_player() {
        val launcherSource = projectFile(
            "src/androidMain/kotlin/com/yfuse/feature/player/VideoPlayer.android.kt",
        ).readText()
        val activitySource = projectFile(
            "src/androidMain/kotlin/com/yfuse/feature/player/PlayerActivity.kt",
        ).readText()
        val manifestSource = projectFile("src/androidMain/AndroidManifest.xml").readText()

        assertFalse("Intent.FLAG_ACTIVITY_NEW_TASK" in launcherSource)
        assertFalse("Intent.FLAG_ACTIVITY_CLEAR_TASK" in launcherSource)
        assertFalse("finishAndRemoveTask()" in activitySource)

        val closeBlock = activitySource
            .substringAfter("private fun closePlayerAndReturn()")
            .substringBefore("private fun enterPlayerPictureInPicture()")
        assertTrue(FINISH_STATEMENT.containsMatchIn(closeBlock))
        assertFalse("startActivity(" in closeBlock)

        val stopBlock = activitySource
            .substringAfter("private fun stopPlaybackAndFinish()")
            .substringBefore("private fun refreshEpisodes()")
        assertTrue(FINISH_STATEMENT.containsMatchIn(stopBlock))

        val playerManifestBlock = manifestSource
            .substringAfter("android:name=\"com.yfuse.feature.player.PlayerActivity\"")
            .substringBefore("/>")
        assertTrue("android:launchMode=\"singleTop\"" in playerManifestBlock)
        assertFalse("android:taskAffinity" in playerManifestBlock)
        assertFalse("android:excludeFromRecents" in playerManifestBlock)
    }

    @Test
    fun a_fresh_reentrant_launch_is_delivered_to_the_single_live_player() {
        val activitySource = projectFile(
            "src/androidMain/kotlin/com/yfuse/feature/player/PlayerActivity.kt",
        ).readText()
        val factoryBlock = activitySource
            .substringAfter("fun intent(")
            .substringBefore("/** Brings a live player forward")

        assertTrue("PlayerLaunchRegistry.register(request)" in factoryBlock)
        assertTrue(".apply(payload::writeTo)" in factoryBlock)
        assertTrue("Intent.FLAG_ACTIVITY_REORDER_TO_FRONT" in factoryBlock)
        assertTrue("Intent.FLAG_ACTIVITY_SINGLE_TOP" in factoryBlock)
        assertFalse("Intent.FLAG_ACTIVITY_CLEAR_TOP" in factoryBlock)

        val replacementBlock = activitySource
            .substringAfter("override fun onNewIntent")
            .substringBefore("override fun onStart()")
        assertTrue("if (intent.action == ACTION_OPEN) return" in replacementBlock)
        assertTrue("resolveFreshPlayerLaunch(payload)" in replacementBlock)
        assertTrue("launchViewModel.request = replacement.request" in replacementBlock)
        assertTrue("launchViewModel.resume = null" in replacementBlock)
        assertTrue("recreate()" in replacementBlock)
        assertFalse("finish()" in replacementBlock)
        assertTrue("launchViewModel.request === launchRequest" in activitySource)
    }

    @Test
    fun foreground_and_transport_notifications_share_the_payload_free_player_open_intent() {
        val activitySource =
            projectFile(
                "src/androidMain/kotlin/com/yfuse/feature/player/PlayerActivity.kt",
            ).readText()
        val notificationSource =
            projectFile(
                "src/androidMain/kotlin/com/yfuse/feature/player/PlayerNotificationController.kt",
            ).readText()
        val serviceSource =
            projectFile(
                "src/androidMain/kotlin/com/yfuse/feature/player/PlaybackKeepAliveService.kt",
            ).readText()
        val openIntentBlock =
            activitySource
                .substringAfter("internal fun openIntent")
                .substringBefore("internal fun discardLaunch")
        val transportNotificationBlock =
            notificationSource
                .substringAfter("fun update(")
                .substringBefore("private fun mediaPendingIntent")

        assertTrue("PlayerActivity.openIntent(this)" in serviceSource)
        assertTrue("PlayerActivity.openIntent(activity)" in transportNotificationBlock)
        assertFalse("getLaunchIntentForPackage" in serviceSource)
        assertFalse("PlayerLaunchIntentPayload" in serviceSource)
        assertFalse("PlayerLaunchIntentPayload" in openIntentBlock)
        assertTrue("Intent.FLAG_ACTIVITY_REORDER_TO_FRONT" in openIntentBlock)
        assertTrue("Intent.FLAG_ACTIVITY_SINGLE_TOP" in openIntentBlock)
        assertFalse("Intent.FLAG_ACTIVITY_CLEAR_TOP" in openIntentBlock)
        assertTrue("PlayerActivity.NOTIFICATION_ID" in serviceSource)
        assertTrue("PlayerActivity.NOTIFICATION_CHANNEL" in serviceSource)
        assertTrue("notificationManager.activeNotifications" in serviceSource)
        assertTrue("existingNotification ?: notification()" in serviceSource)
    }

    private fun projectFile(moduleRelativePath: String): File =
        sequenceOf(
            File(moduleRelativePath),
            File("composeApp", moduleRelativePath),
        ).firstOrNull(File::isFile)
            ?: error("Cannot locate $moduleRelativePath from ${File(".").absolutePath}")

    private companion object {
        val FINISH_STATEMENT = Regex("""(?m)^\s{8}finish\(\)\s*$""")
    }
}
