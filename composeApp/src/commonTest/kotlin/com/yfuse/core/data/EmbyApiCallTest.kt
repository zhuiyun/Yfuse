package com.yfuse.core.data

import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EmbyApiCallTest {
    @Test
    fun a_failure_answered_from_a_cooldown_stays_marked_through_nested_boundaries() =
        runTest {
            val inner =
                embyApiCall<Unit>("inner") {
                    throw EmbyErrorException(EmbyError.Unauthorized, fromCooldown = true)
                }
            val outer = embyApiCall("outer") { inner.getOrThrow() }

            val error = assertIs<EmbyErrorException>(outer.exceptionOrNull())
            assertEquals(EmbyError.Unauthorized, error.error)
            assertTrue(error.fromCooldown)
            val fresh = embyApiCall<Unit>("fresh") { throw EmbyErrorException(EmbyError.Unauthorized) }
            assertFalse(assertIs<EmbyErrorException>(fresh.exceptionOrNull()).fromCooldown)
        }

    @Test
    fun work_called_on_the_ui_thread_moves_to_a_worker_and_other_work_stays_put() =
        runTest {
            val caller = coroutineContext[ContinuationInterceptor]

            val fromUi = offUiThread(onUiThread = true) { currentCoroutineContext()[ContinuationInterceptor] }
            val fromWorker = offUiThread(onUiThread = false) { currentCoroutineContext()[ContinuationInterceptor] }

            assertEquals(Dispatchers.Default, fromUi)
            assertEquals(caller, fromWorker)
        }

    @Test
    fun a_transport_failure_is_named_when_the_engine_says_which_one() =
        runTest {
            assertEquals(EmbyError.Timeout, mappedFrom(SocketTimeoutException("Read timed out")))
            assertEquals(EmbyError.Timeout, mappedFrom(HttpRequestTimeoutException("http://host:8096/Items", 30_000L)))
            // Ktor's connect timeout is a ConnectException on the JVM; it must not read as a closed port.
            assertEquals(EmbyError.Timeout, mappedFrom(ConnectTimeoutException("Connect timeout has expired", null)))
            assertEquals(
                EmbyError.Certificate,
                mappedFrom(SSLHandshakeException("Trust anchor for certification path not found.")),
            )
            assertEquals(EmbyError.Certificate, mappedFrom(SSLPeerUnverifiedException("Hostname host not verified")))
            assertEquals(EmbyError.HostNotFound, mappedFrom(UnknownHostException("Unable to resolve host \"host\"")))
            assertEquals(EmbyError.Network, mappedFrom(IOException("unexpected end of stream")))
        }

    @Test
    fun only_a_refusal_the_system_reported_reads_as_a_closed_port() =
        runTest {
            // OkHttp rewraps the socket's ConnectException, so the errno sits on the cause.
            val refused =
                ConnectException("Failed to connect to /192.168.1.5:8096").apply {
                    initCause(ConnectException("isConnected failed: ECONNREFUSED (Connection refused)"))
                }
            val offline = ConnectException("connect failed: ENETUNREACH (Network is unreachable)")

            assertEquals(EmbyError.ConnectionRefused, mappedFrom(refused))
            assertEquals(EmbyError.Network, mappedFrom(offline))
        }

    @Test
    fun a_tls_read_error_mid_response_is_not_blamed_on_the_certificate() =
        runTest {
            val reset = SSLException("Read error: I/O error during system call, Connection reset by peer")

            assertEquals(EmbyError.Network, mappedFrom(reset))
        }

    private suspend fun mappedFrom(failure: Throwable): EmbyError =
        assertIs<EmbyErrorException>(embyApiCall<Unit>("transport") { throw failure }.exceptionOrNull()).error
}
