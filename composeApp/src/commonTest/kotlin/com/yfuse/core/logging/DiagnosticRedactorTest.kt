package com.yfuse.core.logging

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticRedactorTest {
    @Test
    fun redactsSecretsFromCommonLogShapes() {
        val input = """
            https://example.test/video?api_key=secret-key&item=42
            Authorization: Bearer secret-token
            Authorization=Basic YWRtaW46c2VjcmV0
            {"AccessToken":"server-token","name":"safe"}
            password=hunter2
            https://admin:private@example.test/path
        """.trimIndent()

        val redacted = redactDiagnosticText(input)

        assertFalse("secret-key" in redacted)
        assertFalse("secret-token" in redacted)
        assertFalse("server-token" in redacted)
        assertFalse("YWRtaW46c2VjcmV0" in redacted)
        assertFalse("hunter2" in redacted)
        assertFalse("admin:private" in redacted)
        assertTrue(redacted.count { it == '<' } >= 5)
        assertTrue("item=42" in redacted)
        assertTrue("\"name\":\"safe\"" in redacted)
    }

    @Test
    fun redactsSensitiveAttributeByKey() {
        val redacted = redactDiagnosticAttributes(
            mapOf(
                "accessToken" to "secret",
                "operation" to "load_detail",
            ),
        )

        assertFalse(redacted.getValue("accessToken").contains("secret"))
        assertTrue(redacted.getValue("operation") == "load_detail")
    }
}
