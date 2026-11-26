package com.yfuse.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DanmakuProbeTest {
    @Test
    fun resolvesApiRootAndStripsApiV2Suffix() {
        val rootSource = DanmakuSource("1", "主源", "https://danmu.example.com/api/v2/")
        assertEquals(
            "https://danmu.example.com/api/v2/search/anime?keyword=test",
            danmakuProbeUrl(rootSource),
        )
    }

    @Test
    fun resolvesTemplateOriginWithoutSendingPlaceholderTokens() {
        val templateSource = DanmakuSource("2", "模板", "https://xml.example.com:8443/danmu/{id}.xml?ep={episode}")
        assertEquals("https://xml.example.com:8443/", danmakuProbeUrl(templateSource))
    }

    @Test
    fun rejectsNonHttpSchemes() {
        val invalid = DanmakuSource("3", "错误", "file:///tmp/a.xml")
        assertNull(danmakuProbeUrl(invalid))
    }
}
