package com.yfuse.core2.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class YTtmlTimingTest {
    private fun parse(
        body: String,
        parameters: String = "",
    ) = YTextSubtitleParser
        .parse(
            """<t:tt xmlns:t="http://www.w3.org/ns/ttml" xmlns:p="http://www.w3.org/ns/ttml#parameter" $parameters><t:body>$body</t:body></t:tt>""",
            YSubtitleFormat.Ttml,
        ).cues

    @Test
    fun namespacesAndParentTimesUseTheAuthoredTimeline() {
        assertEquals(
            YSubtitleFormat.Ttml,
            externalTextSubtitleFormat(
                "file:///sub.xml",
                contentPrefix = """<t:tt xmlns:t="http://www.w3.org/ns/ttml">""",
            ),
        )
        val cue = parse("""<t:div begin="10s"><t:p begin="2s" dur="1s">A &amp; &#x1F600;</t:p></t:div>""").single()
        assertEquals(12_000_000L, cue.startUs)
        assertEquals(13_000_000L, cue.endUs)
        assertEquals("A & 😀", (cue.payload as YSubtitlePayload.Text).plainText)
    }

    @Test
    fun frameMultiplierDefaultTicksAndSubframesAreApplied() {
        val cue =
            parse(
                """<t:p begin="108000f" dur="30f">Frame</t:p>""",
                """p:frameRate="30" p:frameRateMultiplier="1000 1001"""",
            ).single()
        assertEquals(3_603_600_000L, cue.startUs)
        assertEquals(3_604_601_000L, cue.endUs)
        assertEquals(
            1_500_000L,
            parse(
                """<t:p begin="00:00:01:12.1" dur="25t">Subframe</t:p>""",
                """p:frameRate="25" p:subFrameRate="2"""",
            ).single().startUs,
        )
        assertEquals(
            500_000L,
            parse(
                """<t:p begin="25t" dur="25t">Tick</t:p>""",
                """p:frameRate="25" p:subFrameRate="2"""",
            ).single().startUs,
        )
    }

    @Test
    fun sequentialChildrenAndParentClippingKeepCorrectIntervals() {
        val cues =
            parse(
                """<t:div begin="10s" dur="4s" timeContainer="seq"><t:p dur="1s">One</t:p><t:p begin="500ms" dur="5s">Two</t:p></t:div>""",
            )
        assertEquals(
            listOf(10_000_000L to 11_000_000L, 11_500_000L to 14_000_000L),
            cues.map { it.startUs to it.endUs },
        )
    }

    @Test
    fun timedSpansAppearOnlyInTheirOwnIntervals() {
        val cues = parse("""<t:p begin="1s" dur="3s">A<t:span begin="1s" dur="1s"> B</t:span></t:p>""")
        assertEquals(listOf("A", "A B", "A"), cues.map { (it.payload as YSubtitlePayload.Text).plainText })
        assertEquals(listOf(1_000_000L, 2_000_000L, 3_000_000L), cues.map { it.startUs })
    }

    @Test
    fun declarationsForeignNamespacesInvalidTimesAndDeepNestingAreRejectedOrExcluded() {
        assertFails {
            YTextSubtitleParser.parse(
                """<!DOCTYPE tt [<!ENTITY x SYSTEM "file:///private">]><tt/>""",
                YSubtitleFormat.Ttml,
            )
        }
        assertFails { parse("""<t:p begin="1s" dur="1s">&custom;</t:p>""") }
        assertFails { parse("<t:p begin='1s' dur='1s'>x</t:div>") }
        assertFails { parse("<t:p begin='1s' dur='1s'>x</t:p>", "p:frameRateMultiplier='1000 0'") }
        assertFails { parse("<t:div>".repeat(65) + "</t:div>".repeat(65)) }
        assertTrue(parse("""<alien:p xmlns:alien="urn:foreign" begin="1s" dur="1s">Hidden</alien:p>""").isEmpty())
    }

    @Test
    fun inlinePreservedSpacesAndLineBreaksSurviveDefaultWhitespace() {
        val cue =
            parse(
                """<t:p dur="2s">  A  <t:span xml:space="preserve"> B  C </t:span> D<t:br/> E </t:p>""",
            ).single()
        assertEquals("A  B  C D\nE", (cue.payload as YSubtitlePayload.Text).plainText)
        assertFails { parse("<t:p dur='1s'><![CDATA[bad\u0001]]></t:p>") }
    }

    @Test
    fun cancellationIsNotSwallowedByTheXmlReader() {
        class Cancel : RuntimeException()
        assertFailsWith<Cancel> { YTextSubtitleParser.parse("<tt/>", YSubtitleFormat.Ttml) { throw Cancel() } }
    }
}
