package com.yfuse.tv.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvSettingsSearchTest {
    @Test
    fun `a blank query matches nothing`() {
        assertTrue(searchTvSettings("").isEmpty())
        assertTrue(searchTvSettings("   ").isEmpty())
    }

    @Test
    fun `the settings root is never a search result`() {
        val everything =
            TvSettingsPage.entries.flatMap { page ->
                searchTvSettings(page.title)
            }
        assertFalse(TvSettingsPage.Root in everything)
    }

    @Test
    fun `a title match finds its page`() {
        assertEquals(
            listOf(TvSettingsPage.Downloads),
            searchTvSettings("下载与离线库"),
        )
    }

    @Test
    fun `keywords find pages whose title does not contain the word`() {
        // Subtitles are configured on the danmaku page, which does not say 字幕 in its title.
        assertTrue(TvSettingsPage.Danmaku in searchTvSettings("字幕"))
        // Someone hunting for hardware decoding types 硬解, not 高级播放.
        assertTrue(
            TvSettingsPage.AdvancedPlayback in searchTvSettings("硬解"),
        )
        // Migrating to a new box is 换机 to the person doing it.
        assertTrue(
            TvSettingsPage.ServerBackup in searchTvSettings("换机"),
        )
    }

    @Test
    fun `matching ignores case for latin keywords`() {
        assertEquals(
            searchTvSettings("YCORE"),
            searchTvSettings("ycore"),
        )
        assertTrue(
            TvSettingsPage.AdvancedPlayback in searchTvSettings("YCore"),
        )
    }

    @Test
    fun `unimplemented resource integration is not searchable`() {
        assertTrue(searchTvSettings("转存").isEmpty())
        assertTrue(searchTvSettings("tgto").isEmpty())
    }

    @Test
    fun `an unrelated query returns nothing rather than everything`() {
        assertTrue(searchTvSettings("紫色的大象").isEmpty())
    }

    @Test
    fun `every page except the root is reachable by at least one query`() {
        val reachable =
            TvSettingsPage.entries
                .filter { it != TvSettingsPage.Root }
                .filter { page ->
                    searchTvSettings(page.title).contains(page)
                }
        assertEquals(TvSettingsPage.entries.size - 1, reachable.size)
    }
}
