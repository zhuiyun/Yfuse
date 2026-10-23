package com.yfuse.core.widget

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetRequestFailuresTest {
    @Test fun only_transient_transport_failures_allow_a_read_retry() {
        assertTrue(SocketException("reset").isRetryableWidgetReadFailure())
        assertTrue(IOException("read failed", EOFException()).isRetryableWidgetReadFailure())
        assertFalse(UnknownHostException("fixture").isRetryableWidgetReadFailure())
        assertFalse(SSLHandshakeException("certificate rejected").isRetryableWidgetReadFailure())
    }

    @Test
    fun okhttp_call_timeouts_and_socket_timeouts_have_the_same_safe_message() {
        for (failure in listOf(SocketTimeoutException("read timed out"), InterruptedIOException("timeout"))) {
            assertEquals("内容服务响应超时，请稍后重试", failure.widgetNetworkMessage())
            assertEquals("TMDB 服务响应超时，请稍后重试", IOException("wrapped", failure).widgetNetworkMessage(tmdb = true))
        }
        assertFalse(InterruptedIOException("interrupted").widgetNetworkMessage().contains("超时"))
        assertFalse(IOException("arbitrary timeout text").widgetNetworkMessage().contains("超时"))
    }
}
