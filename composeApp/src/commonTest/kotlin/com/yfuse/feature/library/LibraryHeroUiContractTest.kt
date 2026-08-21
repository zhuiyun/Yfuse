package com.yfuse.feature.library

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryHeroUiContractTest {
    @Test
    fun library_hero_matches_the_compact_play_first_contract() {
        assertEquals(500f, libraryHeroHeight(640.dp, wideLayout = false).value, 0.01f)
        assertEquals(640f, libraryHeroHeight(915.dp, wideLayout = false).value, 0.01f)
        assertEquals(560f, libraryHeroHeight(800.dp, wideLayout = true).value, 0.01f)
        assertEquals(760f, libraryHeroHeight(1_500.dp, wideLayout = true).value, 0.01f)
        assertEquals("播放影片", libraryHeroPresentation.playActionLabel)
    }
}
