package com.yfuse.core.account

import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.UserAgentPreferences
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.model.SavedServer
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.personal.PersonalMediaRef
import com.yfuse.core.security.TestSecureStore
import com.yfuse.core.security.VaultCrypto
import com.yfuse.core.security.base64UrlToBytes
import com.yfuse.core.sync.ServerSyncManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonalCloudSyncTest {
    @Test
    fun encryptedPersonalSyncMergesDevicesRetriesCasAndPreservesCloudServerSettings() =
        runTest {
            val json =
                Json {
                    encodeDefaults = true
                    ignoreUnknownKeys = true
                }
            val auth =
                AuthResponse(
                    AccountUser("owner", "viewer", "用户", 1, 1_000, 1_000),
                    "access",
                    9_000_000_000_000,
                    "refresh",
                    9_000_000_000_000,
                )
            var remote = SyncResponse(0)
            var conflictOnce = false
            var conflicts = 0
            val requests = mutableListOf<String>()
            val client =
                createAccountClient(
                    MockEngine { request ->
                        val headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        when (request.url.encodedPath) {
                            "/api/v1/auth/prelogin" -> respond("", HttpStatusCode.NotFound)
                            "/api/v1/auth/register", "/api/v1/auth/login" ->
                                respond(
                                    json.encodeToString(auth),
                                    headers = headers,
                                )
                            "/api/v1/account/sync" ->
                                if (request.method.value ==
                                    "GET"
                                ) {
                                    respond(json.encodeToString(remote), headers = headers)
                                } else {
                                    val body = request.body.toByteArray().decodeToString()
                                    requests += body
                                    val put = json.decodeFromString<PutSyncRequest>(body)
                                    if (conflictOnce) {
                                        conflictOnce = false
                                        conflicts++
                                        respond(
                                            "{\"error\":\"sync_conflict\",\"message\":\"retry\"}",
                                            HttpStatusCode.Conflict,
                                            headers,
                                        )
                                    } else {
                                        assertEquals(remote.version, put.baseVersion)
                                        remote = SyncResponse(put.baseVersion + 1, put.payload, 2_000)
                                        respond(json.encodeToString(remote), headers = headers)
                                    }
                                }
                            else -> error("Unexpected path ${request.url.encodedPath}")
                        }
                    },
                )
            val phone = Fixture(AccountApi(client))
            val tablet = Fixture(AccountApi(client))
            val title = PersonalMediaRef("tmdb:603", "不可出现在请求明文的片名", "Movie")
            try {
                phone.registry.addOrUpdate(
                    SavedServer("original", "https://original.example", "原服务器", "u", "用户", "token"),
                )
                phone.account.register("viewer", "secret-password".toCharArray()).getOrThrow()
                phone.personal.setFavorite(title, true)
                phone.account.syncPersonalNow().getOrThrow()
                tablet.account.login("viewer", "secret-password".toCharArray()).getOrThrow()
                tablet.registry.addOrUpdate(
                    SavedServer("local-only", "https://local.example", "本机服务器", "u", "用户", "token"),
                )
                tablet.personal.setWatchLater(title.copy(mediaKey = "tmdb:604", title = "另一个作品"), true)
                conflictOnce = true
                tablet.account.syncPersonalNow().getOrThrow()
                assertEquals(1, conflicts)
                assertEquals(
                    title,
                    tablet.personal.state.value.favorites
                        .single()
                        .media,
                )
                assertEquals("local-only", tablet.registry.defaultServer?.id)
                phone.account.syncPersonalNow().getOrThrow()
                assertEquals(1, phone.personal.state.value.watchLater.size)
                tablet.personal.setFavorite(title, false)
                tablet.account.syncPersonalNow().getOrThrow()
                phone.account.syncPersonalNow().getOrThrow()
                assertTrue(
                    phone.personal.state.value.favorites
                        .isEmpty(),
                )
                tablet.account.downloadNow().getOrThrow()
                assertEquals("https://original.example", tablet.registry.defaultServer?.baseUrl)
                assertFalse(requests.any { title.title in it || "tmdb:603" in it })
            } finally {
                phone.media.close()
                tablet.media.close()
                client.close()
            }
        }

    @Test
    fun watchHistoryTooBigForPlainJsonIsCompressedAndReachesAnotherDevice() =
        runTest {
            val cloud = SyncServer()
            val phone = Fixture(AccountApi(cloud.client))
            val tv = Fixture(AccountApi(cloud.client))
            val tonight = PersonalMediaRef("tmdb:200001/s1e1", "今晚这一集", "Episode")
            try {
                phone.account.register("viewer", "secret-password".toCharArray()).getOrThrow()
                phone.personal.mergeRemote(watchedEpisodes(900))
                assertTrue(cloud.json.encodeToString(phone.personal.snapshot()).length > MAX_SYNC_CIPHERTEXT_BYTES)

                phone.account.uploadNow().getOrThrow()
                tv.account.login("viewer", "secret-password".toCharArray()).getOrThrow()
                tv.account.downloadNow().getOrThrow()
                assertEquals(900, tv.personal.state.value.history.size)

                tv.personal.recordHistory(tonight, 60_000, 2_700_000, false)
                tv.account.syncPersonalNow().getOrThrow()
                phone.account.syncPersonalNow().getOrThrow()
                assertEquals(901, phone.personal.state.value.history.size)
                assertEquals(3, cloud.acceptedUploads)
            } finally {
                phone.media.close()
                tv.media.close()
                cloud.client.close()
            }
        }

    @Test
    fun documentThatCannotFitEvenCompressedNamesWhatTakesTheRoom() =
        runTest {
            val cloud = SyncServer()
            val phone = Fixture(AccountApi(cloud.client))
            val random = Random(3)
            try {
                phone.account.register("viewer", "secret-password".toCharArray()).getOrThrow()
                // Titles of random characters do not compress, so a thousand of them stay too big.
                val library = watchedEpisodes(1_000)
                phone.personal.mergeRemote(
                    library.copy(
                        entries =
                            library.entries.map { entry ->
                                val title = String(CharArray(240) { (0x4E00 + random.nextInt(0x5000)).toChar() })
                                entry.copy(media = entry.media.copy(title = title))
                            },
                    ),
                )

                assertTrue(phone.account.uploadNow().isFailure)

                assertEquals(
                    "同步数据压缩后仍超出云端 256 KB 上限，其中观看历史占用最多",
                    assertIs<AccountState.SignedIn>(phone.account.state.value).message,
                )
                assertEquals(0, cloud.acceptedUploads)
            } finally {
                phone.media.close()
                cloud.client.close()
            }
        }

    @Test
    fun automaticMergeWritesOnlyWhatTheCloudLacksAndLeavesTheAccountCardAlone() =
        runTest {
            val cloud = SyncServer()
            val phone = Fixture(AccountApi(cloud.client))
            val tv = Fixture(AccountApi(cloud.client))
            val movie = PersonalMediaRef("tmdb:603", "黑客帝国", "Movie")
            try {
                phone.account.register("viewer", "secret-password".toCharArray()).getOrThrow()
                phone.personal.setFavorite(movie, true)
                val created = signedIn(phone).message
                // The first document would carry this device's servers too, so a person uploads it.
                phone.account.syncPersonalAutomatically().getOrThrow()
                assertEquals(0, cloud.acceptedUploads)
                assertEquals(created, signedIn(phone).message)
                assertNotNull(phone.personal.state.value.error)
                phone.account.syncPersonalNow().getOrThrow()
                phone.account.syncPersonalAutomatically().getOrThrow()
                assertEquals(1, cloud.acceptedUploads)

                tv.account.login("viewer", "secret-password".toCharArray()).getOrThrow()
                val ready = signedIn(tv).message
                tv.account.syncPersonalAutomatically().getOrThrow()
                assertEquals(
                    movie,
                    tv.personal.state.value.favorites
                        .single()
                        .media,
                )
                assertEquals(1, cloud.acceptedUploads)
                assertEquals(ready, signedIn(tv).message)

                tv.personal.setWatchLater(movie.copy(mediaKey = "tmdb:604", title = "黑客帝国 2"), true)
                tv.account.syncPersonalAutomatically().getOrThrow()
                assertEquals(2, cloud.acceptedUploads)
                // "云端版本 1 已就绪" no longer describes the cloud.
                assertNull(signedIn(tv).message)
                phone.account.syncPersonalAutomatically().getOrThrow()
                assertEquals(1, phone.personal.state.value.watchLater.size)
                assertEquals(2, signedIn(phone).syncVersion)
                assertEquals(2, cloud.acceptedUploads)

                cloud.unavailable = true
                phone.personal.setFavorite(movie, false)
                assertTrue(phone.account.syncPersonalAutomatically().isFailure)
                assertNull(signedIn(phone).message)
                assertFalse(signedIn(phone).syncing)
                assertNotNull(phone.personal.state.value.error)
                assertTrue(phone.personal.state.value.pendingSync)
            } finally {
                phone.media.close()
                tv.media.close()
                cloud.client.close()
            }
        }

    private fun signedIn(fixture: Fixture) = assertIs<AccountState.SignedIn>(fixture.account.state.value)

    /** The account server's sync endpoint, which refuses ciphertext past its limit like the real one. */
    private class SyncServer {
        val json =
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
            }
        var acceptedUploads = 0

        /** Answers every sync request 503, as the account service does while it is down. */
        var unavailable = false
        private var remote = SyncResponse(0)
        private val auth =
            AuthResponse(
                AccountUser("owner", "viewer", "用户", 1, 1_000, 1_000),
                "access",
                9_000_000_000_000,
                "refresh",
                9_000_000_000_000,
            )
        val client =
            createAccountClient(
                MockEngine { request ->
                    val headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    when {
                        request.url.encodedPath == "/api/v1/auth/prelogin" -> respond("", HttpStatusCode.NotFound)
                        request.url.encodedPath.startsWith("/api/v1/auth/") ->
                            respond(json.encodeToString(auth), headers = headers)
                        unavailable ->
                            respond(
                                "{\"error\":{\"code\":\"unavailable\",\"message\":\"账号服务暂时不可用\"}}",
                                HttpStatusCode.ServiceUnavailable,
                                headers,
                            )
                        request.method.value == "GET" -> respond(json.encodeToString(remote), headers = headers)
                        else -> {
                            val body = request.body.toByteArray().decodeToString()
                            val put = json.decodeFromString<PutSyncRequest>(body)
                            val ciphertext = put.payload.ciphertext.base64UrlToBytes()
                            if (ciphertext.size > MAX_SYNC_CIPHERTEXT_BYTES) {
                                respond(
                                    "{\"error\":{\"code\":\"request_too_large\",\"message\":\"请求内容过大\"}}",
                                    HttpStatusCode.PayloadTooLarge,
                                    headers,
                                )
                            } else {
                                acceptedUploads++
                                remote = SyncResponse(put.baseVersion + 1, put.payload, 2_000)
                                respond(json.encodeToString(remote), headers = headers)
                            }
                        }
                    }
                },
            )
    }

    private class Fixture(
        api: AccountApi,
    ) {
        private val settings = MapSettings()
        val personal = PersonalLibraryRepository(settings)
        val registry = ServerRegistry(settings, TestSecureStore(), personal = personal)
        val media = HttpClient(MockEngine { error("Unexpected media request") })
        val account =
            AccountRepository(
                api,
                TestSecureStore(),
                VaultCrypto(),
                registry,
                ThemePreferences(settings),
                UserAgentPreferences(settings),
                WatchTogetherPreferences(settings),
                DanmakuPreferences(settings),
                SkipSegmentPreferences(settings),
                ServerSyncManager(EmbyRepository(media), registry, settings),
                nowEpochMs = { 1_000 },
                personal = personal,
            )
    }
}
