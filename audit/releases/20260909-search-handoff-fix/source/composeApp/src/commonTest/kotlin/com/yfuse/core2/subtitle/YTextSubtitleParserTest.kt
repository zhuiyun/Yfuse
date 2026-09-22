package com.yfuse.core2.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals

class YTextSubtitleParserTest {
    @Test
    fun `parses SRT markup and millisecond timing`() {
        val timeline =
            YTextSubtitleParser.parse(
                """
                1
                00:00:01,250 --> 00:00:03,000
                <i>Hello</i>
                """.trimIndent(),
                YSubtitleFormat.Srt,
            )

        val cue = timeline.cues.single()
        assertEquals(1_250_000L, cue.startUs)
        assertEquals(3_000_000L, cue.endUs)
        assertEquals("Hello", (cue.payload as YSubtitlePayload.Text).plainText)
    }

    @Test
    fun `parses WebVTT cue ids and settings`() {
        val timeline =
            YTextSubtitleParser.parse(
                """
                WEBVTT

                opening
                00:01.000 --> 00:02.500 align:start
                Welcome
                """.trimIndent(),
                YSubtitleFormat.WebVtt,
            )

        assertEquals("opening", timeline.cues.single().id)
        assertEquals(2_500_000L, timeline.cues.single().endUs)
    }

    @Test
    fun `parses ASS dialogue text and rendering overrides`() {
        val timeline =
            YTextSubtitleParser.parse(
                """
                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                Dialogue: 0,0:00:02.00,0:00:04.50,Default,,0,0,0,,{\b1\i1\u1\fs36\an8\c&H112233&}Hello\Nworld
                """.trimIndent(),
                YSubtitleFormat.Ass,
            )

        val payload = timeline.cues.single().payload as YSubtitlePayload.Text
        assertEquals("Hello\nworld", payload.plainText)
        assertEquals(true, payload.style.bold)
        assertEquals(true, payload.style.italic)
        assertEquals(true, payload.style.underline)
        assertEquals(36f, payload.style.fontSizePoints)
        assertEquals(8, payload.style.alignment)
        assertEquals(0xff332211.toInt(), payload.style.primaryColorArgb)
    }

    @Test
    fun `inherits ASS named style before applying inline overrides`() {
        val timeline =
            YTextSubtitleParser.parse(
                """
                [V4+ Styles]
                Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
                Style: Sign,Arial,42,&H00FF8000,&H0,&H0,&H0,-1,0,0,0,100,100,0,0,1,3,1,7,10,10,10,1
                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                Dialogue: 0,0:00:01.00,0:00:02.00,Sign,,0,0,0,,{\i1}Styled
                """.trimIndent(),
                YSubtitleFormat.Ass,
            )

        val style = (timeline.cues.single().payload as YSubtitlePayload.Text).style
        assertEquals(true, style.bold)
        assertEquals(true, style.italic)
        assertEquals(42f, style.fontSizePoints)
        assertEquals(7, style.alignment)
        assertEquals(3f, style.outline)
    }

    @Test
    fun `applies positive subtitle delay without changing cue data`() {
        val timeline =
            YSubtitleTimeline(
                listOf(
                    YSubtitleCue("a", 1_000_000L, 2_000_000L, YSubtitlePayload.Text("A")),
                    YSubtitleCue("b", 1_500_000L, 3_000_000L, YSubtitlePayload.Text("B")),
                ),
            )

        assertEquals(emptyList(), timeline.activeAt(1_400_000L, delayUs = 500_000L))
        assertEquals(listOf("a", "b"), timeline.activeAt(2_100_000L, delayUs = 500_000L).map { it.id })
    }

    @Test
    fun `decodes Matroska ASS packets using container timing`() {
        val cue =
            YEmbeddedSubtitleDecoder.decode(
                data = "7,0,Default,Speaker,0,0,0,,{\\i1}Hello, world\\Nnext".encodeToByteArray(),
                format = YSubtitleFormat.Ass,
                startUs = 2_000_000L,
                durationUs = 1_500_000L,
                id = "ass-packet",
            )

        requireNotNull(cue)
        assertEquals(2_000_000L, cue.startUs)
        assertEquals(3_500_000L, cue.endUs)
        assertEquals("Hello, world\nnext", (cue.payload as YSubtitlePayload.Text).plainText)
        assertEquals(true, cue.payload.style.italic)
    }

    @Test
    fun `decodes tx3g length-prefixed UTF-8 text`() {
        val cue =
            YEmbeddedSubtitleDecoder.decode(
                data = byteArrayOf(0, 5) + "hello".encodeToByteArray(),
                format = YSubtitleFormat.Tx3g,
                startUs = 0L,
                durationUs = 2_000_000L,
                id = "tx3g-packet",
            )

        requireNotNull(cue)
        assertEquals("hello", (cue.payload as YSubtitlePayload.Text).plainText)
    }

    @Test
    fun `resolves external subtitle format from content type extension and content`() {
        assertEquals(YSubtitleFormat.WebVtt, externalTextSubtitleFormat("content://subtitle/7", "text/vtt"))
        assertEquals(YSubtitleFormat.Ssa, externalTextSubtitleFormat("https://media.test/subtitle.SSA?token=redacted"))
        assertEquals(
            YSubtitleFormat.Ass,
            externalTextSubtitleFormat("content://subtitle/8", contentPrefix = "[Script Info]\n"),
        )
        assertEquals(
            YSubtitleFormat.Srt,
            externalTextSubtitleFormat("content://subtitle/9", contentPrefix = "00:01 --> 00:02"),
        )
    }

    @Test
    fun `decodes UTF-16 little endian external subtitle`() {
        val text = "1\r\n00:00:01,000 --> 00:00:02,000\r\n你好"
        val payload =
            text
                .flatMap { character ->
                    listOf(
                        (character.code and 0xff).toByte(),
                        (character.code ushr 8).toByte(),
                    )
                }.toByteArray()
        val decoded = decodeExternalSubtitleText(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + payload)

        assertEquals(text, decoded)
    }
}
