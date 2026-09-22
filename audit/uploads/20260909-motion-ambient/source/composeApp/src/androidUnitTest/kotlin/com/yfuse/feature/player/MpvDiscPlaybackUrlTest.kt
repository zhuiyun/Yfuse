package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackDiscKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MpvDiscPlaybackUrlTest {
    @Test
    fun bluray_and_bdmv_start_on_the_longest_playlist() {
        assertEquals("bd://longest", mpvDiscPlaybackUrl(PlaybackDiscKind.BluRay))
        assertEquals("bd://longest", mpvDiscPlaybackUrl(PlaybackDiscKind.Bdmv))
    }

    @Test
    fun dvd_keeps_its_native_disc_url_and_non_disc_sources_are_ignored() {
        assertEquals("dvd://", mpvDiscPlaybackUrl(PlaybackDiscKind.Dvd))
        assertNull(mpvDiscPlaybackUrl(PlaybackDiscKind.None))
        assertNull(mpvDiscPlaybackUrl(PlaybackDiscKind.Iso))
    }
}
