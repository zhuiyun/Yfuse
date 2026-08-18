package com.yfuse.feature.profile

import com.yfuse.core.account.AccountDeviceSession
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
