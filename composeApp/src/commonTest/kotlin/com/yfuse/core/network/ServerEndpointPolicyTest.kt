package com.yfuse.core.network

import com.yfuse.core.model.SavedServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun embyPublicCleartextIsRejectedEvenAfterRiskConfirmation() {
        val result =
            validateEmbyServerEndpoint(
                "http://media.example.com:8096",
                localCleartextConfirmed = true,
            )

        assertFalse(result.allowed)
        assertEquals(EndpointTransportDecision.PublicCleartextRejected, result.decision)
    }

    @Test
    fun embyLocalCleartextRequiresExplicitConfirmation() {
        listOf(
            "http://192.168.1.8:8096",
            "http://10.0.0.8",
            "http://media.local:8096",
            "http://emby:8096",
            "http://100.64.0.10:8096",
        ).forEach { endpoint ->
            assertTrue(validateEmbyServerEndpoint(endpoint).requiresCleartextConfirmation)
            assertTrue(
                validateEmbyServerEndpoint(
                    endpoint,
                    localCleartextConfirmed = true,
                ).allowed,
            )
        }
    }

    @Test
    fun embyHttpsIsAllowedForPublicAndLocalHosts() {
        assertTrue(validateEmbyServerEndpoint("https://media.example.com").allowed)
        assertTrue(validateEmbyServerEndpoint("https://192.168.1.8:8920").allowed)
    }

    @Test
    fun cleartextClassifierRejectsPublicBoundariesAndHostSpoofing() {
        listOf(
            "http://100.63.255.255:8096",
            "http://100.128.0.1:8096",
            "http://8.8.8.8:8096",
            "http://127.0.0.1.evil.example:8096",
            "http://2130706433:8096",
            "http://0x7f000001:8096",
        ).forEach { endpoint ->
            assertFalse(validateEmbyServerEndpoint(endpoint, true).allowed, endpoint)
        }
    }

    @Test
    fun endpointRejectsCredentialsQueryFragmentAndInvalidPort() {
        listOf(
            "http://user:password@192.168.1.8:8096",
            "http://192.168.1.8:8096?token=secret",
            "http://192.168.1.8:8096/#fragment",
            "http://192.168.1.8:99999",
        ).forEach { endpoint ->
            assertEquals(
                EndpointTransportDecision.Invalid,
                validateEmbyServerEndpoint(endpoint, true).decision,
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
