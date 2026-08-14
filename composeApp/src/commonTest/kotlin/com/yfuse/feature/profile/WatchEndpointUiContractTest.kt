package com.yfuse.feature.profile

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchEndpointUiContractTest {
    @Test
    fun settings_no_longer_offer_a_custom_watch_relay() {
        val profile = projectFile("src/commonMain/kotlin/com/yfuse/feature/profile/ProfileScreen.kt").readText()
        val dialogs = projectFile("src/commonMain/kotlin/com/yfuse/feature/profile/ProfileDialogs.kt").readText()

        assertFalse(profile.contains("Sheet.WatchEndpoint"))
        assertFalse(profile.contains("一起看服务地址"))
        assertFalse(dialogs.contains("internal fun WatchEndpointDialog"))
    }

    @Test
    fun legacy_third_party_invites_are_rejected_in_both_entry_surfaces() {
        val dialogs = projectFile("src/commonMain/kotlin/com/yfuse/feature/profile/ProfileDialogs.kt").readText()
        val sheet = projectFile("src/commonMain/kotlin/com/yfuse/feature/watch/WatchInviteSheet.kt").readText()
        val app = projectFile("src/commonMain/kotlin/com/yfuse/app/App.kt").readText()

        assertTrue(dialogs.contains("unsupportedEndpoint == null"))
        assertTrue(dialogs.contains("一起看协议 v5 只连接 Yfuse 账号服务的官方安全地址"))
        assertTrue(sheet.contains("if (unsupportedEndpoint != null)"))
        assertTrue(sheet.contains("一起看协议 v5 只连接 Yfuse 账号服务的官方安全地址"))
        assertTrue(app.contains("endpoint = WatchTogetherPreferences.DEFAULT_ENDPOINT"))
        assertFalse(app.contains("endpoint = invite.endpoint"))
    }

    private fun projectFile(moduleRelativePath: String): File =
        sequenceOf(File(moduleRelativePath), File("composeApp", moduleRelativePath))
            .firstOrNull(File::isFile)
            ?: error("Cannot locate $moduleRelativePath from ${File(".").absolutePath}")
}
