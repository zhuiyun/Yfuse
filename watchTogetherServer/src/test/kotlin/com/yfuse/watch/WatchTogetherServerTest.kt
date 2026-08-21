package com.yfuse.watch

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WatchTogetherServerTest {
    @Test
    fun productionWatchGateRejectsMissingAccountBearerBeforeRoomAccess() =
        testApplication {
            application { watchTogetherModule(requireWatchAuthentication = true) }
            val socketClient =
                createClient {
                    install(WebSockets)
                }
            val session = socketClient.webSocketSession("/watch")
            val reason = withTimeout(2_000L) { (session as DefaultWebSocketSession).closeReason.await() }
            assertEquals(1008, reason?.code?.toInt())
            assertEquals("account_auth_required", reason?.message)
        }

    @Test
    fun productionWatchGateRejectsPlaintextBeforeReadingAccountBearer() =
        testApplication {
            application {
                watchTogetherModule(
                    requireWatchAuthentication = true,
                    trustProxyHeaders = true,
                )
            }
            val socketClient =
                createClient {
                    install(WebSockets)
                }
            val session =
                socketClient.webSocketSession("/watch") {
                    headers.append(HttpHeaders.Authorization, "Bearer must-not-be-read")
                    headers.append("X-Forwarded-Proto", "http")
                }
            val reason =
                withTimeout(2_000L) {
                    (session as DefaultWebSocketSession).closeReason.await()
                }

            assertEquals(1008, reason?.code?.toInt())
            assertEquals("secure_transport_required", reason?.message)
        }

    @Test
    fun connectionGateEnforcesGlobalIpAndAccountLimitsAndReleasesIdempotently() {
        val gate = WatchConnectionGate(globalLimit = 2, perIpLimit = 1, perAccountLimit = 1)
        val first = checkNotNull(gate.tryAcquire("198.51.100.1"))
        assertTrue(gate.tryAcquire("198.51.100.1") == null)
        val second = checkNotNull(gate.tryAcquire("198.51.100.2"))
        assertTrue(gate.tryAcquire("198.51.100.3") == null)

        assertTrue(first.tryBindAccount("account-a"))
        assertFalse(second.tryBindAccount("account-a"))
        first.close()
        first.close()
        assertTrue(second.tryBindAccount("account-a"))
        second.close()

        val replacement = checkNotNull(gate.tryAcquire("198.51.100.1"))
        assertTrue(replacement.tryBindAccount("account-a"))
        replacement.close()
    }

    @Test
    fun rejectedHandshakeReleasesItsConnectionAdmissionLease() =
        testApplication {
            val gate = WatchConnectionGate(globalLimit = 1, perIpLimit = 1, perAccountLimit = 1)
            val backend =
                com.yfuse.watch.account.AccountBackend
                    .inMemoryForTests()
            val registered =
                backend.execute {
                    register(
                        com.yfuse.watch.account.RegisterRequest(
                            username = "admission-watcher",
                            password = "Watch-Test-42",
                        ),
                    )
                }
            application {
                watchTogetherModule(
                    accountBackend = backend,
                    requireWatchAuthentication = true,
                    maxWatchConnections = 1,
                    maxWatchConnectionsPerIp = 1,
                    maxWatchConnectionsPerAccount = 1,
                    connectionGate = gate,
                )
            }
            val socketClient = createClient { install(WebSockets) }
            val rejected = socketClient.webSocketSession("/watch")
            val rejectedReason =
                withTimeout(2_000L) {
                    (rejected as DefaultWebSocketSession).closeReason.await()
                }
            assertEquals("account_auth_required", rejectedReason?.message)

            withTimeout(2_000L) {
                while (true) {
                    val probe = gate.tryAcquire("probe")
                    if (probe != null) {
                        probe.close()
                        break
                    }
                    delay(5L)
                }
            }

            val accepted =
                socketClient.webSocketSession("/watch") {
                    headers.append(HttpHeaders.Authorization, "Bearer ${registered.accessToken}")
                }
            accepted.send(
                """{"type":"hello","protocolVersion":5,"clientId":"admitted","mediaKey":"tmdb:45"}""",
            )
            val welcome =
                withTimeout(2_000L) {
                    (accepted.incoming.receive() as Frame.Text).readText().asJson()
                }
            assertEquals("welcome", welcome["type"]?.jsonPrimitive?.content)
            accepted.close()
        }

    @Test
    fun silentWatchSocketIsClosedAfterItsAccountSessionIsRevoked() =
        testApplication {
            val backend =
                com.yfuse.watch.account.AccountBackend
                    .inMemoryForTests()
            application {
                watchTogetherModule(
                    accountBackend = backend,
                    requireWatchAuthentication = true,
                    watchAuthRevalidationMs = 25L,
                )
            }
            val registered =
                client
                    .post("/api/v1/auth/register") {
                        header("X-Forwarded-Proto", "https")
                        contentType(ContentType.Application.Json)
                        setBody("""{"username":"watcher","password":"Watch-Test-42"}""")
                    }.bodyAsText()
                    .asJson()
            val accessToken = registered.getValue("accessToken").jsonPrimitive.content
            val socketClient =
                createClient {
                    install(WebSockets)
                }
            val session =
                socketClient.webSocketSession("/watch") {
                    headers.append(HttpHeaders.Authorization, "Bearer $accessToken")
                }
            session.send(
                """{"type":"hello","protocolVersion":5,"clientId":"watcher","mediaKey":"tmdb:42"}""",
            )
            val welcome = (session.incoming.receive() as Frame.Text).readText().asJson()
            assertEquals("welcome", welcome["type"]?.jsonPrimitive?.content)

            backend.execute { logout(accessToken) }

            val reason =
                withTimeout(2_000L) {
                    (session as DefaultWebSocketSession).closeReason.await()
                }
            assertEquals(1008, reason?.code?.toInt())
            assertEquals("account_auth_expired", reason?.message)
        }

    @Test
    fun transientWatchAuthenticationOverloadRetriesAndKeepsTheSocketUsable() =
        testApplication {
            val executor =
                com.yfuse.watch.account.AccountWorkExecutor(
                    com.yfuse.watch.account.AccountExecutionPolicy(
                        workerThreads = 1,
                        maxConcurrentOperations = 1,
                    ),
                )
            val backend =
                com.yfuse.watch.account.AccountBackend.inMemoryForTests(
                    workExecutor = executor,
                )
            val registered =
                backend.execute {
                    register(
                        com.yfuse.watch.account.RegisterRequest(
                            username = "overloaded-watcher",
                            password = "Watch-Test-42",
                        ),
                    )
                }
            val attempts = AtomicInteger()
            val overloadedAttempts = AtomicInteger()
            val blockingWorkEntered = CompletableDeferred<Unit>()
            val releaseBlockingWork = CountDownLatch(1)
            val blockingWork =
                CoroutineScope(currentCoroutineContext()).async {
                    executor.execute {
                        blockingWorkEntered.complete(Unit)
                        check(releaseBlockingWork.await(5, TimeUnit.SECONDS))
                    }
                }
            withTimeout(2_000L) { blockingWorkEntered.await() }
            application {
                watchTogetherModule(
                    accountBackend = backend,
                    requireWatchAuthentication = true,
                    watchAuthRevalidationMs = 25L,
                    watchAccountAuthenticator = { token ->
                        attempts.incrementAndGet()
                        try {
                            backend.authenticateAccessToken(token)
                        } catch (failure: com.yfuse.watch.account.AccountWorkRejectedException) {
                            overloadedAttempts.incrementAndGet()
                            throw failure
                        }
                    },
                    watchAuthRetryDelayMs = { 1L },
                )
            }
            val socketClient =
                createClient {
                    install(WebSockets)
                }
            val session =
                socketClient.webSocketSession("/watch") {
                    headers.append(HttpHeaders.Authorization, "Bearer ${registered.accessToken}")
                }
            session.send(
                """{"type":"hello","protocolVersion":5,"clientId":"overloaded","mediaKey":"tmdb:43"}""",
            )
            try {
                withTimeout(2_000L) {
                    while (overloadedAttempts.get() < 2) delay(5L)
                }
                assertFalse((session as DefaultWebSocketSession).closeReason.isCompleted)
                releaseBlockingWork.countDown()
                val welcome =
                    withTimeout(2_000L) {
                        (session.incoming.receive() as Frame.Text).readText().asJson()
                    }

                assertEquals("welcome", welcome["type"]?.jsonPrimitive?.content)
                assertTrue(attempts.get() >= 3)
                assertTrue(overloadedAttempts.get() >= 2)
                assertFalse(session.closeReason.isCompleted)
                session.close()
            } finally {
                releaseBlockingWork.countDown()
                blockingWork.await()
            }
        }

    @Test
    fun sustainedInitialAuthenticationOverloadEndsWithRetryableClose() =
        testApplication {
            val backend =
                com.yfuse.watch.account.AccountBackend
                    .inMemoryForTests()
            val attempts = AtomicInteger()
            application {
                watchTogetherModule(
                    accountBackend = backend,
                    requireWatchAuthentication = true,
                    watchAccountAuthenticator = {
                        attempts.incrementAndGet()
                        throw com.yfuse.watch.account
                            .AccountWorkRejectedException()
                    },
                    watchAuthRetryDelayMs = { 1L },
                )
            }
            val socketClient =
                createClient {
                    install(WebSockets)
                }
            val session =
                socketClient.webSocketSession("/watch") {
                    headers.append(HttpHeaders.Authorization, "Bearer temporarily-overloaded")
                }

            val reason =
                withTimeout(2_000L) {
                    (session as DefaultWebSocketSession).closeReason.await()
                }
            assertEquals(1013, reason?.code?.toInt())
            assertEquals("account_auth_temporarily_unavailable", reason?.message)
            assertEquals(8, attempts.get())
        }

    @Test
    fun unexpectedInitialAuthenticationFailureIsNotReportedAsAccountExpiry() =
        testApplication {
            val backend =
                com.yfuse.watch.account.AccountBackend
                    .inMemoryForTests()
            application {
                watchTogetherModule(
                    accountBackend = backend,
                    requireWatchAuthentication = true,
                    watchAccountAuthenticator = {
                        error("database unavailable")
                    },
                )
            }
            val socketClient =
                createClient {
                    install(WebSockets)
                }
            val session =
                socketClient.webSocketSession("/watch") {
                    headers.append(HttpHeaders.Authorization, "Bearer unavailable")
                }

            val reason =
                withTimeout(2_000L) {
                    (session as DefaultWebSocketSession).closeReason.await()
                }
            assertEquals(1011, reason?.code?.toInt())
            assertEquals("account_auth_unavailable", reason?.message)
        }

    @Test
    fun watchdogRetriesTransientOverloadThenStillDetectsRevocation() =
        testApplication {
            val backend =
                com.yfuse.watch.account.AccountBackend
                    .inMemoryForTests()
            val registered =
                backend.execute {
                    register(
                        com.yfuse.watch.account.RegisterRequest(
                            username = "revoked-after-overload",
                            password = "Watch-Test-42",
                        ),
                    )
                }
            val attempts = AtomicInteger()
            val authenticator: suspend (String) -> com.yfuse.watch.account.AuthenticatedAccount =
                { token ->
                    val attempt = attempts.incrementAndGet()
                    if (attempt in 2..5) {
                        throw com.yfuse.watch.account
                            .AccountWorkRejectedException()
                    }
                    backend.validateAccessToken(token)
                }
            application {
                watchTogetherModule(
                    accountBackend = backend,
                    requireWatchAuthentication = true,
                    watchAuthRevalidationMs = 25L,
                    watchAccountAuthenticator = authenticator,
                    watchAccountRevalidator = authenticator,
                    watchAuthRetryDelayMs = { 1L },
                )
            }
            val socketClient =
                createClient {
                    install(WebSockets)
                }
            val session =
                socketClient.webSocketSession("/watch") {
                    headers.append(HttpHeaders.Authorization, "Bearer ${registered.accessToken}")
                }
            session.send(
                """{"type":"hello","protocolVersion":5,"clientId":"revoked","mediaKey":"tmdb:44"}""",
            )
            val welcome = (session.incoming.receive() as Frame.Text).readText().asJson()
            assertEquals("welcome", welcome["type"]?.jsonPrimitive?.content)

            withTimeout(2_000L) {
                while (attempts.get() < 5) delay(5L)
            }
            assertFalse((session as DefaultWebSocketSession).closeReason.isCompleted)
            backend.execute { logout(registered.accessToken) }

            val reason = withTimeout(2_000L) { session.closeReason.await() }
            assertEquals(1008, reason?.code?.toInt())
            assertEquals("account_auth_expired", reason?.message)
        }

    @Test
    fun watchdogDoesNotRetryTransientFailurePastTheAccessExpiry() =
        testApplication {
            val backend =
                com.yfuse.watch.account.AccountBackend
                    .inMemoryForTests()
            var nowEpochMs = 1_000L
            val attempts = AtomicInteger()
            val authenticator: suspend (String) -> com.yfuse.watch.account.AuthenticatedAccount =
                {
                    val attempt = attempts.incrementAndGet()
                    if (attempt == 1) {
                        com.yfuse.watch.account.AuthenticatedAccount(
                            userId = "expiring-user",
                            sessionId = "expiring-session",
                            username = "expiring",
                            nickname = "Expiring",
                            avatarId = 0,
                            accessExpiresAtEpochMs = 1_005L,
                        )
                    } else {
                        throw com.yfuse.watch.account
                            .AccountWorkRejectedException()
                    }
                }
            application {
                watchTogetherModule(
                    accountBackend = backend,
                    requireWatchAuthentication = true,
                    watchAuthRevalidationMs = 25L,
                    watchAccountAuthenticator = authenticator,
                    watchAccountRevalidator = authenticator,
                    watchAuthRetryDelayMs = { 1L },
                    watchAuthClock = {
                        nowEpochMs++
                    },
                )
            }
            val socketClient =
                createClient {
                    install(WebSockets)
                }
            val session =
                socketClient.webSocketSession("/watch") {
                    headers.append(HttpHeaders.Authorization, "Bearer expiring-token")
                }

            val reason =
                withTimeout(2_000L) {
                    (session as DefaultWebSocketSession).closeReason.await()
                }
            assertEquals(1008, reason?.code?.toInt())
            assertEquals("account_auth_expired", reason?.message)
            // The watchdog may make one last revalidation while time remains; it must not schedule
            // another retry after the injected clock reaches the token's hard expiry.
            assertTrue(attempts.get() <= 3)
        }

    @Test
    fun watchAuthenticationRetryDelayIsCappedAndJittered() {
        repeat(100) {
            assertTrue(watchAuthTransientRetryDelayMs(1) in 50L..100L)
            assertTrue(watchAuthTransientRetryDelayMs(7) in 2_500L..5_000L)
            assertTrue(watchAuthTransientRetryDelayMs(100) in 2_500L..5_000L)
        }
    }

    @Test
    fun health_and_update_files_share_the_watch_server() {
        val updateRoot = Files.createTempDirectory("yfuse-watch-test").toFile()
        try {
            updateRoot.resolve("update.json").writeText("""{"versionCode":29}""")
            testApplication {
                application { watchTogetherModule(updateRoot) }

                val health = client.get("/health")
                assertEquals(HttpStatusCode.OK, health.status)
                assertEquals("ok", health.bodyAsText())

                val protocol = client.get("/watch/version")
                assertEquals(HttpStatusCode.OK, protocol.status)
                val advertised = protocol.bodyAsText().asJson()
                assertEquals(6, advertised["protocolVersion"]!!.jsonPrimitive.int)
                assertEquals(5, advertised["minProtocolVersion"]!!.jsonPrimitive.int)

                val update = client.get("/yfuse/update.json")
                assertEquals(HttpStatusCode.OK, update.status)
                assertEquals("""{"versionCode":29}""", update.bodyAsText())
            }
        } finally {
            updateRoot.deleteRecursively()
        }
    }

    @Test
    fun host_sync_is_broadcast_to_joined_guest() =
        testApplication {
            application { watchTogetherModule() }
            val socketClient =
                createClient {
                    install(WebSockets)
                }
            val roomCode = CompletableDeferred<String>()
            val guestJoined = CompletableDeferred<Unit>()
            val received = CompletableDeferred<String>()
            val testScope = CoroutineScope(currentCoroutineContext())

            val host =
                testScope.launch {
                    socketClient.webSocket("/watch") {
                        send(
                            """{"type":"hello","protocolVersion":5,"clientId":"host","name":"Host","mediaKey":"tmdb:42"}""",
                        )
                        val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                        assertEquals("welcome", welcome["type"]?.jsonPrimitive?.content)
                        assertTrue(welcome["isHost"]!!.jsonPrimitive.boolean)
                        roomCode.complete(welcome["roomCode"]!!.jsonPrimitive.content)

                        guestJoined.await()
                        send(
                            """{"type":"sync","mediaKey":"tmdb:42","positionMs":45678,"paused":false,"rate":1.0}""",
                        )
                        received.await()
                    }
                }
            val guest =
                testScope.launch {
                    val code = roomCode.await()
                    socketClient.webSocket("/watch") {
                        send(
                            """{"type":"hello","protocolVersion":5,"roomCode":"$code","clientId":"guest","name":"Guest"}""",
                        )
                        while (true) {
                            val payload = (incoming.receive() as Frame.Text).readText().asJson()
                            when (payload["type"]?.jsonPrimitive?.content) {
                                "welcome" -> {
                                    assertFalse(payload["isHost"]!!.jsonPrimitive.boolean)
                                    guestJoined.complete(Unit)
                                }
                                "sync" -> {
                                    received.complete(payload.toString())
                                    break
                                }
                            }
                        }
                    }
                }

            val payload =
                Json
                    .parseToJsonElement(
                        withTimeout(5_000L) { received.await() },
                    ).jsonObject
            assertEquals(45_678L, payload["positionMs"]?.jsonPrimitive?.long)
            assertFalse(payload["paused"]!!.jsonPrimitive.boolean)
            assertEquals(1, payload["seq"]?.jsonPrimitive?.int)
            assertTrue(payload["anchorAtMs"]?.jsonPrimitive?.long != null)

            host.cancelAndJoin()
            guest.cancelAndJoin()
        }

    @Test
    fun guest_sync_is_rejected() =
        testApplication {
            application { watchTogetherModule() }
            val socketClient =
                createClient {
                    install(WebSockets)
                }
            val roomCode = CompletableDeferred<String>()
            val guestReady = CompletableDeferred<Unit>()
            val errorReceived = CompletableDeferred<String>()
            val testScope = CoroutineScope(currentCoroutineContext())

            val host =
                testScope.launch {
                    socketClient.webSocket("/watch") {
                        send("""{"type":"hello","protocolVersion":5,"clientId":"host","mediaKey":"tmdb:1"}""")
                        val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                        roomCode.complete(welcome["roomCode"]!!.jsonPrimitive.content)
                        errorReceived.await()
                    }
                }
            val guest =
                testScope.launch {
                    val code = roomCode.await()
                    socketClient.webSocket("/watch") {
                        send("""{"type":"hello","protocolVersion":5,"roomCode":"$code","clientId":"guest"}""")
                        incoming.receive() // welcome
                        guestReady.complete(Unit)
                        send("""{"type":"sync","mediaKey":"tmdb:1","positionMs":1,"paused":false,"rate":1.0}""")
                        // A `roomUpdate` broadcast (triggered by this guest's own join) can land
                        // before the rejection — drain past it.
                        var reply = (incoming.receive() as Frame.Text).readText().asJson()
                        while (reply["type"]?.jsonPrimitive?.content != "error") {
                            reply = (incoming.receive() as Frame.Text).readText().asJson()
                        }
                        errorReceived.complete(reply["message"]!!.jsonPrimitive.content)
                    }
                }

            val message = withTimeout(5_000L) { errorReceived.await() }
            assertTrue(message.contains("控制权限"))
            host.cancelAndJoin()
            guest.cancelAndJoin()
        }

    @Test
    fun disconnected_host_is_replaced_when_a_guest_remains() =
        testApplication {
            application { watchTogetherModule(hostGraceMs = 25L) }
            val socketClient =
                createClient {
                    install(WebSockets)
                }
            val roomCode = CompletableDeferred<String>()
            val guestJoined = CompletableDeferred<Unit>()
            val guestPromoted = CompletableDeferred<Boolean>()
            val testScope = CoroutineScope(currentCoroutineContext())

            val host =
                testScope.launch {
                    socketClient.webSocket("/watch") {
                        send("""{"type":"hello","protocolVersion":5,"clientId":"host","mediaKey":"tmdb:7"}""")
                        val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                        roomCode.complete(welcome["roomCode"]!!.jsonPrimitive.content)
                        guestJoined.await()
                        // Falling off the end here closes the connection — same as a network
                        // drop. The guest was already present, so the server should hand host off
                        // after the deliberately short reconnect grace configured for this test.
                    }
                }

            val guest =
                testScope.launch {
                    val code = roomCode.await()
                    socketClient.webSocket("/watch") {
                        send("""{"type":"hello","protocolVersion":5,"roomCode":"$code","clientId":"guest"}""")
                        val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                        assertFalse(welcome["isHost"]!!.jsonPrimitive.boolean)
                        guestJoined.complete(Unit)
                        while (true) {
                            val payload = (incoming.receive() as Frame.Text).readText().asJson()
                            if (payload["type"]?.jsonPrimitive?.content == "roomUpdate" &&
                                payload["isHost"]?.jsonPrimitive?.boolean == true
                            ) {
                                guestPromoted.complete(true)
                                break
                            }
                        }
                    }
                }

            assertTrue(withTimeout(5_000L) { guestPromoted.await() })
            host.cancelAndJoin()
            guest.cancelAndJoin()
        }

    @Test
    fun reconnect_with_same_client_id_resumes_as_host_without_a_new_room() =
        testApplication {
            application { watchTogetherModule() }
            val socketClient =
                createClient {
                    install(WebSockets)
                }
            val roomCode = CompletableDeferred<String>()
            var resumeCapability = ""
            var hostCapability = ""

            socketClient.webSocket("/watch") {
                send("""{"type":"hello","protocolVersion":5,"clientId":"host","mediaKey":"tmdb:9"}""")
                val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                roomCode.complete(welcome["roomCode"]!!.jsonPrimitive.content)
                resumeCapability = welcome["resumeCapability"]!!.jsonPrimitive.content
                hostCapability = welcome["hostCapability"]!!.jsonPrimitive.content
                // Close without an explicit leave message — same as a network drop.
            }

            val code = roomCode.await()
            socketClient.webSocket("/watch") {
                send(
                    """{"type":"hello","protocolVersion":5,"roomCode":"$code","clientId":"host","resumeCapability":"$resumeCapability","hostCapability":"$hostCapability"}""",
                )
                val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                assertEquals(code, welcome["roomCode"]?.jsonPrimitive?.content)
                assertTrue(welcome["isHost"]!!.jsonPrimitive.boolean)
                assertNotEquals("null", welcome["mediaKey"].toString())
            }
        }

    @Test
    fun public_client_id_cannot_replace_connected_host_without_private_capabilities() =
        testApplication {
            application { watchTogetherModule() }
            val socketClient =
                createClient {
                    install(WebSockets)
                }
            val roomCode = CompletableDeferred<String>()
            val attackerRejected = CompletableDeferred<Unit>()
            val hostStillControls = CompletableDeferred<Unit>()
            val testScope = CoroutineScope(currentCoroutineContext())

            val host =
                testScope.launch {
                    socketClient.webSocket("/watch") {
                        send(
                            """{"type":"hello","protocolVersion":5,"clientId":"public-host","mediaKey":"tmdb:901"}""",
                        )
                        val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                        roomCode.complete(welcome["roomCode"]!!.jsonPrimitive.content)
                        attackerRejected.await()
                        send(
                            """{"type":"sync","mediaKey":"tmdb:901","positionMs":42,"paused":false,"rate":1.0}""",
                        )
                        while (!hostStillControls.isCompleted) {
                            val payload = (incoming.receive() as Frame.Text).readText().asJson()
                            if (payload["type"]?.jsonPrimitive?.content == "sync" &&
                                payload["positionMs"]?.jsonPrimitive?.long == 42L
                            ) {
                                hostStillControls.complete(Unit)
                            }
                        }
                    }
                }

            val attacker =
                testScope.launch {
                    socketClient.webSocket("/watch") {
                        send(
                            """{"type":"hello","protocolVersion":5,"roomCode":"${roomCode.await()}","clientId":"public-host"}""",
                        )
                        val error = (incoming.receive() as Frame.Text).readText().asJson()
                        assertEquals("resume_auth_failed", error["errorCode"]?.jsonPrimitive?.content)
                        attackerRejected.complete(Unit)
                    }
                }

            withTimeout(5_000L) { hostStillControls.await() }
            host.cancelAndJoin()
            attacker.cancelAndJoin()
        }

    @Test
    fun invalid_timeline_frames_are_rejected_without_advancing_room_state() =
        testApplication {
            application { watchTogetherModule() }
            val socketClient =
                createClient {
                    install(WebSockets)
                }

            socketClient.webSocket("/watch") {
                send(
                    """{"type":"hello","protocolVersion":5,"clientId":"host","mediaKey":"tmdb:902"}""",
                )
                incoming.receive() // welcome
                incoming.receive() // own roomUpdate

                val invalidFrames =
                    listOf(
                        """{"type":"sync","positionMs":-1,"paused":false,"rate":1.0}""",
                        """{"type":"sync","positionMs":1,"paused":false,"rate":1e30}""",
                        """{"type":"sync","mediaKey":"tmdb:bad key","positionMs":1,"paused":false,"rate":1.0}""",
                        """{"type":"sync","positionMs":1,"paused":false}""",
                        """{"type":"sync","positionMs":1,"paused":false,"rate":1.0,"seq":99}""",
                    )
                invalidFrames.forEach { frame ->
                    send(frame)
                    val error = (incoming.receive() as Frame.Text).readText().asJson()
                    assertEquals("timeline_invalid", error["errorCode"]?.jsonPrimitive?.content)
                }

                send(
                    """{"type":"sync","mediaKey":"tmdb:902","positionMs":7,"paused":true,"rate":1.0}""",
                )
                val sync = (incoming.receive() as Frame.Text).readText().asJson()
                assertEquals("sync", sync["type"]?.jsonPrimitive?.content)
                assertEquals(1L, sync["seq"]?.jsonPrimitive?.long)
                assertEquals(7L, sync["positionMs"]?.jsonPrimitive?.long)
            }
        }

    @Test
    fun host_reconnect_requires_host_capability_as_well_as_resume_capability() =
        testApplication {
            application { watchTogetherModule() }
            val socketClient =
                createClient {
                    install(WebSockets)
                }
            var code = ""
            var resumeCapability = ""
            var hostCapability = ""

            socketClient.webSocket("/watch") {
                send(
                    """{"type":"hello","protocolVersion":5,"clientId":"host","mediaKey":"tmdb:903"}""",
                )
                val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                code = welcome["roomCode"]!!.jsonPrimitive.content
                resumeCapability = welcome["resumeCapability"]!!.jsonPrimitive.content
                hostCapability = welcome["hostCapability"]!!.jsonPrimitive.content
            }

            socketClient.webSocket("/watch") {
                send(
                    """{"type":"hello","protocolVersion":5,"roomCode":"$code","clientId":"host","resumeCapability":"$resumeCapability"}""",
                )
                val denied = (incoming.receive() as Frame.Text).readText().asJson()
                assertEquals("host_auth_failed", denied["errorCode"]?.jsonPrimitive?.content)
            }

            socketClient.webSocket("/watch") {
                send(
                    """{"type":"hello","protocolVersion":5,"roomCode":"$code","clientId":"host","resumeCapability":"$resumeCapability","hostCapability":"$hostCapability"}""",
                )
                val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                assertTrue(welcome["isHost"]!!.jsonPrimitive.boolean)
            }
        }

    @Test
    fun host_transfer_rotates_private_capability_and_rejects_the_previous_host_secret() =
        testApplication {
            application { watchTogetherModule() }
            val socketClient =
                createClient {
                    install(WebSockets)
                }
            val roomCode = CompletableDeferred<String>()
            val guestJoined = CompletableDeferred<Unit>()
            val oldHostCapability = CompletableDeferred<String>()
            val guestResumeCapability = CompletableDeferred<String>()
            val guestHostCapability = CompletableDeferred<String>()
            val testScope = CoroutineScope(currentCoroutineContext())

            val host =
                testScope.launch {
                    socketClient.webSocket("/watch") {
                        send(
                            """{"type":"hello","protocolVersion":5,"clientId":"old-host","mediaKey":"tmdb:904"}""",
                        )
                        val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                        roomCode.complete(welcome["roomCode"]!!.jsonPrimitive.content)
                        oldHostCapability.complete(welcome["hostCapability"]!!.jsonPrimitive.content)
                        guestJoined.await()
                        while (true) {
                            val frame = (incoming.receive() as Frame.Text).readText().asJson()
                            assertFalse(frame.containsKey("resumeCapability"))
                            assertFalse(frame.containsKey("hostCapability"))
                            if (frame["type"]?.jsonPrimitive?.content == "controlRequested") {
                                send("""{"type":"grantControl","targetClientId":"new-host"}""")
                                break
                            }
                        }
                        guestHostCapability.await()
                    }
                }

            val guest =
                testScope.launch {
                    socketClient.webSocket("/watch") {
                        send(
                            """{"type":"hello","protocolVersion":5,"roomCode":"${roomCode.await()}","clientId":"new-host"}""",
                        )
                        val welcome = incoming.receiveType("welcome", "resumeCapability", "hostCapability")
                        guestResumeCapability.complete(
                            welcome["resumeCapability"]!!.jsonPrimitive.content,
                        )
                        guestJoined.complete(Unit)
                        send("""{"type":"requestControl"}""")
                        while (true) {
                            val frame = (incoming.receive() as Frame.Text).readText().asJson()
                            if (frame["type"]?.jsonPrimitive?.content == "hostCapabilityGranted") {
                                guestHostCapability.complete(
                                    frame["hostCapability"]!!.jsonPrimitive.content,
                                )
                                break
                            }
                        }
                    }
                }

            withTimeout(5_000L) {
                guestHostCapability.await()
                host.join()
                guest.join()
            }

            socketClient.webSocket("/watch") {
                send(
                    """{"type":"hello","protocolVersion":5,"roomCode":"${roomCode.await()}","clientId":"new-host","resumeCapability":"${guestResumeCapability.await()}","hostCapability":"${oldHostCapability.await()}"}""",
                )
                val denied = (incoming.receive() as Frame.Text).readText().asJson()
                assertEquals("host_auth_failed", denied["errorCode"]?.jsonPrimitive?.content)
            }

            socketClient.webSocket("/watch") {
                send(
                    """{"type":"hello","protocolVersion":5,"roomCode":"${roomCode.await()}","clientId":"new-host","resumeCapability":"${guestResumeCapability.await()}","hostCapability":"${guestHostCapability.await()}"}""",
                )
                val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                assertTrue(welcome["isHost"]!!.jsonPrimitive.boolean)
            }
        }

    @Test
    fun a_granted_control_request_moves_the_host_slot_to_the_asker() =
        testApplication {
            application { watchTogetherModule() }
            val socketClient =
                createClient {
                    install(WebSockets)
                }
            val roomCode = CompletableDeferred<String>()
            val guestJoined = CompletableDeferred<Unit>()
            val guestPromoted = CompletableDeferred<Boolean>()
            val hostDemoted = CompletableDeferred<Boolean>()
            val testScope = CoroutineScope(currentCoroutineContext())

            val host =
                testScope.launch {
                    socketClient.webSocket("/watch") {
                        send("""{"type":"hello","protocolVersion":5,"clientId":"host","mediaKey":"tmdb:11"}""")
                        val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                        roomCode.complete(welcome["roomCode"]!!.jsonPrimitive.content)
                        guestJoined.await()
                        while (true) {
                            val payload = (incoming.receive() as Frame.Text).readText().asJson()
                            if (payload["type"]?.jsonPrimitive?.content == "controlRequested") {
                                assertEquals("guest", payload["clientId"]?.jsonPrimitive?.content)
                                assertEquals("Guest", payload["name"]?.jsonPrimitive?.content)
                                send("""{"type":"grantControl","targetClientId":"guest"}""")
                                break
                            }
                        }
                        // The handover has to demote this side too, or both ends think they drive.
                        while (true) {
                            val payload = (incoming.receive() as Frame.Text).readText().asJson()
                            if (payload["type"]?.jsonPrimitive?.content == "roomUpdate" &&
                                payload["isHost"]?.jsonPrimitive?.boolean == false
                            ) {
                                hostDemoted.complete(true)
                                break
                            }
                        }
                        guestPromoted.await()
                    }
                }
            val guest =
                testScope.launch {
                    val code = roomCode.await()
                    socketClient.webSocket("/watch") {
                        send(
                            """{"type":"hello","protocolVersion":5,"roomCode":"$code","clientId":"guest","name":"Guest"}""",
                        )
                        val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                        assertFalse(welcome["isHost"]!!.jsonPrimitive.boolean)
                        guestJoined.complete(Unit)
                        send("""{"type":"requestControl"}""")
                        while (true) {
                            val payload = (incoming.receive() as Frame.Text).readText().asJson()
                            if (payload["type"]?.jsonPrimitive?.content == "roomUpdate" &&
                                payload["isHost"]?.jsonPrimitive?.boolean == true
                            ) {
                                guestPromoted.complete(true)
                                break
                            }
                        }
                    }
                }

            assertTrue(withTimeout(5_000L) { guestPromoted.await() })
            assertTrue(withTimeout(5_000L) { hostDemoted.await() })
            host.cancelAndJoin()
            guest.cancelAndJoin()
        }

    @Test
    fun a_denied_control_request_reaches_the_asker_and_leaves_the_host_in_place() =
        testApplication {
            application { watchTogetherModule() }
            val socketClient =
                createClient {
                    install(WebSockets)
                }
            val roomCode = CompletableDeferred<String>()
            val guestJoined = CompletableDeferred<Unit>()
            val denied = CompletableDeferred<Boolean>()
            val testScope = CoroutineScope(currentCoroutineContext())

            val host =
                testScope.launch {
                    socketClient.webSocket("/watch") {
                        send("""{"type":"hello","protocolVersion":5,"clientId":"host","mediaKey":"tmdb:12"}""")
                        val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                        roomCode.complete(welcome["roomCode"]!!.jsonPrimitive.content)
                        guestJoined.await()
                        while (true) {
                            val payload = (incoming.receive() as Frame.Text).readText().asJson()
                            if (payload["type"]?.jsonPrimitive?.content == "controlRequested") {
                                send("""{"type":"denyControl","targetClientId":"guest"}""")
                                break
                            }
                        }
                        denied.await()
                    }
                }
            val guest =
                testScope.launch {
                    val code = roomCode.await()
                    socketClient.webSocket("/watch") {
                        send(
                            """{"type":"hello","protocolVersion":5,"roomCode":"$code","clientId":"guest","name":"Guest"}""",
                        )
                        incoming.receive() // welcome
                        guestJoined.complete(Unit)
                        send("""{"type":"requestControl"}""")
                        while (true) {
                            val payload = (incoming.receive() as Frame.Text).readText().asJson()
                            val type = payload["type"]?.jsonPrimitive?.content
                            // A refusal must never quietly arrive as a promotion.
                            if (type == "roomUpdate") {
                                assertFalse(payload["isHost"]!!.jsonPrimitive.boolean)
                            }
                            if (type == "controlDenied") {
                                denied.complete(true)
                                break
                            }
                        }
                    }
                }

            assertTrue(withTimeout(5_000L) { denied.await() })
            host.cancelAndJoin()
            guest.cancelAndJoin()
        }

    @Test
    fun a_guest_cannot_grant_control_to_itself() =
        testApplication {
            application { watchTogetherModule() }
            val socketClient =
                createClient {
                    install(WebSockets)
                }
            val roomCode = CompletableDeferred<String>()
            val guestJoined = CompletableDeferred<Unit>()
            val rejected = CompletableDeferred<Boolean>()
            val testScope = CoroutineScope(currentCoroutineContext())

            val host =
                testScope.launch {
                    socketClient.webSocket("/watch") {
                        send("""{"type":"hello","protocolVersion":5,"clientId":"host","mediaKey":"tmdb:13"}""")
                        val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                        roomCode.complete(welcome["roomCode"]!!.jsonPrimitive.content)
                        rejected.await()
                    }
                }
            val guest =
                testScope.launch {
                    val code = roomCode.await()
                    socketClient.webSocket("/watch") {
                        send("""{"type":"hello","protocolVersion":5,"roomCode":"$code","clientId":"guest"}""")
                        incoming.receive() // welcome
                        guestJoined.complete(Unit)
                        send("""{"type":"grantControl","targetClientId":"guest"}""")
                        // The self-grant must be ignored outright. Proven by the sync that follows
                        // it still being refused: had the grant landed, this would be accepted.
                        send("""{"type":"sync","mediaKey":"tmdb:13","positionMs":1,"paused":false,"rate":1.0}""")
                        while (true) {
                            val payload = (incoming.receive() as Frame.Text).readText().asJson()
                            when (payload["type"]?.jsonPrimitive?.content) {
                                "roomUpdate" -> assertFalse(payload["isHost"]!!.jsonPrimitive.boolean)
                                "error" -> {
                                    rejected.complete(true)
                                    break
                                }
                            }
                        }
                    }
                }

            assertTrue(withTimeout(5_000L) { rejected.await() })
            host.cancelAndJoin()
            guest.cancelAndJoin()
        }

    @Test
    fun chat_uses_the_joined_profile_and_replays_history_to_new_members() =
        testApplication {
            application { watchTogetherModule() }
            val socketClient =
                createClient {
                    install(WebSockets)
                }
            val roomCode = CompletableDeferred<String>()
            val chatSent = CompletableDeferred<Unit>()
            val historyChecked = CompletableDeferred<Boolean>()
            val testScope = CoroutineScope(currentCoroutineContext())

            val host =
                testScope.launch {
                    socketClient.webSocket("/watch") {
                        send(
                            """{"type":"hello","protocolVersion":5,"clientId":"host","name":"小影迷 🎬","avatarId":3,"mediaKey":"tmdb:21"}""",
                        )
                        val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                        roomCode.complete(welcome["roomCode"]!!.jsonPrimitive.content)
                        val member = welcome["participants"]!!.jsonArray.single().jsonObject
                        assertEquals("小影迷 🎬", member["name"]?.jsonPrimitive?.content)
                        assertEquals(3, member["avatarId"]?.jsonPrimitive?.int)

                        send("""{"type":"chat","text":"今晚一起看 🍿👍🏽","name":"伪造昵称","avatarId":7}""")
                        while (true) {
                            val payload = (incoming.receive() as Frame.Text).readText().asJson()
                            if (payload["type"]?.jsonPrimitive?.content == "chat") {
                                val chat = payload["chat"]!!.jsonObject
                                assertEquals("小影迷 🎬", chat["name"]?.jsonPrimitive?.content)
                                assertEquals(3, chat["avatarId"]?.jsonPrimitive?.int)
                                assertEquals("今晚一起看 🍿👍🏽", chat["text"]?.jsonPrimitive?.content)
                                chatSent.complete(Unit)
                                break
                            }
                        }
                        historyChecked.await()
                    }
                }

            val guest =
                testScope.launch {
                    val code = roomCode.await()
                    chatSent.await()
                    socketClient.webSocket("/watch") {
                        send(
                            """{"type":"hello","protocolVersion":5,"roomCode":"$code","clientId":"guest","name":"访客","avatarId":1}""",
                        )
                        val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                        val history = welcome["chatHistory"]!!.jsonArray
                        assertEquals(1, history.size)
                        assertEquals(
                            "今晚一起看 🍿👍🏽",
                            history
                                .single()
                                .jsonObject["text"]
                                ?.jsonPrimitive
                                ?.content,
                        )
                        assertEquals(2, welcome["participants"]?.jsonArray?.size)
                        historyChecked.complete(true)
                    }
                }

            assertTrue(withTimeout(5_000L) { historyChecked.await() })
            host.cancelAndJoin()
            guest.cancelAndJoin()
        }

    @Test
    fun reaction_relays_only_the_known_set_and_never_stores_it() =
        testApplication {
            application { watchTogetherModule() }
            val socketClient =
                createClient {
                    install(WebSockets)
                }
            val roomCode = CompletableDeferred<String>()
            val reactionSeen = CompletableDeferred<Unit>()
            val testScope = CoroutineScope(currentCoroutineContext())

            val host =
                testScope.launch {
                    socketClient.webSocket("/watch") {
                        send(
                            """{"type":"hello","protocolVersion":5,"clientId":"host","name":"房主","avatarId":0,"mediaKey":"tmdb:9"}""",
                        )
                        val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                        assertTrue(
                            "reactions" in
                                welcome["capabilities"]!!.jsonArray.map {
                                    it.jsonPrimitive.content
                                },
                        )
                        roomCode.complete(welcome["roomCode"]!!.jsonPrimitive.content)

                        // Joining broadcasts a roomUpdate back to the host after the welcome frame.
                        // Drain it so the next frame is the response to the invalid reaction below.
                        incoming.receive()

                        // Anything outside the server's own set is refused rather than relayed.
                        send("""{"type":"reaction","reaction":"🙈"}""")
                        val refusal = (incoming.receive() as Frame.Text).readText().asJson()
                        assertEquals("error", refusal["type"]?.jsonPrimitive?.content)
                        assertEquals("reaction_invalid", refusal["errorCode"]?.jsonPrimitive?.content)

                        send("""{"type":"reaction","reaction":"😂","name":"伪造昵称"}""")
                        while (true) {
                            val payload = (incoming.receive() as Frame.Text).readText().asJson()
                            if (payload["type"]?.jsonPrimitive?.content == "reaction") {
                                assertEquals("😂", payload["reaction"]?.jsonPrimitive?.content)
                                // The name comes from the joined profile, not from the message.
                                assertEquals("房主", payload["name"]?.jsonPrimitive?.content)
                                reactionSeen.complete(Unit)
                                break
                            }
                        }
                    }
                }

            val guest =
                testScope.launch {
                    val code = roomCode.await()
                    reactionSeen.await()
                    socketClient.webSocket("/watch") {
                        send(
                            """{"type":"hello","protocolVersion":5,"roomCode":"$code","clientId":"guest","name":"访客","avatarId":1}""",
                        )
                        val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                        // Reactions leave no history: joining after one has happened shows nothing.
                        assertEquals(0, welcome["chatHistory"]?.jsonArray?.size ?: 0)
                    }
                }

            guest.join()
            host.join()
        }

    @Test
    fun chat_enforces_grapheme_length_and_its_own_rate_limit() =
        testApplication {
            application { watchTogetherModule() }
            val socketClient =
                createClient {
                    install(WebSockets)
                }

            socketClient.webSocket("/watch") {
                send("""{"type":"hello","protocolVersion":5,"clientId":"host","mediaKey":"tmdb:22"}""")
                incoming.receive() // welcome
                incoming.receive() // own roomUpdate

                send("""{"type":"chat","text":"${"😀".repeat(31)}"}""")
                val invalid = (incoming.receive() as Frame.Text).readText().asJson()
                assertEquals("chat_invalid", invalid["errorCode"]?.jsonPrimitive?.content)

                repeat(4) { send("""{"type":"chat","text":"消息$it 😀"}""") }
                var chats = 0
                var rateLimited = false
                while (!rateLimited) {
                    val payload = (incoming.receive() as Frame.Text).readText().asJson()
                    when (payload["type"]?.jsonPrimitive?.content) {
                        "chat" -> chats++
                        "error" ->
                            rateLimited =
                                payload["errorCode"]?.jsonPrimitive?.content == "chat_rate_limited"
                    }
                }
                assertEquals(3, chats)
                assertTrue(rateLimited)
            }
        }

    @Test
    fun profile_updates_are_broadcast_as_room_membership() =
        testApplication {
            application { watchTogetherModule() }
            val socketClient =
                createClient {
                    install(WebSockets)
                }

            socketClient.webSocket("/watch") {
                send(
                    """{"type":"hello","protocolVersion":5,"clientId":"host","name":"旧昵称","avatarId":0,"mediaKey":"tmdb:23"}""",
                )
                incoming.receive() // welcome
                incoming.receive() // own roomUpdate
                send("""{"type":"updateProfile","name":"新昵称 🐼","avatarId":5}""")

                val update = (incoming.receive() as Frame.Text).readText().asJson()
                assertEquals("roomUpdate", update["type"]?.jsonPrimitive?.content)
                val member = update["participants"]!!.jsonArray.single().jsonObject
                assertEquals("新昵称 🐼", member["name"]?.jsonPrimitive?.content)
                assertEquals(5, member["avatarId"]?.jsonPrimitive?.int)
                assertTrue(member["isHost"]!!.jsonPrimitive.boolean)
            }
        }

    @Test
    fun protocol_version_range_is_negotiated_and_outside_versions_are_rejected() =
        testApplication {
            application { watchTogetherModule() }
            val socketClient =
                createClient {
                    install(WebSockets)
                }

            socketClient.webSocket("/watch") {
                send(
                    """{"type":"hello","protocolVersion":5,"clientId":"host","mediaKey":"tmdb:30"}""",
                )
                val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                assertEquals("welcome", welcome["type"]?.jsonPrimitive?.content)
                assertEquals(5, welcome["protocolVersion"]?.jsonPrimitive?.int)
            }

            socketClient.webSocket("/watch") {
                send(
                    """{"type":"hello","protocolVersion":6,"clientId":"current","mediaKey":"tmdb:30"}""",
                )
                val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                assertEquals("welcome", welcome["type"]?.jsonPrimitive?.content)
                assertEquals(6, welcome["protocolVersion"]?.jsonPrimitive?.int)
            }

            socketClient.webSocket("/watch") {
                send(
                    """{"type":"hello","protocolVersion":99,"clientId":"future","mediaKey":"tmdb:31"}""",
                )
                val error = (incoming.receive() as Frame.Text).readText().asJson()
                assertEquals("protocol_incompatible", error["errorCode"]?.jsonPrimitive?.content)
                assertEquals(6, error["protocolVersion"]?.jsonPrimitive?.int)
            }

            listOf(
                """{"type":"hello","clientId":"missing","mediaKey":"tmdb:31"}""",
                """{"type":"hello","protocolVersion":4,"clientId":"legacy","mediaKey":"tmdb:31"}""",
            ).forEach { rejectedHello ->
                socketClient.webSocket("/watch") {
                    send(rejectedHello)
                    val error = (incoming.receive() as Frame.Text).readText().asJson()
                    assertEquals("protocol_incompatible", error["errorCode"]?.jsonPrimitive?.content)
                }
            }
        }

    @Test
    fun malformed_unknown_and_invalid_payloads_are_explicitly_rejected() =
        testApplication {
            application { watchTogetherModule() }
            val socketClient =
                createClient {
                    install(WebSockets)
                }

            socketClient.webSocket("/watch") {
                send("not-json")
                assertEquals(
                    "message_invalid",
                    (incoming.receive() as Frame.Text)
                        .readText()
                        .asJson()["errorCode"]
                        ?.jsonPrimitive
                        ?.content,
                )

                send("""{"type":"deleteRoom"}""")
                assertEquals(
                    "message_type_invalid",
                    (incoming.receive() as Frame.Text)
                        .readText()
                        .asJson()["errorCode"]
                        ?.jsonPrimitive
                        ?.content,
                )

                send(
                    """{"type":"hello","protocolVersion":5,"clientId":"host","mediaKey":"tmdb:905"}""",
                )
                incoming.receive() // welcome
                incoming.receive() // own roomUpdate

                send("""{"type":"chat","text":"bad\nmessage"}""")
                assertEquals(
                    "chat_invalid",
                    (incoming.receive() as Frame.Text)
                        .readText()
                        .asJson()["errorCode"]
                        ?.jsonPrimitive
                        ?.content,
                )

                send("""{"type":"playbackStatus","latencyMs":10001}""")
                assertEquals(
                    "playback_status_invalid",
                    (incoming.receive() as Frame.Text)
                        .readText()
                        .asJson()["errorCode"]
                        ?.jsonPrimitive
                        ?.content,
                )
            }
        }

    @Test
    fun binaryDataFrameIsRejectedWithPolicyViolation() =
        testApplication {
            application { watchTogetherModule() }
            val socketClient = createClient { install(WebSockets) }
            val session = socketClient.webSocketSession("/watch")

            session.send(Frame.Binary(fin = true, data = byteArrayOf(1, 2, 3)))

            val reason =
                withTimeout(2_000L) {
                    (session as DefaultWebSocketSession).closeReason.await()
                }
            assertEquals(1008, reason?.code?.toInt())
            assertEquals("binary_frames_not_supported", reason?.message)
        }

    @Test
    fun chat_client_message_id_acknowledges_and_deduplicates_retries() =
        testApplication {
            application { watchTogetherModule() }
            val socketClient =
                createClient {
                    install(WebSockets)
                }
            var code = ""
            var firstServerId = 0L

            socketClient.webSocket("/watch") {
                send("""{"type":"hello","protocolVersion":5,"clientId":"host","mediaKey":"tmdb:32"}""")
                val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                code = welcome["roomCode"]!!.jsonPrimitive.content
                incoming.receive() // own roomUpdate

                send("""{"type":"chat","clientMessageId":"local-1","text":"可靠送达 😀"}""")
                val first = (incoming.receive() as Frame.Text).readText().asJson()["chat"]!!.jsonObject
                firstServerId = first["id"]!!.jsonPrimitive.long
                assertEquals("local-1", first["clientMessageId"]?.jsonPrimitive?.content)

                send("""{"type":"chat","clientMessageId":"local-1","text":"可靠送达 😀"}""")
                val retry = (incoming.receive() as Frame.Text).readText().asJson()["chat"]!!.jsonObject
                assertEquals(firstServerId, retry["id"]?.jsonPrimitive?.long)
            }

            socketClient.webSocket("/watch") {
                send("""{"type":"hello","protocolVersion":5,"roomCode":"$code","clientId":"guest"}""")
                val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                val history = welcome["chatHistory"]!!.jsonArray
                assertEquals(1, history.size)
                assertEquals(
                    firstServerId,
                    history
                        .single()
                        .jsonObject["id"]
                        ?.jsonPrimitive
                        ?.long,
                )
            }
        }

    @Test
    fun playback_status_broadcasts_readiness_latency_and_drift() =
        testApplication {
            application { watchTogetherModule() }
            val socketClient =
                createClient {
                    install(WebSockets)
                }

            socketClient.webSocket("/watch") {
                send("""{"type":"hello","protocolVersion":5,"clientId":"host","mediaKey":"tmdb:33"}""")
                incoming.receive() // welcome
                incoming.receive() // own roomUpdate
                send(
                    """{"type":"playbackStatus","ready":true,"buffering":false,"mediaAvailable":true,"latencyMs":86,"syncDriftMs":-120}""",
                )
                val update = (incoming.receive() as Frame.Text).readText().asJson()
                val member = update["participants"]!!.jsonArray.single().jsonObject
                assertTrue(member["statusKnown"]!!.jsonPrimitive.boolean)
                assertTrue(member["ready"]!!.jsonPrimitive.boolean)
                assertEquals(86L, member["latencyMs"]?.jsonPrimitive?.long)
                assertEquals(-120L, member["syncDriftMs"]?.jsonPrimitive?.long)
            }
        }

    @Test
    fun everyone_and_moderator_modes_allow_guest_control() =
        testApplication {
            application { watchTogetherModule() }
            val socketClient =
                createClient {
                    install(WebSockets)
                }
            val roomCode = CompletableDeferred<String>()
            val guestJoined = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val testScope = CoroutineScope(currentCoroutineContext())

            val host =
                testScope.launch {
                    socketClient.webSocket("/watch") {
                        send("""{"type":"hello","protocolVersion":5,"clientId":"host","mediaKey":"tmdb:34"}""")
                        val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                        roomCode.complete(welcome["roomCode"]!!.jsonPrimitive.content)
                        guestJoined.await()

                        send("""{"type":"setControlMode","controlMode":"everyone"}""")
                        var sawEveryoneSync = false
                        while (!sawEveryoneSync) {
                            val payload = (incoming.receive() as Frame.Text).readText().asJson()
                            sawEveryoneSync = payload["type"]?.jsonPrimitive?.content == "sync" &&
                                payload["positionMs"]?.jsonPrimitive?.long == 111L
                        }

                        send("""{"type":"setControlMode","controlMode":"moderators"}""")
                        send(
                            """{"type":"setModerator","targetClientId":"guest","moderator":true}""",
                        )
                        var sawModeratorSync = false
                        while (!sawModeratorSync) {
                            val payload = (incoming.receive() as Frame.Text).readText().asJson()
                            sawModeratorSync = payload["type"]?.jsonPrimitive?.content == "sync" &&
                                payload["positionMs"]?.jsonPrimitive?.long == 222L
                        }
                        finished.complete(Unit)
                    }
                }
            val guest =
                testScope.launch {
                    socketClient.webSocket("/watch") {
                        send(
                            """{"type":"hello","protocolVersion":5,"roomCode":"${roomCode.await()}","clientId":"guest"}""",
                        )
                        incoming.receive() // welcome
                        guestJoined.complete(Unit)
                        var sentEveryone = false
                        var sentModerator = false
                        while (!sentModerator) {
                            val payload = (incoming.receive() as Frame.Text).readText().asJson()
                            if (payload["type"]?.jsonPrimitive?.content != "roomUpdate") continue
                            val mode = payload["controlMode"]?.jsonPrimitive?.content
                            val canControl = payload["canControl"]?.jsonPrimitive?.boolean == true
                            if (mode == "everyone" && canControl && !sentEveryone) {
                                sentEveryone = true
                                send(
                                    """{"type":"sync","mediaKey":"tmdb:34","positionMs":111,"paused":false,"rate":1.0}""",
                                )
                            }
                            if (mode == "moderators" && canControl && !sentModerator) {
                                val self =
                                    payload["participants"]!!
                                        .jsonArray
                                        .map { it.jsonObject }
                                        .first { it["clientId"]?.jsonPrimitive?.content == "guest" }
                                assertTrue(self["isModerator"]!!.jsonPrimitive.boolean)
                                sentModerator = true
                                send(
                                    """{"type":"sync","mediaKey":"tmdb:34","positionMs":222,"paused":false,"rate":1.0}""",
                                )
                            }
                        }
                        finished.await()
                    }
                }

            withTimeout(5_000L) { finished.await() }
            host.cancelAndJoin()
            guest.cancelAndJoin()
        }

    @Test
    fun only_host_can_remove_guest_and_removed_guest_cannot_rejoin() =
        testApplication {
            application { watchTogetherModule() }
            val socketClient =
                createClient {
                    install(WebSockets)
                }
            val roomCode = CompletableDeferred<String>()
            val guestJoined = CompletableDeferred<Unit>()
            val unauthorizedDenied = CompletableDeferred<Unit>()
            val kickedReceived = CompletableDeferred<Unit>()
            val hostSawRemoval = CompletableDeferred<Unit>()
            val rejoinBlocked = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val testScope = CoroutineScope(currentCoroutineContext())

            val host =
                testScope.launch {
                    socketClient.webSocket("/watch") {
                        send(
                            """{"type":"hello","protocolVersion":5,"clientId":"host","mediaKey":"tmdb:35"}""",
                        )
                        val welcome = (incoming.receive() as Frame.Text).readText().asJson()
                        roomCode.complete(welcome["roomCode"]!!.jsonPrimitive.content)
                        guestJoined.await()
                        unauthorizedDenied.await()
                        send("""{"type":"kickParticipant","targetClientId":"guest"}""")

                        while (!hostSawRemoval.isCompleted) {
                            val payload = (incoming.receive() as Frame.Text).readText().asJson()
                            if (
                                payload["type"]?.jsonPrimitive?.content == "roomUpdate" &&
                                payload["participantCount"]?.jsonPrimitive?.int == 1
                            ) {
                                val participants = payload["participants"]!!.jsonArray
                                assertEquals(
                                    "host",
                                    participants
                                        .single()
                                        .jsonObject["clientId"]
                                        ?.jsonPrimitive
                                        ?.content,
                                )
                                hostSawRemoval.complete(Unit)
                            }
                        }
                        finished.await()
                    }
                }

            val guest =
                testScope.launch {
                    val code = roomCode.await()
                    runCatching {
                        socketClient.webSocket("/watch") {
                            send(
                                """{"type":"hello","protocolVersion":5,"roomCode":"$code","clientId":"guest"}""",
                            )
                            while (!guestJoined.isCompleted) {
                                val payload = (incoming.receive() as Frame.Text).readText().asJson()
                                if (payload["type"]?.jsonPrimitive?.content == "welcome") {
                                    guestJoined.complete(Unit)
                                }
                            }

                            // A guest cannot use the same message to remove the host.
                            send("""{"type":"kickParticipant","targetClientId":"host"}""")
                            while (!unauthorizedDenied.isCompleted) {
                                val payload = (incoming.receive() as Frame.Text).readText().asJson()
                                if (
                                    payload["type"]?.jsonPrimitive?.content == "error" &&
                                    payload["errorCode"]?.jsonPrimitive?.content == "host_only"
                                ) {
                                    unauthorizedDenied.complete(Unit)
                                }
                            }

                            while (!kickedReceived.isCompleted) {
                                val payload = (incoming.receive() as Frame.Text).readText().asJson()
                                if (payload["type"]?.jsonPrimitive?.content == "kicked") {
                                    assertTrue(payload["message"]?.jsonPrimitive?.content?.contains("移出") == true)
                                    kickedReceived.complete(Unit)
                                }
                            }
                        }
                    }

                    socketClient.webSocket("/watch") {
                        send(
                            """{"type":"hello","protocolVersion":5,"roomCode":"$code","clientId":"guest"}""",
                        )
                        val payload = (incoming.receive() as Frame.Text).readText().asJson()
                        assertEquals("error", payload["type"]?.jsonPrimitive?.content)
                        assertEquals("removed_by_host", payload["errorCode"]?.jsonPrimitive?.content)
                        rejoinBlocked.complete(Unit)
                    }
                }

            withTimeout(5_000L) {
                unauthorizedDenied.await()
                kickedReceived.await()
                hostSawRemoval.await()
                rejoinBlocked.await()
            }
            finished.complete(Unit)
            host.cancelAndJoin()
            guest.cancelAndJoin()
        }

    @Test
    fun authenticatedAccountCannotAllocateFreshMembershipsOrBypassKickWithNewClientId() =
        testApplication {
            val backend =
                com.yfuse.watch.account.AccountBackend
                    .inMemoryForTests()
            val hostAuth =
                backend.service.register(
                    com.yfuse.watch.account.RegisterRequest(
                        username = "account-host",
                        password = "Watch-Test-42",
                    ),
                )
            val guestAuth =
                backend.service.register(
                    com.yfuse.watch.account.RegisterRequest(
                        username = "account-guest",
                        password = "Watch-Test-42",
                    ),
                )
            application {
                watchTogetherModule(
                    accountBackend = backend,
                    requireWatchAuthentication = true,
                )
            }
            val socketClient = createClient { install(WebSockets) }
            val host =
                socketClient.webSocketSession("/watch") {
                    headers.append(HttpHeaders.Authorization, "Bearer ${hostAuth.accessToken}")
                }
            host.send(
                """{"type":"hello","protocolVersion":5,"clientId":"host-device","mediaKey":"tmdb:351"}""",
            )
            val hostWelcome =
                (host.incoming.receive() as Frame.Text)
                    .readText()
                    .asJson()
            val roomCode = hostWelcome.getValue("roomCode").jsonPrimitive.content

            val guest =
                socketClient.webSocketSession("/watch") {
                    headers.append(HttpHeaders.Authorization, "Bearer ${guestAuth.accessToken}")
                }
            guest.send(
                """{"type":"hello","protocolVersion":5,"roomCode":"$roomCode","clientId":"guest-device"}""",
            )
            val guestWelcome =
                (guest.incoming.receive() as Frame.Text)
                    .readText()
                    .asJson()
            assertEquals("welcome", guestWelcome["type"]?.jsonPrimitive?.content)

            val conflictingIdentity =
                socketClient.webSocketSession("/watch") {
                    headers.append(HttpHeaders.Authorization, "Bearer ${guestAuth.accessToken}")
                }
            conflictingIdentity.send(
                """{"type":"hello","protocolVersion":5,"roomCode":"$roomCode","clientId":"guest-device-2"}""",
            )
            val conflict =
                (conflictingIdentity.incoming.receive() as Frame.Text)
                    .readText()
                    .asJson()
            assertEquals("account_membership_conflict", conflict["errorCode"]?.jsonPrimitive?.content)
            val conflictCloseReason =
                withTimeout(2_000L) {
                    (conflictingIdentity as DefaultWebSocketSession).closeReason.await()
                }
            assertEquals(1008, conflictCloseReason?.code?.toInt())

            host.send("""{"type":"kickParticipant","targetClientId":"guest-device"}""")
            withTimeout(2_000L) {
                while (true) {
                    val payload = (guest.incoming.receive() as Frame.Text).readText().asJson()
                    if (payload["type"]?.jsonPrimitive?.content == "kicked") break
                }
            }

            val rejoin =
                socketClient.webSocketSession("/watch") {
                    headers.append(HttpHeaders.Authorization, "Bearer ${guestAuth.accessToken}")
                }
            rejoin.send(
                """{"type":"hello","protocolVersion":5,"roomCode":"$roomCode","clientId":"brand-new-id"}""",
            )
            val removed =
                (rejoin.incoming.receive() as Frame.Text)
                    .readText()
                    .asJson()
            assertEquals("removed_by_host", removed["errorCode"]?.jsonPrimitive?.content)
            val rejoinCloseReason =
                withTimeout(2_000L) {
                    (rejoin as DefaultWebSocketSession).closeReason.await()
                }
            assertEquals(1008, rejoinCloseReason?.code?.toInt())
            host.close()
        }

    @Test
    fun active_room_creation_is_limited_per_ip_without_blocking_other_ips() =
        testApplication {
            application {
                watchTogetherModule(
                    maxActiveRoomsPerIp = 1,
                    clientIpResolver = { call ->
                        call.request.queryParameters["testIp"] ?: "test-default"
                    },
                )
            }
            val socketClient =
                createClient {
                    install(WebSockets)
                }

            socketClient.webSocket("/watch?testIp=shared") {
                send("""{"type":"hello","protocolVersion":5,"clientId":"host-1","mediaKey":"tmdb:101"}""")
                val first = (incoming.receive() as Frame.Text).readText().asJson()
                assertEquals("welcome", first["type"]?.jsonPrimitive?.content)
                assertTrue(
                    first["roomCode"]
                        ?.jsonPrimitive
                        ?.content
                        ?.matches(Regex("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}")) == true,
                )

                socketClient.webSocket("/watch?testIp=shared") {
                    send("""{"type":"hello","protocolVersion":5,"clientId":"host-2","mediaKey":"tmdb:102"}""")
                    val limited = (incoming.receive() as Frame.Text).readText().asJson()
                    assertEquals("error", limited["type"]?.jsonPrimitive?.content)
                    assertEquals("room_ip_limit", limited["errorCode"]?.jsonPrimitive?.content)
                }

                socketClient.webSocket("/watch?testIp=other") {
                    send("""{"type":"hello","protocolVersion":5,"clientId":"host-3","mediaKey":"tmdb:103"}""")
                    val other = (incoming.receive() as Frame.Text).readText().asJson()
                    assertEquals("welcome", other["type"]?.jsonPrimitive?.content)
                }
            }
        }

    @Test
    fun expired_empty_room_releases_its_ip_creation_quota() =
        testApplication {
            application {
                watchTogetherModule(
                    roomGraceMs = 20L,
                    maxActiveRoomsPerIp = 1,
                    clientIpResolver = { call ->
                        call.request.queryParameters["testIp"] ?: "test-default"
                    },
                )
            }
            val socketClient =
                createClient {
                    install(WebSockets)
                }

            socketClient.webSocket("/watch?testIp=recycled") {
                send("""{"type":"hello","protocolVersion":5,"clientId":"host-1","mediaKey":"tmdb:201"}""")
                val first = (incoming.receive() as Frame.Text).readText().asJson()
                assertEquals("welcome", first["type"]?.jsonPrimitive?.content)
            }

            // Allow both the server-side socket cleanup and the configured room grace to pass.
            delay(100L)

            socketClient.webSocket("/watch?testIp=recycled") {
                send("""{"type":"hello","protocolVersion":5,"clientId":"host-2","mediaKey":"tmdb:202"}""")
                val recreated = (incoming.receive() as Frame.Text).readText().asJson()
                assertEquals("welcome", recreated["type"]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun proxy_headers_are_only_used_after_explicit_trust() {
        assertEquals(
            "10.0.0.4",
            resolveClientIp(
                remoteHost = "10.0.0.4",
                xForwardedFor = "203.0.113.8, 10.0.0.3",
                forwarded = null,
                trustProxyHeaders = false,
            ),
        )
        assertEquals(
            "203.0.113.8",
            resolveClientIp(
                remoteHost = "10.0.0.4",
                xForwardedFor = "203.0.113.8, 10.0.0.3",
                forwarded = null,
                trustProxyHeaders = true,
            ),
        )
        assertEquals(
            "2001:db8::7",
            resolveClientIp(
                remoteHost = "10.0.0.4",
                xForwardedFor = null,
                forwarded = "for=\"[2001:db8::7]:4711\";proto=https",
                trustProxyHeaders = true,
            ),
        )
    }

    @Test
    fun hello_rejects_oversized_client_ids() =
        testApplication {
            application { watchTogetherModule() }
            val socketClient =
                createClient {
                    install(WebSockets)
                }

            socketClient.webSocket("/watch") {
                val oversized = "x".repeat(129)
                send("""{"type":"hello","protocolVersion":5,"clientId":"$oversized","mediaKey":"tmdb:301"}""")
                val error = (incoming.receive() as Frame.Text).readText().asJson()
                assertEquals("error", error["type"]?.jsonPrimitive?.content)
                assertEquals("client_id_invalid", error["errorCode"]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun removed_account_tombstones_have_a_strict_capacity() {
        val removed = linkedSetOf<String>()

        assertTrue(rememberRemovedAccountUserId(removed, "one", limit = 3))
        assertTrue(rememberRemovedAccountUserId(removed, "two", limit = 3))
        assertTrue(rememberRemovedAccountUserId(removed, "three", limit = 3))
        assertFalse(rememberRemovedAccountUserId(removed, "four", limit = 3))
        assertTrue(rememberRemovedAccountUserId(removed, "one", limit = 3))
        assertEquals(setOf("one", "two", "three"), removed)
    }
}

private fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject

/** WebSocket broadcasts may interleave with a direct reply; wait for the reply by protocol type. */
private suspend fun kotlinx.coroutines.channels.ReceiveChannel<Frame>.receiveType(
    type: String,
    vararg forbiddenKeys: String,
): JsonObject {
    while (true) {
        val frame = receive() as? Frame.Text ?: continue
        val payload = frame.readText().asJson()
        if (payload["type"]?.jsonPrimitive?.content == type) return payload
        forbiddenKeys.forEach { key -> assertFalse(payload.containsKey(key)) }
    }
}
