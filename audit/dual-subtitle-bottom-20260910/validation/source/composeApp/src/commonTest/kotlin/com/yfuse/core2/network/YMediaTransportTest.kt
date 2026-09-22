package com.yfuse.core2.network

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YMediaTransportTest {
    @Test
    fun drm_post_body_and_credentials_never_enter_diagnostics() {
        val request =
            YMediaTransportRequest(
                uri = "https://license.example.test/widevine?token=secret",
                protocol = YSourceProtocol.Https,
                headers = mapOf("Authorization" to "Bearer secret"),
                method = YTransportMethod.Post,
                body = "private-challenge".encodeToByteArray(),
            )

        val diagnostic = request.diagnosticSummary()
        assertTrue("Post" in diagnostic)
        assertFalse("secret" in diagnostic)
        assertFalse("private-challenge" in diagnostic)
        assertFalse("Authorization" in diagnostic)
        assertFalse("private-challenge" in request.toString())
    }

    @Test
    fun post_ranges_and_get_bodies_are_rejected() {
        assertFailsWith<IllegalArgumentException> {
            YMediaTransportRequest(
                uri = "https://example.test",
                protocol = YSourceProtocol.Https,
                body = byteArrayOf(1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            YMediaTransportRequest(
                uri = "https://example.test",
                protocol = YSourceProtocol.Https,
                range = YByteRange(0L, 1L),
                method = YTransportMethod.Post,
            )
        }
    }
}
