package com.yfuse.core.network

import android.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalNetworkPermissionTest {
    @Test
    fun android17_and_later_request_local_network_permission() {
        assertEquals(Manifest.permission.ACCESS_LOCAL_NETWORK, localNetworkRuntimePermission(37))
        assertEquals(Manifest.permission.ACCESS_LOCAL_NETWORK, localNetworkRuntimePermission(38))
    }

    @Test
    fun android16_preserves_its_opt_in_bridge() {
        assertEquals(Manifest.permission.NEARBY_WIFI_DEVICES, localNetworkRuntimePermission(36))
    }

    @Test
    fun older_versions_do_not_request_unrelated_nearby_permissions() {
        for (sdk in 26..35) assertNull(localNetworkRuntimePermission(sdk))
    }
}
