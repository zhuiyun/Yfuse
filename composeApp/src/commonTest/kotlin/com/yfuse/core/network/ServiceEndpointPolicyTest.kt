package com.yfuse.core.network

import com.yfuse.core.data.WatchTogetherPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceEndpointPolicyTest {
    @Test
    fun official_watch_service_default_is_valid() {
        val result = validateServiceEndpoint(WatchTogetherPreferences.DEFAULT_ENDPOINT)
        assertTrue(result.allowed)
        assertEquals(EndpointTransportDecision.Secure, result.decision)
    }

    @Test
    fun secure_public_endpoints_are_accepted() {
        listOf("https://watch.example.com", "wss://watch.example.com/socket").forEach { value ->
            val result = validateServiceEndpoint(value)
            assertTrue(result.allowed)
            assertEquals(EndpointTransportDecision.Secure, result.decision)
        }
    }

    @Test
    fun cleartext_public_and_local_endpoints_are_accepted_without_confirmation() {
        listOf(
            "http://watch.example.com",
            "ws://203.0.113.10/socket",
            "http://localhost:8080",
            "ws://192.168.1.20:8080/socket",
        ).forEach { value ->
            val result = validateServiceEndpoint(value)
            assertTrue(result.allowed, value)
            assertEquals(EndpointTransportDecision.Cleartext, result.decision, value)
            assertNull(result.message, value)
        }
    }

    @Test
    fun incomplete_or_unsupported_addresses_are_invalid() {
        listOf("watch.example.com", "ftp://watch.example.com", "https://").forEach { value ->
            assertEquals(EndpointTransportDecision.Invalid, validateServiceEndpoint(value).decision)
        }
    }
}
