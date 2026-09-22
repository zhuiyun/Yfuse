package com.yfuse.feature.library

import com.yfuse.core.model.MediaContainer
import com.yfuse.core.model.MediaContainerKind
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LibraryContainerRouteTest {
    @Test
    fun container_route_survives_library_navigation_config_serialization() {
        val route =
            LibraryContainerRoute.from(
                MediaContainer(
                    id = "playlist|with punctuation",
                    title = "周末片单",
                    kind = MediaContainerKind.Playlist,
                    serverId = "https://emby.example/emby#user-1",
                ),
            )
        val original = LibraryComponent.Config.Grid(route.encode(), "周末片单")

        val encodedConfig = Json.encodeToString(LibraryComponent.Config.serializer(), original)
        val restored = Json.decodeFromString(LibraryComponent.Config.serializer(), encodedConfig)
        val restoredGrid = assertIs<LibraryComponent.Config.Grid>(restored)
        val restoredRoute = requireNotNull(LibraryContainerRoute.decode(restoredGrid.libraryId))

        assertEquals(route, restoredRoute)
        assertEquals("周末片单", restoredGrid.title)
    }

    @Test
    fun directory_route_round_trips_its_server_and_kind() {
        val route =
            LibraryContainerDirectoryRoute(
                serverId = "server-two",
                kind = MediaContainerKind.BoxSet,
            )

        assertEquals(route, LibraryContainerDirectoryRoute.decode(route.encode()))
    }
}
