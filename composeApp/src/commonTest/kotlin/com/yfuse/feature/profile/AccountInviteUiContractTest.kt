package com.yfuse.feature.profile

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountInviteUiContractTest {
    @Test
    fun invite_entry_is_server_capability_gated_and_plaintext_is_not_saveable() {
        val source = accountSettingsSource()
        assertTrue(source.contains("if (user.canIssueInvites())"))
        assertFalse(source.contains("user.username == \"zhuiyun\""))
        assertTrue(source.contains("var issuedInvite by remember {"))
        assertFalse(source.contains("var issuedInvite by rememberSaveable"))
        assertTrue(source.contains("share.copySensitiveText(invite.code)"))
        assertTrue(source.contains("InviteCredentialSheet("))
        assertTrue(source.contains("internal fun InviteCredentialSheet("))
        assertTrue(source.contains("复制并关闭"))
        assertTrue(source.contains("暂不关闭"))
        assertTrue(source.contains("一次性邀请码"))
    }

    @Test
    fun invitation_expiry_is_rendered_as_an_explicit_utc_time() {
        assertEquals("2023-11-14 22:13 UTC", formatInviteExpiryUtc(1_700_000_000_000L))
    }

    @Test
    fun invite_code_is_grouped_for_readability_without_changing_the_credential() {
        val code = "00fkGXQc35Ma6egzQ5lcLuWlqAxAKgSGJk7lfc7qAvk"

        assertEquals(
            "00fkGXQc  35Ma6egz  Q5lcLuWl  qAxAKgSG  Jk7lfc7q  Avk",
            formatInviteCodeForDisplay(code),
        )
        assertEquals(code, formatInviteCodeForDisplay(code).replace("  ", ""))
    }

    private fun accountSettingsSource(): String =
        projectFile("src/commonMain/kotlin/com/yfuse/feature/profile/AccountSettingsScreen.kt").readText()

    private fun projectFile(moduleRelativePath: String): File =
        sequenceOf(File(moduleRelativePath), File("composeApp", moduleRelativePath))
            .firstOrNull(File::isFile)
            ?: error("Cannot locate $moduleRelativePath from ${File(".").absolutePath}")
}
