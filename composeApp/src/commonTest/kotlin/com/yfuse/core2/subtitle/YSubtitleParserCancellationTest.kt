package com.yfuse.core2.subtitle

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class YSubtitleParserCancellationTest {
    @Test
    fun large_srt_and_ass_scripts_stop_during_parsing_when_the_owner_cancels() {
        val srt = (1..1_000).joinToString("\n\n") { "$it\n00:00:01,000 --> 00:00:02,000\nCaption $it" }
        val ass =
            "[Events]\n" +
                (1..1_000).joinToString("\n") {
                    "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Caption $it"
                }
        for ((text, format) in listOf(srt to YSubtitleFormat.Srt, ass to YSubtitleFormat.Ass)) {
            var checks = 0
            val normalizedLineCount = text.lineSequence().count()
            // Pass normalization first; then interrupt actual block/event parsing.
            val cancelAfter = normalizedLineCount + 25
            assertFailsWith<CancellationException> {
                YTextSubtitleParser.parse(text, format) {
                    checks++
                    if (checks >= cancelAfter) throw CancellationException("subtitle changed")
                }
            }
            assertTrue(checks == cancelAfter)
        }
    }
}
