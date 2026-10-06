package com.yfuse.core2.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Subtitle files state no encoding, so YCore reads it off their bytes. */
class YSubtitleCharsetTest {
    @Test
    fun legacy_encodings_are_told_apart_by_their_bytes() {
        val expected =
            listOf(
                SIMPLIFIED_GBK to YSubtitleCharset.Gb18030,
                TRADITIONAL_BIG5 to YSubtitleCharset.Big5,
                // Traditional characters saved as GBK use trail bytes Big5 never has.
                TRADITIONAL_GBK to YSubtitleCharset.Gb18030,
                JAPANESE_SHIFT_JIS to YSubtitleCharset.ShiftJis,
                JAPANESE_EUC_JP to YSubtitleCharset.EucJp,
                KOREAN_CP949 to YSubtitleCharset.EucKr,
                RUSSIAN_1251 to YSubtitleCharset.Windows1251,
                ENGLISH_1252 to YSubtitleCharset.Windows1252,
            )
        for ((bytes, charset) in expected) assertEquals(charset, detectSubtitleCharset(bytes))
    }

    @Test
    fun a_traditional_label_settles_big5_only_where_the_bytes_allow_it() {
        // Big5 whose characters all have high trail bytes reads as well as GBK.
        assertEquals(YSubtitleCharset.Gb18030, detectSubtitleCharset(AMBIGUOUS_BIG5))
        assertEquals(YSubtitleCharset.Big5, detectSubtitleCharset(AMBIGUOUS_BIG5, traditionalHint = true))
        assertEquals(YSubtitleCharset.Gb18030, detectSubtitleCharset(TRADITIONAL_GBK, traditionalHint = true))
    }

    @Test
    fun unicode_is_recognised_with_or_without_a_mark() {
        val text = "1\r\n00:00:01,000 --> 00:00:02,000\r\n你好，世界 ♪\r\n"
        val utf8 = text.encodeToByteArray()
        assertEquals(YSubtitleCharset.Utf8, detectSubtitleCharset(utf8))
        assertEquals(text, decodeExternalSubtitleText(utf8))
        assertEquals(text, decodeExternalSubtitleText(bytes(0xEF, 0xBB, 0xBF) + utf8))
        for (littleEndian in listOf(true, false)) {
            val utf16 = text.encodeUtf16(littleEndian)
            val mark = if (littleEndian) bytes(0xFF, 0xFE) else bytes(0xFE, 0xFF)
            val charset = if (littleEndian) YSubtitleCharset.Utf16Le else YSubtitleCharset.Utf16Be
            assertEquals(charset, detectSubtitleCharset(utf16))
            assertEquals(charset, detectSubtitleCharset(mark + utf16))
            assertEquals(text, decodeExternalSubtitleText(utf16))
            assertEquals(text, decodeExternalSubtitleText(mark + utf16))
        }
    }

    @Test
    fun utf8_survives_a_stray_byte_and_a_cut_off_end() {
        val text = "1\r\n00:00:01,000 --> 00:00:02,000\r\n我知道你在想什么，但是我不能这么做。\r\n"
        val stray = text.encodeToByteArray() + bytes(0x93) + "quoted".encodeToByteArray()
        assertEquals(YSubtitleCharset.Utf8, detectSubtitleCharset(stray))
        assertEquals(text + "\uFFFDquoted", decodeExternalSubtitleText(stray))
        // Cut inside the final 。
        val cut = text.encodeToByteArray().let { it.copyOf(it.size - 3) }
        assertEquals(YSubtitleCharset.Utf8, detectSubtitleCharset(cut))
    }

    @Test
    fun legacy_text_decodes_strictly_first_then_leniently_in_the_same_encoding() {
        val calls = mutableListOf<String>()
        val hongKong =
            YLegacyTextDecoder { _, name, strict ->
                calls += "$name:$strict"
                "decoded as $name".takeIf { strict && name == "Big5-HKSCS" }
            }
        assertEquals("decoded as Big5-HKSCS", decodeExternalSubtitleText(TRADITIONAL_BIG5, legacyDecoder = hongKong))
        assertEquals(listOf("Big5:true", "Big5-HKSCS:true"), calls)

        // A damaged GBK file loses a character; it is never read as Big5.
        calls.clear()
        val damaged =
            YLegacyTextDecoder { _, name, strict ->
                calls += "$name:$strict"
                "decoded as $name".takeUnless { strict }
            }
        assertEquals("decoded as GB18030", decodeExternalSubtitleText(SIMPLIFIED_GBK, legacyDecoder = damaged))
        assertEquals(listOf("GB18030:true", "GBK:true", "GB18030:false"), calls)
    }

