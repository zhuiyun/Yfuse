package com.yfuse.core.handoff

import com.russhwolf.settings.MapSettings
import com.yfuse.core.account.AccountApi
import com.yfuse.core.account.AccountRepository
import com.yfuse.core.account.AccountUser
import com.yfuse.core.account.AuthResponse
import com.yfuse.core.account.PlaybackVaultCipher
import com.yfuse.core.account.createAccountClient
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.UserAgentPreferences
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.security.TestSecureStore
import com.yfuse.core.security.VaultCrypto
import com.yfuse.core.security.base64UrlToBytes
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.sync.playback.PlaybackStateRecord
import com.yfuse.core.sync.playback.PlaybackSyncDocument
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HandoffVaultCipherTest {
    @Test
    fun actualAccountVaultSupportsBothCiphersAndRejectsWrongOwnerOrRequest() =
        runTest {
            val secrets = TestSecureStore()
            val crypto = VaultCrypto()
            val settings = MapSettings()
            val registry = ServerRegistry(settings, TestSecureStore())
            val auth =
                AuthResponse(
                    AccountUser("user-1", "viewer", "Viewer", 0, 1, 1),
                    "access",
                    Long.MAX_VALUE,
                    "refresh",
                    Long.MAX_VALUE,
                )
            val accountClient =
                createAccountClient(
                    MockEngine { request ->
                        check(request.url.encodedPath == "/api/v1/auth/register")
                        respond(
                            Json.encodeToString(auth),
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                )
            val embyClient = HttpClient(MockEngine { error("No media access expected") })
            try {
                val repository =
                    AccountRepository(
                        AccountApi(accountClient),
                        secrets,
                        crypto,
                        registry,
                        ThemePreferences(settings),
                        UserAgentPreferences(settings),
                        WatchTogetherPreferences(settings),
                        DanmakuPreferences(settings),
                        SkipSegmentPreferences(settings),
                        ServerSyncManager(EmbyRepository(embyClient), registry, settings),
                    )
                assertTrue(repository.register("viewer", "a-test-password-123".toCharArray(), "Viewer", 0).isSuccess)
                // These values are written by real AccountRepository.storeVault, not fixture-only aliases.
                assertEquals("user-1", secrets.get("vault_user_id")?.decodeToString())
                assertEquals(32, secrets.get("vault_key")?.size)
                val playbackCipher = PlaybackVaultCipher(repository, secrets, crypto)
                val playbackDocument =
                    PlaybackSyncDocument(state = PlaybackStateRecord("tmdb:603", deviceId = "device"))
                assertNotNull(playbackCipher.encrypt(playbackDocument, "mutation"))
                val cipher = HandoffVaultCipher(repository, secrets, crypto)
                val media =
                    HandoffMedia("tmdb:603", "A private title", "server", "item", 3_000, 60_000, mediaSourceId = "4k")
                val envelope = cipher.encrypt("request-1", media)
                assertFalse(
                    envelope.ciphertext
                        .base64UrlToBytes()
                        .decodeToString()
                        .contains(media.title),
                )
                assertEquals(media, cipher.decrypt("request-1", envelope))
                assertFails { cipher.decrypt("another-request", envelope) }
                secrets.put("vault_user_id", "other-user".encodeToByteArray())
                assertFails { cipher.encrypt("request-1", media) }
                assertFails { cipher.decrypt("request-1", envelope) }
            } finally {
                accountClient.close()
                embyClient.close()
                secrets.clear()
            }
        }
}
