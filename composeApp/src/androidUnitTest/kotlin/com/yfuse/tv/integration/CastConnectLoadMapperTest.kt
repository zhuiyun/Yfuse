package com.yfuse.tv.integration

import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.SavedServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CastConnectLoadMapperTest {
    @Test
    fun intentClassifierRecognizesOfficialActionsAndOnlyValidYfuseLinks() {
        val deepLink = TvPlaybackDeepLinkCodec.encode(identity(), 10L)

        assertEquals(CastConnectIntentKind.Launch, classifyCastConnectIntent(CAST_CONNECT_LAUNCH_ACTION, null))
        assertEquals(CastConnectIntentKind.Load, classifyCastConnectIntent(CAST_CONNECT_LOAD_ACTION, null))
        assertEquals(CastConnectIntentKind.YfusePlaybackDeepLink, classifyCastConnectIntent(null, deepLink))
        assertEquals(CastConnectIntentKind.Other, classifyCastConnectIntent(null, "yfuse://tv/play/token"))
    }

    @Test
    fun deepLinkEntityWinsAndKeepsItsResumePointWhenLoadUsesDefaultZero() {
        val deepLink = TvPlaybackDeepLinkCodec.encode(identity(), 55_000L)
        val request =
            CastConnectLoadMapper.map(
                envelope(
                    entity = deepLink,
                    contentId = "https://media.example/direct.mp4?api_key=transient",
                    positionMs = 0L,
                    credentialsSupplied = true,
                ),
            )

        val mapped = requireNotNull(request)
        val source = assertIs<CastConnectPlaybackSource.YfuseDeepLink>(mapped.source)
        assertEquals("item", source.route.itemId)
        assertEquals(55_000L, mapped.positionMs)
        assertTrue(mapped.credentialsSupplied)
        assertFalse(mapped.toString().contains("credential-value"))
    }

    @Test
    fun explicitLoadPositionOverridesDeepLinkAndUnassignedNormalizesToZero() {
        val deepLink = TvPlaybackDeepLinkCodec.encode(identity(), 55_000L)
        val override =
            requireNotNull(
                CastConnectLoadMapper.map(
                    envelope(entity = deepLink, positionMs = 99_000L),
                ),
            )
        val direct =
            requireNotNull(
                CastConnectLoadMapper.map(
                    envelope(contentId = "https://media.example/movie.mp4", positionMs = -1L),
                ),
            )

        assertEquals(99_000L, override.positionMs)
        assertEquals(0L, direct.positionMs)
    }

    @Test
    fun directMediaAcceptsOnlyBoundedHttpUrlsWithoutUserInfoOrFragments() {
        val accepted =
            requireNotNull(
                CastConnectLoadMapper.map(
                    envelope(contentId = "https://media.example/video.m3u8?api_key=transient"),
                ),
            )
        assertIs<CastConnectPlaybackSource.DirectMedia>(accepted.source)

        assertNull(CastConnectLoadMapper.map(envelope(contentId = "file:///data/movie.mp4")))
        assertNull(CastConnectLoadMapper.map(envelope(contentId = "https://user:secret@media.example/movie.mp4")))
        assertNull(CastConnectLoadMapper.map(envelope(contentId = "https://media.example/movie.mp4#token")))
        assertNull(CastConnectLoadMapper.map(envelope(contentId = "https://media.example/movie.mp4", positionMs = -2L)))
    }

    @Test
    fun hostResolverMapsDeepLinkThroughCurrentServerRegistry() {
        val server = savedServer()
        val uri = TvPlaybackDeepLinkCodec.encode(identity(), 55_000L)
        val request =
            CastConnectPlaybackRequest(
                senderId = "sender",
                source =
                    CastConnectPlaybackSource.YfuseDeepLink(
                        uri = uri,
                        route = requireNotNull(TvPlaybackDeepLinkCodec.decode(uri)),
                    ),
                contentType = "video/mp4",
                title = "Movie",
                autoplay = true,
                positionMs = 88_000L,
                credentialsSupplied = false,
            )

        val action =
            assertIs<CastConnectHostAction.ResolveLibraryPlayback>(
                CastConnectHostActionResolver { listOf(server) }.resolve(request),
            )

        assertEquals(server.id, action.target.serverId)
        assertEquals(server.userId, action.target.profileId)
        assertEquals("item", action.target.itemId)
        assertEquals(88_000L, action.target.positionMs)
    }

    @Test
    fun hostResolverRejectsUnknownLaneAndMismatchedDecodedRoute() {
        val uri = TvPlaybackDeepLinkCodec.encode(identity(), 1L)
        val route = requireNotNull(TvPlaybackDeepLinkCodec.decode(uri))
        val request =
            CastConnectPlaybackRequest(
                senderId = null,
                source = CastConnectPlaybackSource.YfuseDeepLink(uri, route.copy(itemId = "forged")),
                contentType = null,
                title = null,
                autoplay = true,
                positionMs = 1L,
                credentialsSupplied = false,
            )

        assertNull(CastConnectHostActionResolver { listOf(savedServer()) }.resolve(request))
        assertNull(
            CastConnectHostActionResolver { emptyList() }.resolve(
                request.copy(source = CastConnectPlaybackSource.YfuseDeepLink(uri, route)),
            ),
        )
    }

    @Test
    fun hostResolverCreatesRedactedTransientDirectActionWithoutTranscode() {
        val secretUrl = "https://media.example/movie.mp4?api_key=credential-value"
        val request =
            requireNotNull(
                CastConnectLoadMapper.map(
                    envelope(
                        contentId = secretUrl,
                        positionMs = 42_000L,
                        credentialsSupplied = true,
                    ),
                ),
            )

        val action =
            assertIs<CastConnectHostAction.PlayDirect>(
                CastConnectHostActionResolver { emptyList() }.resolve(request),
            )

        assertEquals(secretUrl, action.url)
        assertEquals("Movie", action.title)
        assertEquals(42_000L, action.positionMs)
        assertFalse(action.transcodeAllowed)
        assertEquals(CastConnectActionPersistence.TransientOnly, action.persistence)
        assertFalse(action.toString().contains("credential-value"))
        assertFalse(request.source.toString().contains("credential-value"))
    }

    private fun envelope(
        entity: String? = null,
        contentId: String? = null,
        positionMs: Long = 0L,
        credentialsSupplied: Boolean = false,
    ) =
        CastConnectLoadEnvelope(
            senderId = "sender",
            entity = entity,
            contentId = contentId,
            contentUrl = null,
            contentType = "video/mp4",
            title = " Movie ",
            autoplay = true,
            positionMs = positionMs,
            credentialsSupplied = credentialsSupplied,
        )

    private fun identity() =
        ContinueWatchingIdentity(
            ContinueWatchingScope(TvMediaProvider.Emby, "server", "profile"),
            "item",
        )

    private fun savedServer() =
        SavedServer(
            id = "server",
            baseUrl = "https://media.example",
            serverName = "Home",
            userId = "profile",
            userName = "Viewer",
            accessToken = "local-token",
            kind = MediaServerKind.Emby,
        )
}
