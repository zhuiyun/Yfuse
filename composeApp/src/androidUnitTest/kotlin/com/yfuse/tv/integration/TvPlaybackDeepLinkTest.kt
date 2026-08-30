package com.yfuse.tv.integration

import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.SavedServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvPlaybackDeepLinkTest {
    @Test
    fun roundTripKeepsOnlyOpaqueLaneAndEncodedItem() {
        val identity = identity(itemId = "episode/中文 ?#")

        val value = TvPlaybackDeepLinkCodec.encode(identity, positionMs = 42_123L)
        val decoded = assertNotNull(TvPlaybackDeepLinkCodec.decode(value))

        assertEquals(identity.scope.provider, decoded.provider)
        assertEquals(identity.scope.opaqueLaneId, decoded.opaqueLaneId)
        assertEquals(identity.itemId, decoded.itemId)
        assertEquals(42_123L, decoded.positionMs)
        assertFalse(value.contains(identity.scope.serverId))
        assertFalse(value.contains(identity.scope.profileId))
        assertFalse(value.contains("access_token", ignoreCase = true))
    }

    @Test
    fun equalItemIdsRemainIsolatedByProviderServerAndProfile() {
        val first = identity(itemId = "same")
        val second =
            first.copy(
                scope = first.scope.copy(profileId = "profile-b"),
            )
        val third =
            first.copy(
                scope = first.scope.copy(provider = TvMediaProvider.Plex),
            )

        assertNotEquals(first.platformId, second.platformId)
        assertNotEquals(first.platformId, third.platformId)
        assertNotEquals(first.scope.opaqueLaneId, second.scope.opaqueLaneId)
    }

    @Test
    fun resolverAcceptsPreviousServerIdentityButReturnsCurrentAuthenticatedIdentity() {
        val previousId = "https://old.example#profile-a"
        val server = savedServer(id = "stable-server", previousIds = setOf(previousId))
        val oldIdentity =
            ContinueWatchingIdentity(
                scope = ContinueWatchingScope(TvMediaProvider.Emby, previousId, server.userId),
                itemId = "movie-7",
            )
        val value = TvPlaybackDeepLinkCodec.encode(oldIdentity, 123_000L)

        val resolved = assertNotNull(TvPlaybackDeepLinkResolver { listOf(server) }.resolve(value))

        assertEquals(server.id, resolved.serverId)
        assertEquals(server.userId, resolved.profileId)
        assertEquals("movie-7", resolved.itemId)
        assertEquals(123_000L, resolved.positionMs)
    }

    @Test
    fun resolverRejectsAmbiguousOrWrongProfileLanes() {
        val server = savedServer(id = "server-a")
        val route = TvPlaybackDeepLinkCodec.encode(identity(), 1L)
        val duplicate = server.copy(baseUrl = "https://duplicate.example")
        val wrongProfileRoute =
            TvPlaybackDeepLinkCodec.encode(
                identity().copy(scope = identity().scope.copy(profileId = "profile-other")),
                1L,
            )

        assertNull(TvPlaybackDeepLinkResolver { listOf(server, duplicate) }.resolve(route))
        assertNull(TvPlaybackDeepLinkResolver { listOf(server) }.resolve(wrongProfileRoute))
    }

    @Test
    fun decoderRejectsExtraOrDuplicateQueryFieldsAndMalformedEscapes() {
        val valid = TvPlaybackDeepLinkCodec.encode(identity(), 10L)

        assertNull(TvPlaybackDeepLinkCodec.decode("$valid&token=secret"))
        assertNull(TvPlaybackDeepLinkCodec.decode("$valid&p=11"))
        assertNull(TvPlaybackDeepLinkCodec.decode(valid.replace("item-1", "%ZZ")))
        assertNull(TvPlaybackDeepLinkCodec.decode(valid.replace("?p=10", "#token")))
    }

    @Test
    fun artworkSanitizerDropsCredentialsAndUnknownQueryParameters() {
        val sanitized =
            sanitizeTvArtworkUri(
                "https://media.example/Items/7/Images/Primary?maxWidth=480&api_key=secret&tag=abc#fragment",
            )
        assertNull(sanitized)

        val safe =
            assertNotNull(
                sanitizeTvArtworkUri(
                    "https://media.example/Items/7/Images/Primary?maxWidth=480&api_key=secret&" +
                        "url=https%3A%2F%2Fprivate.example%2Fimage%3Ftoken%3Dnested&tag=abc",
                ),
            )
        assertTrue("maxWidth=480" in safe)
        assertTrue("tag=abc" in safe)
        assertFalse("api_key" in safe)
        assertFalse("secret" in safe)
        assertFalse("private.example" in safe)
        assertFalse("nested" in safe)
    }

    private fun identity(itemId: String = "item-1"): ContinueWatchingIdentity =
        ContinueWatchingIdentity(
            scope = ContinueWatchingScope(TvMediaProvider.Emby, "server-a", "profile-a"),
            itemId = itemId,
        )

    private fun savedServer(
        id: String,
        previousIds: Set<String> = emptySet(),
    ): SavedServer =
        SavedServer(
            id = id,
            baseUrl = "https://media.example",
            serverName = "Home",
            userId = "profile-a",
            userName = "Viewer",
            accessToken = "must-never-enter-a-deep-link",
            kind = MediaServerKind.Emby,
            previousIds = previousIds,
        )
}
