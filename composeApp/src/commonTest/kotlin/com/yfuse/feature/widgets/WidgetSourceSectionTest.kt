package com.yfuse.feature.widgets

import com.yfuse.core.widget.WidgetMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetSourceSectionTest {
    @Test
    fun metadataLinksDoNotLoadSourceDetailsButRealRoutesDo() {
        assertFalse(
            WidgetMedia(
                "1",
                "Movie",
                link = "https://www.themoviedb.org/movie/299534?language=zh",
            ).hasWidgetPlaybackSource(),
        )
        assertTrue(WidgetMedia("1", "Movie", link = "https://source.example/film/1").hasWidgetPlaybackSource())
        assertTrue(WidgetMedia("1", "Movie", videoUrl = "https://source.example/movie.m3u8").hasWidgetPlaybackSource())
    }

    @Test
    fun sourceGroupsRemainSelectableAndInheritHeadersWithoutMixingSiblings() {
        val media =
            WidgetMedia(
                "show",
                "Show",
                headers = mapOf("Referer" to "root"),
                poster = "https://source.example/poster.jpg",
                backdrop = "https://source.example/backdrop.jpg",
                children =
                    listOf(
                        WidgetMedia(
                            "line-a",
                            "线路 A",
                            headers = mapOf("Referer" to "line-a"),
                            season = 2,
                            children =
                                listOf(
                                    WidgetMedia(
                                        "ep-1",
                                        "第 1 集",
                                        videoUrl = "https://a.example/1.m3u8",
                                        episode = 1,
                                        headers =
                                            mapOf(
                                                "User-Agent" to "source",
                                            ),
                                    ),
                                ),
                        ),
                        WidgetMedia("line-b", "线路 B", videoUrl = "https://b.example/1.m3u8"),
                    ),
            )
        val routes = media.widgetPlaybackRoutes()
        assertEquals(listOf("线路 A · 第 1 集", "线路 B"), routes.map { it.title })
        assertEquals(mapOf("Referer" to "line-a", "User-Agent" to "source"), routes[0].headers)
        assertEquals(mapOf("Referer" to "root"), routes[1].headers)
        assertEquals("https://a.example/1.m3u8", routes[0].videoUrl)
        assertEquals(media.poster, routes[0].poster)
        assertEquals(media.backdrop, routes[0].backdrop)
        assertEquals(2, routes[0].season)
        assertEquals(1, routes[0].episode)
        assertEquals(null, routes[1].season)
    }

    @Test
    fun largeEpisodeListsHaveABoundedHorizontalRail() {
        val media =
            WidgetMedia(
                "show",
                "Show",
                children =
                    List(700) {
                        WidgetMedia("$it", "Episode $it", videoUrl = "https://source.example/$it")
                    },
            )
        assertEquals(500, media.widgetPlaybackRoutes().size)
    }
}
