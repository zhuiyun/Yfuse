package com.yfuse.core.network

import com.yfuse.core.model.SavedServer
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ServerEndpointPolicyTest {
    @Test
    fun retired_host_check_matches_the_parsed_host_only() {
        assertNotNull(server("http://gf.emby.yun:19001").knownUnavailableEndpointReason())
        assertNull(server("http://gf.emby.yun.example.com").knownUnavailableEndpointReason())
        assertNull(server("http://47.112.219.60:19001").knownUnavailableEndpointReason())
    }

    private fun server(baseUrl: String) =
        SavedServer(
            id = SavedServer.idOf(baseUrl, "user"),
            baseUrl = baseUrl,
            serverName = "Emby",
            userId = "user",
            userName = "User",
            accessToken = "token",
        )
}
