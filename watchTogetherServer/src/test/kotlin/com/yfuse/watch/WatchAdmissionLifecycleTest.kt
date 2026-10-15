package com.yfuse.watch

import com.yfuse.watch.account.AccountExecutionPolicy
import com.yfuse.watch.account.AccountWorkExecutor
import com.yfuse.watch.account.AccountWorkRejectedException
import com.yfuse.watch.account.AuthenticatedAccount
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.testing.testApplication
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchAdmissionLifecycleTest {
    @Test
    fun totalDeadlineCancelsSlowAuthenticatorAndReleasesAdmission() = authenticationDeadlineTest(slowAttempt = true)

    @Test
    fun totalDeadlineAlsoBoundsRetryBackoff() = authenticationDeadlineTest(slowAttempt = false)

    @Test
    fun blockingDatabaseWorkDoesNotHoldAConnectionPastAuthenticationDeadline() =
        testApplication {
            val executor = AccountWorkExecutor(AccountExecutionPolicy(workerThreads = 1))
            val gate = WatchConnectionGate(globalLimit = 1, perIpLimit = 1, perAccountLimit = 1)
            val entered = CountDownLatch(1)
            val finish = CountDownLatch(1)
            application {
                watchTogetherModule(
                    requireWatchAuthentication = true,
                    connectionGate = gate,
                    clientIpResolver = { "test" },
                    watchInitialAuthTimeoutMs = 150,
                    watchAccountAuthenticator = {
                        executor.executeCancellable {
                            entered.countDown()
                            check(finish.await(5, TimeUnit.SECONDS))
                            AuthenticatedAccount("account", "session", "user", "User", 0, Long.MAX_VALUE)
                        }
                    },
                )
            }
            val socketClient = createClient { install(WebSockets) }
            try {
                val session = socketClient.webSocketSession("/watch") { headers.append("Authorization", "Bearer test") }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val reason = withTimeout(2_000) { (session as DefaultWebSocketSession).closeReason.await() }
                assertEquals("account_auth_timeout", reason?.message)
                assertEquals(0, executor.availablePermits())
                withTimeout(2_000) {
                    var lease = gate.tryAcquire("test")
                    while (lease == null) {
                        delay(5)
                        lease = gate.tryAcquire("test")
                    }
                    lease.close()
                }
            } finally {
                finish.countDown()
                executor.close()
            }
        }

    private fun authenticationDeadlineTest(slowAttempt: Boolean) =
        testApplication {
            val gate = WatchConnectionGate(globalLimit = 1, perIpLimit = 1, perAccountLimit = 1)
            val attempts = AtomicInteger()
            val finished = AtomicInteger()
            application {
                watchTogetherModule(
                    requireWatchAuthentication = true,
                    connectionGate = gate,
                    clientIpResolver = { "test" },
                    watchInitialAuthTimeoutMs = 100,
                    watchAuthRetryDelayMs = { 60_000 },
                    watchAccountAuthenticator = {
                        attempts.incrementAndGet()
                        try {
                            if (slowAttempt) awaitCancellation() else throw AccountWorkRejectedException()
                        } finally {
                            finished.incrementAndGet()
                        }
                    },
                )
            }
            val socketClient = createClient { install(WebSockets) }
            val session = socketClient.webSocketSession("/watch") { headers.append("Authorization", "Bearer test") }
            val reason = withTimeout(3_000) { (session as DefaultWebSocketSession).closeReason.await() }
            assertEquals("account_auth_timeout", reason?.message)
            assertEquals(1013, reason?.code?.toInt())
            assertEquals(1, attempts.get())
            assertEquals(1, finished.get())
            withTimeout(3_000) {
                var lease = gate.tryAcquire("test")
                while (lease == null) {
                    delay(5)
                    lease = gate.tryAcquire("test")
                }
                lease.close()
            }
        }

    @Test
    fun rejectedJoinOfKnownRoomDoesNotClearGuessingFailures() =
        testApplication {
            val limiter = WatchJoinFailureLimiter(maxFailures = 2)
            application { watchTogetherModule(joinFailureLimiter = limiter, clientIpResolver = { "test" }) }
            val socketClient = createClient { install(WebSockets) }
            val host = socketClient.webSocketSession("/watch")
            host.send("""{"type":"hello","protocolVersion":5,"clientId":"host","mediaKey":"tmdb:42"}""")
            val welcome = Json.parseToJsonElement((host.incoming.receive() as Frame.Text).readText()).jsonObject
            val code = welcome.getValue("roomCode").jsonPrimitive.content
            assertFalse(limiter.recordFailure("test"))
            val intruder = socketClient.webSocketSession("/watch")
            intruder.send("""{"type":"hello","protocolVersion":5,"clientId":"host","roomCode":"$code"}""")
            val error =
                withTimeout(2_000) {
                    Json.parseToJsonElement((intruder.incoming.receive() as Frame.Text).readText()).jsonObject
                }
            assertEquals("resume_auth_failed", error["errorCode"]?.jsonPrimitive?.content)
            assertTrue(limiter.recordFailure("test"))
            host.close()
            intruder.close()
        }
}
