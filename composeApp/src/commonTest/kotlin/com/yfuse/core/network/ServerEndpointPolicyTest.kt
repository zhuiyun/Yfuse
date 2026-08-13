package com.yfuse.core.network

import com.yfuse.core.model.SavedServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerEndpointPolicyTest {
    @Test
    fun retired_host_check_matches_the_parsed_host_only() {
        assertNotNull(server("http://gf.emby.yun:19001").knownUnavailableEndpointReason())
        assertNull(server("http://gf.emby.yun.example.com").knownUnavailableEndpointReason())
        assertNull(server("http://47.112.219.60:19001").knownUnavailableEndpointReason())
    }

    @Test
    fun emby_http_is_allowed_immediately_on_public_and_local_hosts() {
        listOf(
            "http://192.168.1.8:8096",
            "http://10.0.0.8",
            "http://media.local:8096",
            "http://8.8.8.8:8096",
            "http://media.example.com:8096",
        ).forEach { endpoint ->
            val result = validateEmbyServerEndpoint(endpoint)
            assertTrue(result.allowed, endpoint)
            assertEquals(EndpointTransportDecision.Cleartext, result.decision, endpoint)
            assertNull(result.message, endpoint)
        }
    }

    @Test
    fun emby_https_is_allowed_for_public_and_local_hosts() {
        listOf("https://media.example.com", "https://192.168.1.8:8920").forEach { endpoint ->
            val result = validateEmbyServerEndpoint(endpoint)
            assertTrue(result.allowed)
            assertEquals(EndpointTransportDecision.Secure, result.decision)
        }
    }

    @Test
    fun endpoint_rejects_credentials_query_fragment_and_invalid_port() {
        listOf(
            "http://user:password@192.168.1.8:8096",
            "http://192.168.1.8:8096?token=secret",
            "http://192.168.1.8:8096/#fragment",
            "http://192.168.1.8:99999",
        ).forEach { endpoint ->
            assertEquals(
                EndpointTransportDecision.Invalid,
                validateEmbyServerEndpoint(endpoint).decision,
                endpoint,
            )
        }
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
