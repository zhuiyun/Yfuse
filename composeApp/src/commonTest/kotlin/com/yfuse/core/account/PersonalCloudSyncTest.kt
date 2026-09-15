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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
