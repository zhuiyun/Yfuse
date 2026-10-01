package com.yfuse.core.designsystem

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZoomBackNavigationTest {
    /** What a tab's saved state hands the host made for its stack when the tab comes back. */
    private fun leaveAndReturn(origins: Map<String, ZoomOrigin>): SnapshotStateMap<String, ZoomOrigin> {
        val shown = mutableStateMapOf<String, ZoomOrigin>().apply { putAll(origins) }
        val saved = with(ZoomOriginsSaver) { SaverScope { true }.save(shown) }
        return requireNotNull(ZoomOriginsSaver.restore(requireNotNull(saved)))
    }

    @Test
    fun aDetailOpenedFromAPosterStillGoesBackIntoItAfterATabRoundTrip() {
        val poster = MediaSharedElementKey("server", "film")
        val related = MediaSharedElementKey("server", "sequel", kind = "poster")
        val page = Size(1080f, 2400f)
        val restored =
            leaveAndReturn(
                mapOf(
                    "home:Detail(film)" to ZoomOrigin(poster, "home:Home", page),
                    "home:Detail(sequel)" to ZoomOrigin(related, "home:Detail(film)", page),
                ),
            )

        assertEquals(setOf("home:Detail(film)", "home:Detail(sequel)"), restored.keys)
        val film = requireNotNull(restored["home:Detail(film)"])
        assertEquals(poster, film.key)
        assertEquals("home:Home", film.underlay)
        assertEquals(page, film.pageSize)
        val sequel = requireNotNull(restored["home:Detail(sequel)"])
        assertEquals(related, sequel.key)
        assertEquals("home:Detail(film)", sequel.underlay)
    }

    @Test
    fun aTitleWithNoServerAndAnUnmeasuredPageKeepsItsOrigin() {
        val poster = MediaSharedElementKey(null, "local")
        val restored = leaveAndReturn(mapOf("library:Detail(local)" to ZoomOrigin(poster, "library:Grid", Size.Zero)))

        val origin = requireNotNull(restored["library:Detail(local)"])
        assertEquals(poster, origin.key)
        assertEquals(Size.Zero, origin.pageSize)
    }

    @Test
    fun aPageLetGoPastThePointOfNoReturnIsLeaving() {
        val controller = ZoomBackController(TestScope())
        controller.page = Size(1000f, 2000f)
        assertFalse(controller.leaving)

        assertTrue(controller.startPull(Offset(500f, 100f)))
        controller.movePull(Offset(500f, 1000f))
        assertFalse(controller.leaving, "still under the finger")

        controller.releasePull(Offset.Zero)
        assertTrue(controller.leaving)
    }

    @Test
    fun aPageSpringingHomeIsNotLeaving() {
        val controller = ZoomBackController(TestScope())
        controller.page = Size(1000f, 2000f)

        assertTrue(controller.startPull(Offset(500f, 100f)))
        controller.movePull(Offset(500f, 130f))
        controller.releasePull(Offset.Zero)

        assertEquals(ZoomBackPhase.Returning, controller.phase)
        assertFalse(controller.leaving)
    }
}
