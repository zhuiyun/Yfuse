package com.yfuse.feature.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerBackupPayloadTest {

    @Test
    fun qr_payload_round_trip_preserves_custom_server_name() {
        val backup =
            """{"v":1,"s":[{"b":"https://media.example","n":"客厅影院","u":"u","a":"User","t":"token"}]}"""

        val encoded = encodeQrPayload(backup)

        assertTrue(encoded.startsWith("YFUSE1:"))
        assertEquals(backup, decodeQrPayload(backup))
        assertEquals(backup, decodeQrPayload(encoded))
    }
}
