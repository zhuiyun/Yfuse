package com.yfuse.feature.profile

import com.yfuse.core.account.AccountDeviceSession
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountSessionsUiContractTest {
    @Test
    fun sessions_are_deduplicated_by_credential_id_without_merging_same_name_devices() {
        val first = session(id = "first", deviceName = "Phone", lastSeen = 30L)
        val duplicate = first.copy(lastSeenAtEpochMs = 20L)
        val secondLoginOnSamePhone = session(id = "second", deviceName = "Phone", lastSeen = 10L)

        val result = deduplicateAccountSessions(listOf(first, duplicate, secondLoginOnSamePhone))

        assertEquals(listOf("first", "second"), result.map(AccountDeviceSession::id))
        assertEquals(listOf("Phone", "Phone"), result.map(AccountDeviceSession::deviceName))
    }

    @Test
    fun account_page_has_one_sessions_entry_and_the_list_lives_on_a_child_page() {
        val accountSource =
            projectFile(
                "src/commonMain/kotlin/com/yfuse/feature/profile/AccountSettingsScreen.kt",
            ).readText()
        val sessionsSource =
            projectFile(
                "src/commonMain/kotlin/com/yfuse/feature/profile/AccountSessionsScreen.kt",
            ).readText()
        val profileSource =
            projectFile(
                "src/commonMain/kotlin/com/yfuse/feature/profile/ProfileScreen.kt",
            ).readText()

        assertEquals(1, Regex("Text\\(\"登录与会话\"").findAll(accountSource).count())
        assertFalse(accountSource.contains("sessions.forEach"))
        assertTrue(sessionsSource.contains("LaunchedEffect(userId)"))
        assertTrue(sessionsSource.contains("internal fun AccountSessionsContent("))
        assertTrue(sessionsSource.contains("otherSessions.forEachIndexed"))
        assertTrue(sessionsSource.contains("SessionDivider()"))
        assertFalse(sessionsSource.contains("YfLinkButton"))
        assertTrue(profileSource.contains("ProfilePage.AccountSessions ->"))
        assertTrue(profileSource.contains("openPage(ProfilePage.AccountSessions)"))
    }

    private fun session(
        id: String,
        deviceName: String,
        lastSeen: Long,
    ) = AccountDeviceSession(
        id = id,
        deviceName = deviceName,
        createdAtEpochMs = 0L,
        lastSeenAtEpochMs = lastSeen,
        current = false,
    )

    private fun projectFile(moduleRelativePath: String): File =
        sequenceOf(File(moduleRelativePath), File("composeApp", moduleRelativePath))
            .firstOrNull(File::isFile)
            ?: error("Cannot locate $moduleRelativePath from ${File(".").absolutePath}")
}
