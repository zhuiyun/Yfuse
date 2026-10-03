package com.yfuse.core.remote

import com.yfuse.watch.protocol.WatchProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemotePhoneIdentityTest {
    @Test
    fun a_phone_keeps_one_id_that_is_not_its_media_server_id() {
        val install = "2b1f8c3e-5d4a-4c1b-9a77-0e6f5d4c3b2a"
        val id = remoteDeviceIdOf(install)
        assertEquals(id, remoteDeviceIdOf(install), "the same install is the same phone every time")
        assertEquals(32, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(install in id)
        assertNotEquals(id, remoteDeviceIdOf("another-install"))
        // A television may keep it for good, and the relay takes it as a name.
        assertTrue(WatchProtocol.isStableRemoteDeviceId(id))
    }

    @Test
    fun the_model_becomes_a_name_the_relay_takes() {
        assertEquals("Xiaomi 2211133C", remoteDeviceName("Xiaomi 2211133C"))
        assertEquals("HUAWEI ALN-AL00", remoteDeviceName("  HUAWEI\tALN-AL00\n"))
        val long = remoteDeviceName("Manufacturer With A Very Long Marketing Model Name")
        assertEquals(WatchProtocol.MAX_NAME_GRAPHEMES, long?.length)
        assertTrue(WatchProtocol.isValidOptionalName(long))
        assertNull(remoteDeviceName("   "))
        assertNull(remoteDeviceName("\u0007"))
    }
}
