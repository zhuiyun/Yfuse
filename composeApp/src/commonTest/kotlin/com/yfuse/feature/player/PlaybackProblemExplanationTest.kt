package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackFailureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackProblemExplanationTest {
    @Test fun unknown_error_is_not_guessed_from_message() {
        assertEquals("阶段未确定", explainPlaybackProblem(PlaybackState(error = "decoder network renderer timeout")).stage)
    }

    @Test fun structured_stage_takes_precedence_over_buffering() {
        assertEquals(
            "声音输出",
            explainPlaybackProblem(PlaybackState(buffering = true, errorKind = PlaybackFailureKind.AudioSink)).stage,
        )
        assertTrue(explainPlaybackProblem(PlaybackState(buffering = true)).advice.contains("不能单独证明网络故障"))
    }
}
