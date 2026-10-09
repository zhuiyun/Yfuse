package com.yfuse.core2.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** SAMI, MicroDVD and TTML sidecars, read as text like SRT and WebVTT. */
class YExtraTextSubtitleFormatsTest {
    @Test
    fun sami_captions_last_until_the_next_sync_and_the_main_class_is_shown() {
        val sami =
            """
            <SAMI><HEAD><STYLE TYPE="text/css"><!--
            .KRCC {Name:Korean; lang:ko-KR;}
            .ENCC {Name:English; lang:en-US;}
            --></STYLE></HEAD><BODY>
            <SYNC Start=1000><P Class=KRCC>안녕하세요<br>반갑습니다
            <SYNC Start=1000><P Class=ENCC>Hello
            <SYNC Start=3500><P Class=KRCC>&nbsp;
            <SYNC Start=5000><P Class=KRCC><font color="#ffff00">두 번째</font>
            <SYNC Start=7000><P Class=KRCC>세 번째
            </BODY></SAMI>
            """.trimIndent()

        val cues = YTextSubtitleParser.parse(sami, YSubtitleFormat.Smi).cues

        assertEquals(listOf("안녕하세요\n반갑습니다", "두 번째", "세 번째"), cues.map { it.text() })
        assertEquals(1_000_000L to 3_500_000L, cues[0].startUs to cues[0].endUs)
        assertEquals(5_000_000L to 7_000_000L, cues[1].startUs to cues[1].endUs)
        // The last caption has nothing after it to end it.
        assertEquals(11_000_000L, cues[2].endUs)
    }

    @Test
    fun microdvd_frames_follow_the_stated_rate_and_codes_are_dropped() {
        val microDvd =
            """
            {1}{1}25
            {25}{75}{y:i}First line|second line
            {100}{150}Plain
            """.trimIndent()

        val cues = YTextSubtitleParser.parse(microDvd, YSubtitleFormat.MicroDvd).cues

        assertEquals(listOf("First line\nsecond line", "Plain"), cues.map { it.text() })
        assertEquals(1_000_000L to 3_000_000L, cues[0].startUs to cues[0].endUs)
        assertEquals(4_000_000L to 6_000_000L, cues[1].startUs to cues[1].endUs)
        // Without a rate line, 23.976 frames per second.
        assertEquals(
            1_001_001L,
            YTextSubtitleParser
                .parse("{24}{48}x", YSubtitleFormat.MicroDvd)
                .cues
                .single()
                .startUs,
        )
    }

    @Test
    fun ttml_reads_clock_offset_and_frame_times_with_end_or_dur() {
        val ttml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttp="http://www.w3.org/ns/ttml#parameter" ttp:frameRate="25" xmlns:tts="http://www.w3.org/ns/ttml#styling">
              <body><div>
                <p begin="00:00:01.500" end="00:00:03.000">First <span tts:color="yellow">line</span><br/>second</p>
                <p begin="4s" dur="1500ms">Offset &amp; duration</p>
                <p begin="00:00:06:12" end="00:00:07:00">Frames</p>
                <p begin="9s">No end is skipped</p>
              </div></body>
            </tt>
            """.trimIndent()

        val cues = YTextSubtitleParser.parse(ttml, YSubtitleFormat.Ttml).cues

        assertEquals(listOf("First line\nsecond", "Offset & duration", "Frames"), cues.map { it.text() })
        assertEquals(1_500_000L to 3_000_000L, cues[0].startUs to cues[0].endUs)
        assertEquals(4_000_000L to 5_500_000L, cues[1].startUs to cues[1].endUs)
        assertEquals(6_480_000L to 7_000_000L, cues[2].startUs to cues[2].endUs)
    }

    @Test
    fun the_new_formats_are_recognised_by_name_type_and_content() {
        assertEquals(YSubtitleFormat.Smi, externalTextSubtitleFormat("https://nas.test/Film.KOR.smi"))
        assertEquals(YSubtitleFormat.Ttml, externalTextSubtitleFormat("content://subs/1", "application/ttml+xml"))
        assertEquals(YSubtitleFormat.Ttml, externalTextSubtitleFormat("https://cdn.test/subs.dfxp"))
        assertEquals(
            YSubtitleFormat.Ttml,
            externalTextSubtitleFormat(
                "https://cdn.test/subs.xml",
                contentPrefix = "<?xml version=\"1.0\"?>\n<tt xmlns=\"http://www.w3.org/ns/ttml\">",
            ),
        )
        assertEquals(
            YSubtitleFormat.Smi,
            externalTextSubtitleFormat("content://subs/2", contentPrefix = "<SAMI>\n<HEAD>"),
        )
        assertEquals(
            YSubtitleFormat.MicroDvd,
            externalTextSubtitleFormat("file:///sdcard/film.sub", contentPrefix = "{1}{1}23.976\n"),
        )
        // VobSub's .sub is binary MPEG-PS and is not taken for MicroDVD.
        assertNull(externalTextSubtitleFormat("file:///sdcard/film.sub", contentPrefix = "\u0000\u0000\u0001º"))
    }

    private fun YSubtitleCue.text(): String = (payload as YSubtitlePayload.Text).plainText
}
