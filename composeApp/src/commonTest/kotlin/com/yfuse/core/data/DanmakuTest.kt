package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DanmakuTest {

    @Test
    fun preferences_survive_recreation() {
        val settings = MapSettings()
        DanmakuPreferences(settings).apply {
            setUrlTemplate("https://example.com/{title}/{id}")
            setEnabled(false)
            setDisplayArea(DanmakuDisplayArea.ThreeQuarters)
            setFontSize(DanmakuFontSize.Large)
            setSpeed(DanmakuSpeed.Fast)
            setOpacity(DanmakuOpacity.High)
        }

        val restored = DanmakuPreferences(settings)

        assertEquals("https://example.com/{title}/{id}", restored.urlTemplate.value)
        assertFalse(restored.enabled.value)
        assertEquals(DanmakuDisplayArea.ThreeQuarters, restored.displayArea.value)
        assertEquals(DanmakuFontSize.Large, restored.fontSize.value)
        assertEquals(DanmakuSpeed.Fast, restored.speed.value)
        assertEquals(DanmakuOpacity.High, restored.opacity.value)
    }

    @Test
    fun resolves_and_encodes_media_placeholders() {
        val url = DanmakuRepository.resolveUrl(
            "https://example.com/{serverId}/{id}?title={title}&season={season}&episode={episode}",
            DanmakuMedia(
                id = "a/b",
                title = "测试 标题",
                episode = 7,
                season = 2,
                serverId = "server 1",
            ),
        )

        assertEquals(
            "https://example.com/server%201/a%2Fb?title=%E6%B5%8B%E8%AF%95%20%E6%A0%87%E9%A2%98&season=2&episode=7",
            url,
        )
    }

    @Test
    fun parses_bilibili_xml() {
        val comments = DanmakuParser.parse(
            """
            <i>
              <d p="1.5,1,25,16711680,0,0,0,0">滚动 &amp; 测试</d>
              <d p="2.0,5,25,16777215,0,0,0,0">顶部</d>
              <d p="3.0,4,25,255,0,0,0,0">底部</d>
            </i>
            """.trimIndent(),
        )

        assertEquals(3, comments.size)
        assertEquals(1_500L, comments[0].timeMs)
        assertEquals("滚动 & 测试", comments[0].text)
        assertEquals(0xFF0000, comments[0].color)
        assertEquals(DanmakuKind.Top, comments[1].kind)
        assertEquals(DanmakuKind.Bottom, comments[2].kind)
    }

    @Test
    fun parses_dplayer_and_common_json() {
        val comments = DanmakuParser.parse(
            """
            {
              "data": [
                [1.25, 0, 16777215, "alice", "滚动"],
                [2.5, 1, 65280, "bob", "顶部"]
              ],
              "comments": [
                {"progress": 3750, "content": "对象格式", "mode": 4, "color": "#0000ff"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(3, comments.size)
        assertEquals(listOf(1_250L, 2_500L, 3_750L), comments.map { it.timeMs })
        assertEquals(DanmakuKind.Top, comments[1].kind)
        assertEquals(DanmakuKind.Bottom, comments[2].kind)
        assertEquals(0x0000FF, comments[2].color)
        assertTrue(comments.all { it.text.isNotBlank() })
    }
}
