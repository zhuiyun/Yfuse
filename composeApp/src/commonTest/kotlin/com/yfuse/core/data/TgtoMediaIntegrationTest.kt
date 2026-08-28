package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.security.TestSecureStore
import com.yfuse.feature.profile.normalize123Channels
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TgtoMediaIntegrationTest {
    @Test
    fun default123ChannelsArePublicChannelPages() {
        assertEquals(5, DEFAULT_123_CHANNELS.size)
        assertTrue(DEFAULT_123_CHANNELS.all { it.startsWith("https://t.me/") && it.count { char -> char == '/' } == 3 })
    }

    @Test
    fun customChannelsAreValidatedAndDeduplicated() {
        val channels =
            normalize123Channels(
                """
                https://t.me/regeng123
                https://t.me/regeng123
                @custom_channel
                https://t.me/regeng123/9579
                https://example.com/not-telegram
                """.trimIndent(),
            )

        assertEquals(listOf("https://t.me/regeng123", "@custom_channel"), channels)
    }

    @Test
    fun mediaItemMapsFullTmdbImageUrlsBackToPaths() {
        val mapped =
            TgtoMediaItem(
                externalId = "687163",
                title = "挽救计划",
                posterUrl = "https://image.tmdb.org/t/p/w500/qf8HaEGNUFXU6nVAq9BVEk4Tiqb.jpg",
                backdropUrl = "https://image.tmdb.org/t/p/w1280/8Tfys3mDZVp4tNoH2ktm06a0Tau.jpg",
                year = "2026",
            ).toTmdbItem()

        assertEquals(687163, mapped?.id)
        assertEquals("/qf8HaEGNUFXU6nVAq9BVEk4Tiqb.jpg", mapped?.posterPath)
        assertEquals("/8Tfys3mDZVp4tNoH2ktm06a0Tau.jpg", mapped?.backdropPath)
    }

    @Test
    fun mediaItemMapsToEmbyCardLookupTarget() {
        val target =
            TgtoMediaItem(
                mediaType = "tv",
                tmdbId = 330250,
                title = "测试剧集",
                originalTitle = "Test Series",
                year = "2026",
                status = "Returning Series",
                numberOfEpisodes = 12,
            ).toEmbyCardTarget()

        assertEquals("tv:330250", target?.key)
        assertEquals("330250", target?.tmdbId)
        assertEquals(12, target?.numberOfEpisodes)
        assertEquals("Returning Series", target?.seriesStatus)
    }

    @Test
    fun directoryListingParsesTheDirectSelectorResponse() {
        val listing =
            Json.decodeFromString<TgtoDirectoryListing>(
                """{"success":true,"count":1,"parent_id":"0","provider":"123","items":[{"id":"75543827","name":"影视库","parent_id":"0","provider":"123"}]}""",
            )

        assertTrue(listing.success)
        assertEquals("0", listing.parentId)
        assertEquals("75543827", listing.items.single().id)
        assertEquals("影视库", listing.items.single().name)
    }

    @Test
    fun pan123ShareLinksParseBothSupportedShapes() {
        assertEquals(
            Pan123ShareLink("RWJUVv-aqF3v", "DGQH"),
            parsePan123ShareLink("https://1813278387.share.123pan.cn/123pan/RWJUVv-aqF3v?pwd=DGQH"),
        )
        assertEquals(
            Pan123ShareLink("abc_DEF-123", ""),
            parsePan123ShareLink("https://www.123865.com/s/abc_DEF-123"),
        )
    }

    @Test
    fun passwordIsEncryptedStoreOnlyAndBlankSaveKeepsIt() {
        val settings = MapSettings()
        val secureStore = TestSecureStore()
        val preferences = TgtoMediaPreferences(settings, secureStore)

        assertFalse(preferences.connection.value.hasPassword)
        preferences.save("http://example.test/", "viewer", "secret")
        preferences.save("http://example.test/new/", "viewer", "")

        assertEquals("http://example.test/new", preferences.connection.value.endpoint)
        assertEquals("secret", preferences.password())
        assertTrue(preferences.connection.value.hasPassword)
        assertFalse(settings.keys.contains("password"))
    }

    @Test
    fun discoveryHomeChoicePersistsWithoutClearingConnection() {
        val settings = MapSettings()
        val secureStore = TestSecureStore()
        val preferences = TgtoMediaPreferences(settings, secureStore)
        preferences.save("https://tgto.example.test", "viewer", "secret")

        preferences.setDiscoveryHomeEnabled(false)

        val restored = TgtoMediaPreferences(settings, secureStore)
        assertFalse(restored.discoveryHomeEnabled.value)
        assertTrue(restored.connection.value.hasPassword)
        assertEquals("secret", restored.password())
    }

    @Test
    fun concurrentUnauthorizedResponsesShareOneLogin() =
        runTest {
            val preferences = TgtoMediaPreferences(MapSettings(), TestSecureStore())
            preferences.save("https://tgto.example.test", "viewer", "secret")
            val initialSettingsRequests = AtomicInteger()
            val loginRequests = AtomicInteger()
            val bothInitialRequestsStarted = CompletableDeferred<Unit>()
            val client =
                createTgtoMediaClient(
                    MockEngine { request ->
                        when (request.url.encodedPath) {
                            "/api/login" -> {
                                loginRequests.incrementAndGet()
                                jsonResponse("""{"success":true}""")
                            }
                            "/api/media/settings" -> {
                                if (loginRequests.get() == 0) {
                                    if (initialSettingsRequests.incrementAndGet() == 2) {
                                        bothInitialRequestsStarted.complete(Unit)
                                    }
                                    bothInitialRequestsStarted.await()
                                    jsonResponse("""{"success":false}""", HttpStatusCode.Unauthorized)
                                } else {
                                    jsonResponse("""{"success":true,"data":{"configured":true}}""")
                                }
                            }
                            else -> error("Unexpected request ${request.url}")
                        }
                    },
                )
            val repository = TgtoMediaRepository(preferences, client)

            val results =
                coroutineScope {
                    listOf(
                        async { repository.settings() },
                        async { repository.settings() },
                    ).awaitAll()
                }

            assertTrue(results.all(Result<TgtoSettings>::isSuccess))
            assertEquals(1, loginRequests.get())
        }

    @Test
    fun pan123TokenPersistsInTheEncryptedStoreUntilExplicitlyCleared() {
        val settings = MapSettings()
        val secureStore = TestSecureStore()
        val preferences = TgtoMediaPreferences(settings, secureStore)

        preferences.savePan123Authorization("13800138000", "saved-token")

        val restored = TgtoMediaPreferences(settings, secureStore)
        assertEquals("13800138000", restored.pan123Authorization.value.phone)
        assertTrue(restored.pan123Authorization.value.hasToken)
        assertEquals("saved-token", restored.pan123Token())
        assertFalse(settings.keys.contains("pan123.token"))

        restored.clearPan123Authorization()
        assertFalse(restored.pan123Authorization.value.hasToken)
        assertEquals("", restored.pan123Token())
    }

    @Test
    fun pan123LoginPersistsTokenAndVerifiesItAgainstTheDrive() =
        runTest {
            val preferences = TgtoMediaPreferences(MapSettings(), TestSecureStore())
            var driveAuthorization: String? = null
            val client =
                Pan123DirectClient(
                    preferences,
                    createTgtoMediaClient(
                        MockEngine { request ->
                            when (request.url.encodedPath) {
                                "/api/user/sign_in" ->
                                    jsonResponse(
                                        """{"code":0,"message":"ok","data":{"token":"token-123"}}""",
                                    )
                                "/api/file/list" -> {
                                    driveAuthorization = request.headers[HttpHeaders.Authorization]
                                    jsonResponse(
                                        """{"code":0,"message":"ok","data":{"InfoList":[{"FileId":75543827,"FileName":"影视库","ParentFileId":0,"Type":1,"Trashed":false}],"Next":-1}}""",
                                    )
                                }
                                else -> error("Unexpected request ${request.url}")
                            }
                        },
                    ),
                )

            val listing = client.authorize("13800138000", "test-password")

            assertEquals("影视库", listing.items.single().name)
            assertEquals("Bearer token-123", driveAuthorization)
            assertTrue(preferences.pan123Authorization.value.hasToken)
            assertEquals("token-123", preferences.pan123Token())
        }

    @Test
    fun pan123TransferUsesDirectShareCopyAndPollEndpoints() =
        runTest {
            val preferences = TgtoMediaPreferences(MapSettings(), TestSecureStore())
            preferences.savePan123Authorization("13800138000", "token-123")
            val requestedPaths = mutableListOf<String>()
            val client =
                Pan123DirectClient(
                    preferences,
                    createTgtoMediaClient(
                        MockEngine { request ->
                            requestedPaths += request.url.encodedPath
                            when (request.url.encodedPath) {
                                "/api/share/get" ->
                                    jsonResponse(
                                        """{"code":0,"message":"ok","data":{"InfoList":[{"FileId":7,"FileName":"movie.mkv","Etag":"etag","Size":1024,"Type":0}],"Next":-1}}""",
                                    )
                                "/b/api/restful/goapi/v1/file/copy/save" ->
                                    jsonResponse("""{"code":0,"message":"ok","data":{"taskID":"copy-task"}}""")
                                "/b/api/restful/goapi/v1/file/copy/save/get" ->
                                    jsonResponse("""{"code":0,"message":"ok","data":{"status":2,"currentCount":1}}""")
                                else -> error("Unexpected request ${request.url}")
                            }
                        },
                    ),
                )

            val result =
                client.transfer(
                    "https://1813278387.share.123pan.cn/123pan/RWJUVv-aqF3v?pwd=DGQH",
                    "75543827",
                )

            assertEquals("转存成功", result)
            assertEquals(
                listOf(
                    "/api/share/get",
                    "/b/api/restful/goapi/v1/file/copy/save",
                    "/b/api/restful/goapi/v1/file/copy/save/get",
                ),
                requestedPaths,
            )
        }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.jsonResponse(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )
}
