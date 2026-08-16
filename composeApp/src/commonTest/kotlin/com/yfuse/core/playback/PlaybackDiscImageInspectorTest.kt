package com.yfuse.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackDiscImageInspectorTest {
    @Test
    fun ascii_directory_names_identify_dvd_and_bluray() {
        assertEquals(PlaybackDiscKind.Dvd, detectPlaybackDiscImageKind(ascii("pad VIDEO_TS.IFO pad")))
        assertEquals(
            PlaybackDiscKind.BluRay,
            detectPlaybackDiscImageKind(ascii("pad BDMV/PLAYLIST/00001.MPLS")),
        )
    }

    /**
     * The case this was missing. A Blu-ray image is UDF, and UDF file identifiers are OSTA
     * Compressed Unicode — a leading compression id of 16 means two bytes per character. An
     * image mastered that way carries `INDEX.BDMV` as UTF-16, and an ASCII-only search reads
     * it as an ordinary data ISO, which is then handed to the player as a plain file.
     */
    @Test
    fun utf16_directory_names_identify_a_bluray_image() {
        assertEquals(
            PlaybackDiscKind.BluRay,
            detectPlaybackDiscImageKind(utf16LittleEndian("INDEX.BDMV")),
            "UDF with a 16-bit compression id stores the name little-endian per character",
        )
        assertEquals(
            PlaybackDiscKind.BluRay,
            detectPlaybackDiscImageKind(utf16BigEndian("MOVIEOBJECT.BDMV")),
        )
        assertEquals(
            PlaybackDiscKind.Dvd,
            detectPlaybackDiscImageKind(utf16BigEndian("VIDEO_TS.IFO")),
        )
    }

    @Test
    fun matching_ignores_case() {
        assertEquals(PlaybackDiscKind.Dvd, detectPlaybackDiscImageKind(ascii("video_ts.ifo")))
        assertEquals(PlaybackDiscKind.BluRay, detectPlaybackDiscImageKind(utf16LittleEndian("index.bdmv")))
    }

    @Test
    fun an_ordinary_image_stays_an_iso() {
        assertEquals(
            PlaybackDiscKind.Iso,
            detectPlaybackDiscImageKind(ascii("ordinary iso without a movie layout")),
        )
        assertEquals(PlaybackDiscKind.Iso, detectPlaybackDiscImageKind(ByteArray(0)))
    }

    /** Blu-ray wins: a hybrid image carrying both layouts should play as the Blu-ray. */
    @Test
    fun a_hybrid_image_is_read_as_bluray() {
        assertEquals(
            PlaybackDiscKind.BluRay,
            detectPlaybackDiscImageKind(ascii("VIDEO_TS.IFO and INDEX.BDMV together")),
        )
    }

    @Test
    fun a_name_split_across_two_reads_is_still_found() {
        val marker = ascii("INDEX.BDMV")
        val scanner = PlaybackDiscImageScanner()

        scanner.accept(ascii("padding") + marker.copyOfRange(0, 4))
        assertFalse(scanner.settled, "half a name is not a match")
        scanner.accept(marker.copyOfRange(4, marker.size) + ascii("padding"))

        assertTrue(scanner.settled)
        assertEquals(PlaybackDiscKind.BluRay, scanner.kind)
    }

    @Test
    fun scanning_stops_as_soon_as_the_layout_is_known() {
        val scanner = PlaybackDiscImageScanner()

        assertFalse(scanner.accept(ascii("nothing here")))
        assertTrue(scanner.accept(ascii("VIDEO_TS.IFO")), "accept reports that reading can stop")
        assertTrue(scanner.accept(ascii("INDEX.BDMV")), "a settled scanner ignores later chunks")
        assertEquals(PlaybackDiscKind.Dvd, scanner.kind, "the first layout found decides")
    }

    @Test
    fun only_the_declared_length_of_a_chunk_is_matched() {
        val buffer = ascii("VIDEO_TS.IFO").copyOf(64)
        val scanner = PlaybackDiscImageScanner()

        // A read loop reuses one buffer, so bytes past `count` are the previous read's.
        assertFalse(scanner.accept(buffer, length = 5))
        assertEquals(PlaybackDiscKind.Iso, scanner.kind)
        assertTrue(scanner.accept(buffer, length = buffer.size))
    }

    private fun ascii(value: String) = ByteArray(value.length) { value[it].code.toByte() }

    private fun utf16LittleEndian(value: String) =
        ByteArray(value.length * 2) { if (it % 2 == 0) value[it / 2].code.toByte() else 0 }

    private fun utf16BigEndian(value: String) =
        ByteArray(value.length * 2) { if (it % 2 == 0) 0 else value[it / 2].code.toByte() }
}
