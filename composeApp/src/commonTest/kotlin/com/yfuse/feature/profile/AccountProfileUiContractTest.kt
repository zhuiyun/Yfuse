package com.yfuse.feature.profile

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountProfileUiContractTest {
    @Test
    fun selected_avatar_is_the_signed_in_identity_and_refreshes_from_account_state() {
        val source = accountSettingsSource()
        val avatar = source.substringAfter("private fun AccountAvatar(")

        assertTrue("LaunchedEffect(user.nickname, user.avatarId)" in source)
        assertTrue("private fun AccountAvatar(avatarId: Int)" in source)
        assertTrue("WatchAvatar(avatarId = avatarId, size = 54.dp)" in avatar)
        assertFalse("nickname: String" in avatar)
        assertFalse("initial.isBlank()" in avatar)
    }

    @Test
    fun saving_profile_has_an_explicit_success_state() {
        val source = accountSettingsSource()

        assertTrue(".onSuccess { profileSaved = true }" in source)
        assertTrue("Text(\"资料已保存\"" in source)
    }

    private fun accountSettingsSource(): String =
        projectFile("src/commonMain/kotlin/com/yfuse/feature/profile/AccountSettingsScreen.kt").readText()

    private fun projectFile(moduleRelativePath: String): File =
        sequenceOf(File(moduleRelativePath), File("composeApp", moduleRelativePath))
            .firstOrNull(File::isFile)
            ?: error("Cannot locate $moduleRelativePath from ${File(".").absolutePath}")
}
