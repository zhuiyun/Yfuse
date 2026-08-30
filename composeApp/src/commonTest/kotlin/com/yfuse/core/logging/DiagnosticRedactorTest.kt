package com.yfuse.core.logging

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticRedactorTest {
    @Test
    fun redactsSecretsFromCommonLogShapes() {
        val input =
            """
            https://example.test/video?api_key=secret-key&item=42
            https://plex.example/video?X-Plex-Token=plex-secret&item=43
            Authorization: Bearer secret-token
            Authorization=Basic YWRtaW46c2VjcmV0
            {"AccessToken":"server-token","name":"safe"}
            password=hunter2
            refresh_token=refresh-me
            client_secret=client-secret
            Cookie: session=private-session
            Set-Cookie: auth=private-cookie
            https://admin:private@example.test/path
            GET https://private-emby.example:8096/Users/7a2dadae07dc47d1825a0efb60277e8f/Views
            """.trimIndent()

        val redacted = redactDiagnosticText(input)

        assertFalse("secret-key" in redacted)
        assertFalse("plex-secret" in redacted)
        assertFalse("secret-token" in redacted)
        assertFalse("server-token" in redacted)
        assertFalse("YWRtaW46c2VjcmV0" in redacted)
        assertFalse("hunter2" in redacted)
        assertFalse("refresh-me" in redacted)
        assertFalse("client-secret" in redacted)
        assertFalse("private-session" in redacted)
        assertFalse("private-cookie" in redacted)
        assertFalse("admin:private" in redacted)
        assertFalse("private-emby.example" in redacted)
        assertFalse("7a2dadae07dc47d1825a0efb60277e8f" in redacted)
        assertTrue("https://<redacted-host>/Users/<redacted>/Views" in redacted)
        assertTrue(redacted.count { it == '<' } >= 5)
        assertTrue("item=42" in redacted)
        assertTrue("\"name\":\"safe\"" in redacted)
    }

    @Test
    fun redactsSensitiveAttributeByKey() {
        val redacted =
            redactDiagnosticAttributes(
                mapOf(
                    "accessToken" to "secret",
                    "set-cookie" to "private-cookie",
                    "serverId" to "https://private.example#user-id",
                    "operation" to "load_detail",
                ),
            )

        assertFalse(redacted.getValue("accessToken").contains("secret"))
        assertFalse(redacted.getValue("set-cookie").contains("private-cookie"))
        assertFalse(redacted.getValue("serverId").contains("private.example"))
        assertTrue(redacted.getValue("operation") == "load_detail")
    }

    @Test
    fun redacts_server_identity_and_cloudflare_details_outside_urls() {
        val input =
            """
            GET /Items/0123456789abcdef?UserId=user-private&DeviceId=device-private
            host=media.private-example.test zone private-zone.example
            Ray-ID: 8f0123456789abcd
            origin IPv4 192.0.2.42 and IPv6 2001:db8:85a3::8a2e:370:7334
            Cloudflare could not reach private-origin.example
            """.trimIndent()

        val redacted = redactDiagnosticText(input)

        assertFalse("0123456789abcdef" in redacted)
        assertFalse("user-private" in redacted)
        assertFalse("device-private" in redacted)
        assertFalse("media.private-example.test" in redacted)
        assertFalse("private-zone.example" in redacted)
        assertFalse("8f0123456789abcd" in redacted)
        assertFalse("192.0.2.42" in redacted)
        assertFalse("2001:db8:85a3::8a2e:370:7334" in redacted)
        assertFalse("private-origin.example" in redacted)
        assertTrue("Cloudflare could not reach <redacted-host>" in redacted)
    }

    @Test
    fun logcat_payload_redacts_message_attributes_and_stack_trace() {
        val payload =
            formatSafeLogcatMessage(
                event = "request_failed",
                message = "GET https://example.test/image?api_key=message-secret",
                attributes =
                    mapOf(
                        "accessToken" to "attribute-secret",
                        "operation" to "Authorization: Bearer nested-secret",
                    ),
                throwableText = "IllegalStateException: password=stack-secret",
            )

        assertFalse("message-secret" in payload)
        assertFalse("attribute-secret" in payload)
        assertFalse("nested-secret" in payload)
        assertFalse("stack-secret" in payload)
        assertTrue("request_failed" in payload)
        assertTrue("<redacted>" in payload)
    }
}
