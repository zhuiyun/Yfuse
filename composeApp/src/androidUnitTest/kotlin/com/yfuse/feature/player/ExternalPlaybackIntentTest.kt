package com.yfuse.feature.player

import android.content.Intent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalPlaybackIntentTest {
    @Test
    fun viewed_document_plays_through_its_content_uri() {
        val document = "content://com.android.providers.media.documents/document/video%3A1234"

        assertEquals(
            ExternalPlaybackTarget(document, ExternalPlaybackSource.Content),
            externalPlaybackTarget(Intent.ACTION_VIEW, document, "video/mp4", null),
        )
        // An explicit intent may leave the type out; the address alone then decides.
        assertEquals(
            ExternalPlaybackTarget(document, ExternalPlaybackSource.Content),
            externalPlaybackTarget(Intent.ACTION_VIEW, document, null, null),
        )
        assertEquals(
            ExternalPlaybackTarget(document, ExternalPlaybackSource.Content),
            externalPlaybackTarget(Intent.ACTION_VIEW, document, "VIDEO/X-MATROSKA", null),
        )
    }

    @Test
    fun a_declared_type_other_than_video_is_refused() {
        listOf("image/png", "text/html", "application/vnd.android.package-archive", "audio/mpeg").forEach { type ->
            assertNull(externalPlaybackTarget(Intent.ACTION_VIEW, "content://files/document/1", type, null), type)
        }
    }

    @Test
    fun only_an_absolute_local_file_path_is_taken_from_a_file_uri() {
        assertEquals(
            ExternalPlaybackTarget("file:///storage/emulated/0/Movies/a.mp4", ExternalPlaybackSource.File),
            externalPlaybackTarget(Intent.ACTION_VIEW, "file:///storage/emulated/0/Movies/a.mp4", "video/mp4", null),
        )
        listOf("file:///", "file://host/share/a.mp4", "file:relative.mp4", "FILE:///sdcard/a.mp4").forEach { data ->
            assertNull(externalPlaybackTarget(Intent.ACTION_VIEW, data, "video/mp4", null), data)
        }
    }

    @Test
    fun viewed_web_address_is_validated_like_a_pasted_link() {
        assertEquals(
            ExternalPlaybackTarget("https://cdn.example.com/v.m3u8", ExternalPlaybackSource.Web),
            externalPlaybackTarget(Intent.ACTION_VIEW, "HTTPS://cdn.example.com/v.m3u8", "video/*", null),
        )
        assertNull(externalPlaybackTarget(Intent.ACTION_VIEW, "https://:443/v.mp4", "video/mp4", null))
    }

    @Test
    fun other_schemes_and_malformed_documents_are_refused() {
        listOf(
            "intent://play#Intent;scheme=https;end",
            "javascript:alert(1)",
            "data:video/mp4;base64,AAAA",
            "smb://nas/share/a.mkv",
            "rtsp://camera.local/live",
            "yfuse://watch/ABCD",
            "Content://files/document/1",
            "content://",
            "content:///no-authority",
            "content://files/document/1\n",
            " content://files/document/1",
            "",
        ).forEach { data ->
            assertNull(externalPlaybackTarget(Intent.ACTION_VIEW, data, "video/mp4", null), data)
        }
        assertNull(externalPlaybackTarget(Intent.ACTION_VIEW, null, "video/mp4", null))
    }

    @Test
    fun an_overlong_address_is_refused_before_anything_parses_it() {
        val document = "content://files/" + "d".repeat(MAX_EXTERNAL_STREAM_URL_CHARS)

        assertNull(externalPlaybackTarget(Intent.ACTION_VIEW, document, "video/mp4", null))
    }

    @Test
    fun shared_text_plays_its_first_web_link() {
        assertEquals(
            ExternalPlaybackTarget("https://example.com/v.mp4", ExternalPlaybackSource.Web),
            externalPlaybackTarget(Intent.ACTION_SEND, null, "text/plain", "片名 https://example.com/v.mp4 来自某应用"),
        )
        assertNull(externalPlaybackTarget(Intent.ACTION_SEND, null, "text/plain", "只有文字，没有链接"))
        assertNull(externalPlaybackTarget(Intent.ACTION_SEND, null, "text/plain", "ftp://example.com/v.mp4"))
        assertNull(externalPlaybackTarget(Intent.ACTION_SEND, null, "text/plain", null))
        assertNull(externalPlaybackTarget(Intent.ACTION_SEND, null, "image/jpeg", "https://example.com/v.mp4"))
    }

    @Test
    fun a_shared_link_is_read_from_the_text_never_from_the_data() {
        assertNull(externalPlaybackTarget(Intent.ACTION_SEND, "https://example.com/v.mp4", "text/plain", null))
    }

    @Test
    fun other_actions_are_refused() {
        listOf(Intent.ACTION_EDIT, Intent.ACTION_MAIN, Intent.ACTION_SEND_MULTIPLE, "", null).forEach { action ->
            assertNull(externalPlaybackTarget(action, "content://files/document/1", "video/mp4", null), "$action")
        }
    }

    @Test
    fun log_labels_are_fixed_words_whatever_the_intent_says() {
        assertEquals("view", externalPlaybackVia(Intent.ACTION_VIEW))
        assertEquals("send", externalPlaybackVia(Intent.ACTION_SEND))
        assertEquals("other", externalPlaybackVia("com.example.STEAL https://secret.example/token"))
        assertEquals("other", externalPlaybackVia(null))
        assertEquals("https", externalPlaybackSchemeLabel("https://secret.example/token?api_key=1"))
        assertEquals("content", externalPlaybackSchemeLabel("content://files/document/1"))
        assertEquals("other", externalPlaybackSchemeLabel("javascript:alert(1)"))
        assertEquals("none", externalPlaybackSchemeLabel("no-scheme-here"))
        assertEquals("none", externalPlaybackSchemeLabel(null))
    }
}
