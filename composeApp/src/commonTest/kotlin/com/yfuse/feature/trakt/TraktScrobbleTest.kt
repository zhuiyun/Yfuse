package com.yfuse.feature.trakt

import com.yfuse.core.trakt.TraktPlaybackAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TraktScrobbleTest {
    @Test
    fun nothingIsSaidBeforePlaybackReallyStarts() {
        val scrobble = TraktScrobble()
        assertNull(scrobble.onPlayback(playing = false, completed = false))
        assertNull(scrobble.onLeave())
    }

    @Test
    fun aScrobbleStartsPausesResumesAndStopsOnceAtTheEnd() {
        val scrobble = TraktScrobble()
        assertEquals(TraktPlaybackAction.Start, scrobble.onPlayback(playing = true, completed = false))
        assertEquals(TraktPlaybackAction.Pause, scrobble.onPlayback(playing = false, completed = false))
        assertEquals(TraktPlaybackAction.Start, scrobble.onPlayback(playing = true, completed = false))
        assertEquals(TraktPlaybackAction.Stop, scrobble.onPlayback(playing = false, completed = true))
        // Finished: neither more playback, nor leaving the player, says anything again.
        assertNull(scrobble.onPlayback(playing = true, completed = false))
        assertNull(scrobble.onPlayback(playing = false, completed = true))
        assertNull(scrobble.onLeave())
    }

    @Test
    fun leavingMidwayStopsTheOpenScrobble() {
        val scrobble = TraktScrobble()
        scrobble.onPlayback(playing = true, completed = false)
        assertEquals(TraktPlaybackAction.Stop, scrobble.onLeave())
    }

    @Test
    fun anEndReachedBeforeAnyPlayingMomentStartsRatherThanStops() {
        // A completed flag with nothing started yet is not a finished scrobble.
        val scrobble = TraktScrobble()
        assertEquals(TraktPlaybackAction.Start, scrobble.onPlayback(playing = true, completed = true))
        assertEquals(TraktPlaybackAction.Stop, scrobble.onPlayback(playing = false, completed = true))
    }
}
