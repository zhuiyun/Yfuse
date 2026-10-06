package com.yfuse.core2.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class YSamiBoundsTest {
    @Test
    fun duplicateStartsAndBlankClearsUseTheNextStrictlyLaterTimestamp() {
        val cues =
            YTextSubtitleParser
                .parse(
                    "<SAMI><BODY><SYNC Start=1000><P Class=EN>A<SYNC Start=1000><P Class=EN>B<SYNC Start=2000><P Class=EN>&nbsp;</BODY></SAMI>",
                    YSubtitleFormat.Smi,
                ).cues
        assertEquals(listOf(2_000_000L, 2_000_000L), cues.map { it.endUs })
    }

    @Test
    fun aLargeFileIsLinearAfterSortingAndStillCancellable() {
        val text = buildString { repeat(20_000) { append("<SYNC Start=${it * 1000}><P Class=EN>Caption$it") } }
        var checkpoints = 0
        val cues = YTextSubtitleParser.parse(text, YSubtitleFormat.Smi) { checkpoints++ }.cues
        assertEquals(20_000, cues.size)
        assertEquals(19_999_000_000L, cues.last().startUs)
        // The last phase must also call cancellation, not just token discovery.
        val cancelAt = checkpoints - 5
        checkpoints = 0
        assertFails {
            YTextSubtitleParser.parse(text, YSubtitleFormat.Smi) {
                if (++checkpoints >
                    cancelAt
                ) {
                    error("cancel")
                }
            }
        }
    }
}
