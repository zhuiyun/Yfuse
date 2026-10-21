package com.yfuse.feature.player

import com.yfuse.core2.subtitle.YSubtitleCue
import com.yfuse.core2.subtitle.YSubtitlePayload

private val positionedAssTag = Regex("""\\(?:pos|move|org|clip|iclip|t)\s*\(""")

/** Keep signs, karaoke transforms and explicitly positioned ASS on their authored canvas. */
internal fun core2HasAuthoredSubtitlePosition(cues: List<YSubtitleCue>): Boolean {
    val sources = mutableSetOf<com.yfuse.core2.subtitle.YAssSubtitleSource>()
    return cues.any { cue ->
        val ass = cue.payload as? YSubtitlePayload.AssEvent ?: return@any false
        val data = ass.packet ?: ass.source.data.takeIf { sources.add(ass.source) } ?: return@any false
        positionedAssTag.containsMatchIn(data.decodeToString())
    }
}
