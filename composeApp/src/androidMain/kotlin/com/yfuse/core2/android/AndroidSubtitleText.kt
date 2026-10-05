package com.yfuse.core2.android

import com.yfuse.core.filesource.subtitleLabelSuggestsTraditional
import com.yfuse.core2.subtitle.YLegacyTextDecoder
import com.yfuse.core2.subtitle.decodeExternalSubtitleText
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** The platform's charsets, for the legacy encodings YCore recognises in subtitle files. */
internal object AndroidLegacyTextDecoder : YLegacyTextDecoder {
    override fun decode(
        data: ByteArray,
        charsetName: String,
        strict: Boolean,
    ): String? {
        val charset = runCatching { Charset.forName(charsetName) }.getOrNull() ?: return null
        if (!strict) return String(data, charset)
        return try {
            charset
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}

/**
 * Decodes a subtitle file in whatever encoding it was saved in. [labels] are what names it — a
 * file name, an address, a track language — and only say whether it is traditional Chinese.
 */
internal fun decodeSubtitleFile(
    data: ByteArray,
    vararg labels: String?,
): String =
    decodeExternalSubtitleText(
        data = data,
        traditionalHint = labels.any(::subtitleLabelSuggestsTraditional),
        legacyDecoder = AndroidLegacyTextDecoder,
    )

/** `…/document/primary%3AMovies%2FFilm.cht.srt?x=1` → `primary:Movies/Film.cht.srt`. */
internal fun subtitleAddressName(uri: String): String {
    val segment =
        uri
            .substringBefore('#')
            .substringBefore('?')
            .trimEnd('/')
            .substringAfterLast('/')
    return runCatching { URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)
}
