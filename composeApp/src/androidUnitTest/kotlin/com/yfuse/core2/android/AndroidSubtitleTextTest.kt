package com.yfuse.core2.android

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Subtitle files reach the player in whatever encoding they were saved in. */
class AndroidSubtitleTextTest {
    @Test
    fun every_recognised_encoding_decodes_to_the_original_text() {
        val samples =
            listOf(
                SIMPLIFIED to "GBK",
                SIMPLIFIED to "GB18030",
                TRADITIONAL to "Big5",
                JAPANESE to "windows-31j",
                JAPANESE to "EUC-JP",
                KOREAN to "x-windows-949",
                RUSSIAN to "windows-1251",
                WESTERN to "windows-1252",
                SIMPLIFIED to "UTF-8",
                SIMPLIFIED to "UTF-16LE",
                SIMPLIFIED to "UTF-16BE",
            )
        for ((text, charset) in samples) {
            assertEquals(text, decodeSubtitleFile(text.toByteArray(Charset.forName(charset))), charset)
        }
    }

    @Test
    fun cantonese_keeps_its_hong_kong_characters() {
        val text = srt("你哋喺度做咩呀？", "佢話聽日會嚟搵我哋", "冇問題，我哋仲有時間", "快啲走！佢哋嚟咗！")

        assertEquals(text, decodeSubtitleFile(text.toByteArray(Charset.forName("Big5-HKSCS"))))
    }

    @Test
    fun a_traditional_name_settles_what_the_bytes_leave_open() {
        // Big5 characters whose trail bytes are all high read as GBK just as well.
        val text = srt("個體中文幕我們說話", "時間界有關到底哪裡", "能夠阻止還有不離", "請快走們來明天找")
        val big5 = text.toByteArray(Charset.forName("Big5"))
        val document = "content://com.android.externalstorage.documents/document/primary%3AMovies%2FFilm.cht.srt"

        assertNotEquals(text, decodeSubtitleFile(big5))
        assertEquals(text, decodeSubtitleFile(big5, null, subtitleAddressName(document)))
        assertEquals(text, decodeSubtitleFile(big5, "Chinese Traditional"))
        assertEquals(text, decodeSubtitleFile(big5, "繁英双语"))
        // Simplified text under a traditional name is still read as GBK.
        assertEquals(SIMPLIFIED, decodeSubtitleFile(SIMPLIFIED.toByteArray(Charset.forName("GBK")), "Film.cht.srt"))
    }

    @Test
    fun address_names_are_the_decoded_last_segment() {
        assertEquals(
            "primary:Movies/Film.cht.srt",
            subtitleAddressName(
                "content://com.android.externalstorage.documents/document/primary%3AMovies%2FFilm.cht.srt",
            ),
        )
        assertEquals("Stream.srt", subtitleAddressName("https://emby.test/Videos/1/Subtitles/3/Stream.srt?api_key=x"))
        assertEquals("电影.繁体.srt", subtitleAddressName("https://nas.test/%E7%94%B5%E5%BD%B1.%E7%B9%81%E4%BD%93.srt"))
        assertEquals("100%.srt", subtitleAddressName("file:///sdcard/100%.srt"))
    }

    private companion object {
        val SIMPLIFIED = srt("我知道你在想什么 但是我不能这么做", "你们到底要去哪里？", "没关系 我们还有时间", "这个世界上没有人能够阻止我们")
        val TRADITIONAL = srt("我知道你在想什麼 但是我不能這麼做", "你們到底要去哪裡？", "沒關係 我們還有時間", "這個世界上沒有人能夠阻止我們")
        val JAPANESE = srt("何を考えているのか分かっている", "でも、そんなことはできない", "どこへ行くつもりなの？", "お願い、行かないで")
        val KOREAN = srt("네가 무슨 생각을 하는지 알아", "하지만 난 그렇게 할 수 없어", "도대체 어디로 가는 거야?", "괜찮아, 아직 시간이 있어")
        val RUSSIAN =
            srt(
                "Я знаю, о чём ты думаешь.",
                "Куда вы вообще собираетесь?",
                "Ничего страшного, у нас ещё есть время.",
                "Быстрее!",
            )
        val WESTERN =
            srt(
                "I know what you’re thinking.",
                "“Where are you going?” she asked.",
                "It’s fine… — maybe.",
                "Café, naïve, déjà vu.",
            )

        fun srt(vararg lines: String): String =
            lines
                .mapIndexed { index, line ->
                    val second = (index + 1) * 3
                    "${index + 1}\r\n00:00:${second.toString().padStart(2, '0')},000 --> " +
                        "00:00:${(second + 2).toString().padStart(2, '0')},500\r\n$line\r\n"
                }.joinToString("\r\n")
    }
}
