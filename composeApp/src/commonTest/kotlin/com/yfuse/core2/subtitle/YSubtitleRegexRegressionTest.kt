package com.yfuse.core2.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class YSubtitleRegexRegressionTest {
    @Test
    fun embeddedPlainTextInitializesSubtitleHelpers() {
        assertEquals("字幕正常", embedded("字幕正常").plainText)
    }

    @Test
    fun embeddedOverridesPreserveTextAndLineBreaks() {
        assertEquals("第一行\n第二行", embedded("{\\b1}第一行{\\b0}\\N第二行").plainText)
    }

    @Test
    fun embeddedAssKeepsWholeCueStyle() {
        val text = embedded("{\\b1\\i1\\an8}字幕", YSubtitleFormat.Ass)
        assertEquals("字幕", text.plainText)
        assertTrue(text.style.bold)
        assertTrue(text.style.italic)
        assertEquals(8, text.style.alignment)
    }

    @Test
    fun unclosedOverrideRemainsLiteral() {
        assertEquals("{\\b1未闭合", embedded("{\\b1未闭合").plainText)
    }

    @Test
    fun trailingLiteralClosingBraceIsPreserved() {
        assertEquals("字幕}", embedded("{\\b1}字幕}").plainText)
    }

    @Test
    fun standaloneSrtInitializesParserHelpers() {
        val source = "1\n00:00:01,000 --> 00:00:02,000\n<b>字幕</b> &amp; text\n"
        assertEquals("字幕 & text", standalone(source, YSubtitleFormat.Srt).plainText)
    }

    @Test
    fun standaloneWebVttInitializesParserHelpers() {
        val source = "WEBVTT\n\n00:01.000 --> 00:02.000\n<i>字幕</i>\n"
        assertEquals("字幕", standalone(source, YSubtitleFormat.WebVtt).plainText)
    }

    @Test
    fun standaloneAssAndSsaStripOverridesAndRetainStyle() {
        val source =
            "[Events]\n" +
                "Format: Start, End, Text\n" +
                "Dialogue: 0:00:01.00,0:00:02.00,{\\b1}第一行{\\i1}\\N第二行\n"
        for (format in listOf(YSubtitleFormat.Ass, YSubtitleFormat.Ssa)) {
            val text = standalone(source, format)
            assertEquals("第一行\n第二行", text.plainText)
            assertTrue(text.style.bold)
            assertTrue(text.style.italic)
        }
    }

    private fun embedded(
        text: String,
        format: YSubtitleFormat = YSubtitleFormat.Srt,
    ): YSubtitlePayload.Text {
        val cue = YEmbeddedSubtitleDecoder.decode(text.encodeToByteArray(), format, 0L, 1_000_000L, "regex-test")
        return assertIs<YSubtitlePayload.Text>(assertNotNull(cue).payload)
    }

    private fun standalone(
        text: String,
        format: YSubtitleFormat,
    ): YSubtitlePayload.Text = assertIs<YSubtitlePayload.Text>(YTextSubtitleParser.parse(text, format).cues.single().payload)
}
