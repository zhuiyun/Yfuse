package com.yfuse.feature.widgets

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetFailureMessageTest {
    @Test
    fun a_java_connection_stack_becomes_a_short_network_hint() {
        val stack =
            "Error: java.net.SocketException: Connection reset\n" +
                "at com.android.org.conscrypt.ConscryptEngineSocket.read(Source.java:191)\n".repeat(200)
        val message = widgetFailureMessage(stack)
        assertTrue("连接" in message)
        assertTrue(message.length < 80)
        assertFalse("java" in message)
        assertFalse("Source" in message)
    }

    @Test
    fun arbitrary_script_errors_cannot_show_headers_urls_or_credentials() {
        val message =
            widgetFailureMessage(
                "Custom failure: https://example.test/feed?token=private-token\nAuthorization: Bearer private-value",
            )
        assertTrue(message.length < 80)
        listOf("example.test", "private-token", "private-value", "Authorization", "https", "\n").forEach {
            assertFalse(it in message)
        }
    }

    @Test
    fun timeout_and_disabled_components_offer_relevant_recovery() {
        assertTrue("重试" in widgetFailureMessage("HTTP request timed out"))
        assertTrue("组件管理" in widgetFailureMessage("组件已停用"))
    }
}