    @Test
    fun a_simplified_file_under_a_traditional_label_falls_back_to_gbk() {
        val calls = mutableListOf<String>()
        val decoder =
            YLegacyTextDecoder { _, name, strict ->
                calls += "$name:$strict"
                "decoded as $name".takeIf { strict && name == "GB18030" }
            }
        assertEquals(
            "decoded as GB18030",
            decodeExternalSubtitleText(SIMPLIFIED_GBK, traditionalHint = true, legacyDecoder = decoder),
        )
        assertEquals(listOf("Big5:true", "Big5-HKSCS:true", "GB18030:true"), calls)
    }

    @Test
    fun without_a_platform_decoder_legacy_bytes_read_as_utf8() {
        assertEquals(SIMPLIFIED_GBK.decodeToString(), decodeExternalSubtitleText(SIMPLIFIED_GBK))
        assertNotEquals(
            SIMPLIFIED_GBK.decodeToString(),
            decodeExternalSubtitleText(SIMPLIFIED_GBK, legacyDecoder = { _, _, _ -> "legacy" }),
        )
    }
}

private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

private fun hex(value: String): ByteArray =
    ByteArray(value.length / 2) {
        value.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

private fun String.encodeUtf16(littleEndian: Boolean): ByteArray {
    val output = ByteArray(length * 2)
    forEachIndexed { index, char ->
        val high = (char.code shr 8).toByte()
        val low = char.code.toByte()
        output[index * 2] = if (littleEndian) low else high
        output[index * 2 + 1] = if (littleEndian) high else low
    }
    return output
}

// Four-cue SRT files, written by the encoders of the named encodings.
private val SIMPLIFIED_GBK =
    hex(
        "310d0a30303a30303a30332c303030202d2d3e2030303a30303a30352c3530300d0aced2d6aab5c0c4e3d4dacfebcab2c3b4" +
            "20b5abcac7ced2b2bbc4dcd5e2c3b4d7f60d0a0d0a320d0a30303a30303a30362c303030202d2d3e2030303a30303a30382c" +
            "3530300d0ac4e3c3c7b5bdb5d7d2aac8a5c4c4c0efa3bf0d0a0d0a330d0a30303a30303a30392c303030202d2d3e2030303a" +
            "30303a31312c3530300d0ac3bbb9d8cfb520ced2c3c7bbb9d3d0cab1bce40d0a0d0a340d0a30303a30303a31322c30303020" +
            "2d2d3e2030303a30303a31342c3530300d0ad5e2b8f6cac0bde7c9cfc3bbd3d0c8cbc4dcb9bbd7e8d6b9ced2c3c70d0a",
    )

private val TRADITIONAL_BIG5 =
    hex(
        "310d0a30303a30303a30332c303030202d2d3e2030303a30303a30352c3530300d0aa7daaabeb944a741a662b751a4b0bbf2" +
            "20a6fdac4fa7daa4a3afe0b36fbbf2b0b50d0a0d0a320d0a30303a30303a30362c303030202d2d3e2030303a30303a30382c" +
            "3530300d0aa741adcca8eca9b3ad6ea568adfeb8cca1480d0a0d0a330d0a30303a30303a30392c303030202d2d3e2030303a" +
            "30303a31312c3530300d0aa853c3f6ab5920a7daadccc1d9a6b3aec9b6a10d0a0d0a340d0a30303a30303a31322c30303020" +
            "2d2d3e2030303a30303a31342c3530300d0ab36fadd3a540acc9a457a853a6b3a448afe0b0f7aafda4eea7daadcc0d0a",
    )

private val TRADITIONAL_GBK =
    hex(
        "310d0a30303a30303a30332c303030202d2d3e2030303a30303a30352c3530300d0aced2d6aab5c0c4e3d4dacfebcab2fc4e" +
            "20b5abcac7ced2b2bbc4dcdf40fc4ed7f60d0a0d0a320d0a30303a30303a30362c303030202d2d3e2030303a30303a30382c" +
            "3530300d0ac4e38283b5bdb5d7d2aac8a5c4c4d165a3bf0d0a0d0a330d0a30303a30303a30392c303030202d2d3e2030303a" +
            "30303a31312c3530300d0a9b5dea50825320ced28283df80d3d09572e9670d0a0d0a340d0a30303a30303a31322c30303020" +
            "2d2d3e2030303a30303a31342c3530300d0adf408280cac0bde7c9cf9b5dd3d0c8cbc4dc89f2d7e8d6b9ced282830d0a",
    )

private val JAPANESE_SHIFT_JIS =
    hex(
        "310d0a30303a30303a30332c303030202d2d3e2030303a30303a30352c3530300d0a89bd82f08d6c82a682c482a282e982cc" +
            "82a995aa82a982c182c482a282e90d0a0d0a320d0a30303a30303a30362c303030202d2d3e2030303a30303a30382c353030" +
            "0d0a82c582e0814182bb82f182c882b182c682cd82c582ab82c882a20d0a0d0a330d0a30303a30303a30392c303030202d2d" +
            "3e2030303a30303a31312c3530300d0a82c782b182d68d7382ad82c282e082e882c882cc81480d0a0d0a340d0a30303a3030" +
            "3a31322c303030202d2d3e2030303a30303a31342c3530300d0a82a88ae882a281418d7382a982c882a282c50d0a",
    )

private val JAPANESE_EUC_JP =
    hex(
        "310d0a30303a30303a30332c303030202d2d3e2030303a30303a30352c3530300d0ab2bfa4f2b9cda4a8a4c6a4a4a4eba4ce" +
            "a4abcaaca4aba4c3a4c6a4a4a4eb0d0a0d0a320d0a30303a30303a30362c303030202d2d3e2030303a30303a30382c353030" +
            "0d0aa4c7a4e2a1a2a4bda4f3a4caa4b3a4c8a4cfa4c7a4ada4caa4a40d0a0d0a330d0a30303a30303a30392c303030202d2d" +
            "3e2030303a30303a31312c3530300d0aa4c9a4b3a4d8b9d4a4afa4c4a4e2a4eaa4caa4cea1a90d0a0d0a340d0a30303a3030" +
            "3a31322c303030202d2d3e2030303a30303a31342c3530300d0aa4aab4eaa4a4a1a2b9d4a4aba4caa4a4a4c70d0a",
    )

private val KOREAN_CP949 =
    hex(
        "310d0a30303a30303a30332c303030202d2d3e2030303a30303a30352c3530300d0ab3d7b0a120b9abbdbc20bbfdb0a2c0bb" +
            "20c7cfb4c2c1f620becbbec60d0a0d0a320d0a30303a30303a30362c303030202d2d3e2030303a30303a30382c3530300d0a" +
            "c7cfc1f6b8b820b3ad20b1d7b7b8b0d420c7d220bcf620bef8beee0d0a0d0a330d0a30303a30303a30392c303030202d2d3e" +
            "2030303a30303a31312c3530300d0ab5b5b4ebc3bc20beeeb5f0b7ce20b0a1b4c220b0c5bedf3f0d0a0d0a340d0a30303a30" +
            "303a31322c303030202d2d3e2030303a30303a31342c3530300d0ab1a6c2fabec62c20bec6c1f720bdc3b0a3c0cc20c0d6be" +
            "ee0d0a",
    )

private val RUSSIAN_1251 =
    hex(
        "310d0a30303a30303a30332c303030202d2d3e2030303a30303a30352c3530300d0adf20e7ede0fe2c20ee20f7b8ec20f2fb" +
            "20e4f3ece0e5f8fc2e0d0a0d0a320d0a30303a30303a30362c303030202d2d3e2030303a30303a30382c3530300d0acaf3e4" +
            "e020e2fb20e2eeeee1f9e520f1eee1e8f0e0e5f2e5f1fc3f0d0a0d0a330d0a30303a30303a30392c303030202d2d3e203030" +
            "3a30303a31312c3530300d0acde8f7e5e3ee20f1f2f0e0f8edeee3ee2c20f320ede0f120e5f9b820e5f1f2fc20e2f0e5ecff" +
            "2e0d0a0d0a340d0a30303a30303a31322c303030202d2d3e2030303a30303a31342c3530300d0ac1fbf1f2f0e5e52120ceed" +
            "e820e8e4f3f2210d0a",
    )

private val ENGLISH_1252 =
    hex(
        "310d0a30303a30303a30332c303030202d2d3e2030303a30303a30352c3530300d0a49206b6e6f77207768617420796f7592" +
            "7265207468696e6b696e672c2062757420492063616e92742e0d0a0d0a320d0a30303a30303a30362c303030202d2d3e2030" +
            "303a30303a30382c3530300d0a9357686572652061726520796f7520676f696e673f94207368652061736b65642e0d0a0d0a" +
            "330d0a30303a30303a30392c303030202d2d3e2030303a30303a31312c3530300d0a497492732066696e6585207765207374" +
            "696c6c20686176652074696d652097206d617962652e0d0a0d0a340d0a30303a30303a31322c303030202d2d3e2030303a30" +
            "303a31342c3530300d0a436166e92c206e61ef76652c2064e96ae02076752e0d0a",
    )

private val AMBIGUOUS_BIG5 =
    hex(
        "310d0a30303a30303a30332c303030202d2d3e2030303a30303a30352c3530300d0aadd3c5e9a4a4a4e5b9f5a7daadccbba1" +
            "b8dc0d0a0d0a320d0a30303a30303a30362c303030202d2d3e2030303a30303a30382c3530300d0aaec9b6a1acc9a6b3c3f6" +
            "a8eca9b3adfeb8cc0d0a0d0a330d0a30303a30303a30392c303030202d2d3e2030303a30303a31312c3530300d0aafe0b0f7" +
            "aafda4eec1d9a6b3a4a3c2f70d0a0d0a340d0a30303a30303a31322c303030202d2d3e2030303a30303a31342c3530300d0a" +
            "bdd0a7d6a8abadcca8d3a9faa4d1a7e40d0a",
    )
