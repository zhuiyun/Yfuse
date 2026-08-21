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
