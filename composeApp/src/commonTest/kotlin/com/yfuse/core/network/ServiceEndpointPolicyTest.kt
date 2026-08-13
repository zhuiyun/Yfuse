package com.yfuse.core.network

import com.yfuse.core.data.WatchTogetherPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceEndpointPolicyTest {
    @Test
    fun officialWatchServiceDefaultsToSecureTransport() {
        val result = validateServiceEndpoint(WatchTogetherPreferences.DEFAULT_ENDPOINT)

        assertTrue(result.allowed)
        assertEquals(EndpointTransportDecision.Secure, result.decision)
    }

    @Test
    fun securePublicHttpAndWebSocketEndpointsAreAccepted() {
        listOf("https://watch.example.com", "wss://watch.example.com/socket").forEach { value ->
            val result = validateServiceEndpoint(value)
            assertTrue(result.allowed)
            assertEquals(EndpointTransportDecision.Secure, result.decision)
        }
    }

    @Test
    fun publicCleartextIsRejectedEvenAfterConfirmation() {
        listOf(
            "http://watch.example.com",
            "ws://203.0.113.10/socket",
            "http://fc.example.com",
        ).forEach { value ->
            val result = validateServiceEndpoint(value, localCleartextConfirmed = true)
            assertFalse(result.allowed)
            assertEquals(EndpointTransportDecision.PublicCleartextRejected, result.decision)
        }
    }

    @Test
    fun localCleartextRequiresAnExplicitConfirmation() {
        listOf(
            "http://localhost:8080",
            "ws://192.168.1.20:8080/socket",
            "http://10.0.0.8",
            "http://100.64.0.10:8080",
            "ws://media-server.local:8080",
            "http://[::1]:8080",
        ).forEach { value ->
            val pending = validateServiceEndpoint(value)
            assertFalse(pending.allowed)
            assertTrue(pending.requiresCleartextConfirmation)

            val confirmed = validateServiceEndpoint(value, localCleartextConfirmed = true)
            assertTrue(confirmed.allowed)
            assertEquals(EndpointTransportDecision.LocalCleartextConfirmed, confirmed.decision)
        }
    }

    @Test
    fun incompleteOrUnsupportedAddressesAreInvalid() {
        listOf("watch.example.com", "ftp://watch.example.com", "https://").forEach { value ->
            assertEquals(
                EndpointTransportDecision.Invalid,
                validateServiceEndpoint(value).decision,
            )
        }
    }
}
