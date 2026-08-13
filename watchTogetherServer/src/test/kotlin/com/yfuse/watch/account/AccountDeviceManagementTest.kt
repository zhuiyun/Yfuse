package com.yfuse.watch.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountDeviceManagementTest {
    @Test
    fun invitationIsSingleUseAndRegistrationRecordsDevice() {
        val invite = "invite-code-2026"
        AccountBackend.inMemoryForTests(
            registrationPolicy = AccountRegistrationPolicy(
                enabled = true,
                invitationCodes = setOf(invite),
            ),
        ).use { backend ->
            val auth = backend.service.register(
                registration("Alice", invite, "Living room TV"),
            )
            val sessions = backend.service.listSessions(auth.accessToken).sessions
            assertEquals(1, sessions.size)
            assertEquals("Living room TV", sessions.single().deviceName)
            assertTrue(sessions.single().current)

            val failure = assertFailsWith<AccountServiceException> {
                backend.service.register(registration("Bob", invite, "Phone"))
            }
            assertEquals(AccountProblem.InvitationInvalid, failure.problem)
            assertEquals("invite_invalid", failure.safeCode)
        }
    }

    @Test
    fun sessionOwnerCanRevokeOneOtherDeviceOrEveryOtherDevice() {
        AccountBackend.inMemoryForTests().use { backend ->
            val first = backend.service.register(registration("Alice", null, "TV"))
            val phone = backend.service.login(LoginRequest("Alice", PASSWORD, "Phone"))
            val tablet = backend.service.login(LoginRequest("Alice", PASSWORD, "Tablet"))

            val initial = backend.service.listSessions(phone.accessToken).sessions
            assertEquals(setOf("TV", "Phone", "Tablet"), initial.map { it.deviceName }.toSet())
            val tabletSession = initial.single { it.deviceName == "Tablet" }
            backend.service.revokeSession(phone.accessToken, tabletSession.id)
            assertUnauthorized { backend.service.getProfile(tablet.accessToken) }
            assertEquals(2, backend.service.listSessions(phone.accessToken).sessions.size)

            backend.service.revokeOtherSessions(phone.accessToken)
            assertUnauthorized { backend.service.getProfile(first.accessToken) }
            val remaining = backend.service.listSessions(phone.accessToken).sessions
            assertEquals(1, remaining.size)
            assertTrue(remaining.single().current)
        }
    }

    @Test
    fun exportNeverContainsCredentialsAndDeleteCascadesAccountSessionsAndSync() {
        AccountBackend.inMemoryForTests().use { backend ->
            val auth = backend.service.register(registration("Alice", null, "Phone"))
            val export = backend.service.exportAccount(auth.accessToken)
            assertEquals("Alice", export.user.username)
            assertEquals(0, export.encryptedSync.version)
            val rendered = export.toString()
            assertFalse(rendered.contains(auth.accessToken))
            assertFalse(rendered.contains(auth.refreshToken))
            assertFalse(rendered.contains(PASSWORD))

            backend.service.deleteAccount(auth.accessToken, DeleteAccountRequest(PASSWORD))
            assertUnauthorized { backend.service.getProfile(auth.accessToken) }
            val login = assertFailsWith<AccountServiceException> {
                backend.service.login(LoginRequest("Alice", PASSWORD, "Phone"))
            }
            assertEquals(AccountProblem.InvalidCredentials, login.problem)
        }
    }

    private fun registration(username: String, invite: String?, device: String) = RegisterRequest(
        username = username,
        password = PASSWORD,
        nickname = username,
        inviteCode = invite,
        deviceName = device,
    )

    private fun assertUnauthorized(block: () -> Unit) {
        val failure = assertFailsWith<AccountServiceException> { block() }
        assertEquals(AccountProblem.Unauthorized, failure.problem)
    }

    private companion object {
        const val PASSWORD = "StrongPassword-2026"
    }
}
