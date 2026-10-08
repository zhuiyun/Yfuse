package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalPlaybackTest {
    @Test
    fun links_into_this_device_or_the_lan_are_recognised_for_a_confirmation() {
        listOf(
            "http://127.0.0.1:9978/proxy/video.mp4",
            "http://localhost/v.mp4",
            "http://[::1]:8080/live/index.m3u8",
            "http://[fe80::1%25wlan0]/v.mp4",
            "http://[fd12:3456::1]/v.mp4",
            "http://[::ffff:192.168.1.2]/v.mp4",
            "http://10.0.0.5/v.mp4",
            "http://172.20.1.1/v.mp4",
            "http://192.168.1.1/admin",
            "http://169.254.10.10/v.mp4",
            "http://100.100.1.1/v.mp4",
            "https://user:secret@nas.local:5005/dav/film.mkv",
            "http://nas/film.mkv",
            "http://router.home.arpa/",
        ).forEach { url -> assertTrue(externalStreamTargetsLocalNetwork(url), url) }
        listOf(
            "https://media.example.com/film.mkv",
            "https://[2001:db8::7]/v.mp4",
            "http://8.8.8.8/v.mp4",
            "http://172.32.0.1/v.mp4",
            "http://192.169.0.1/v.mp4",
        ).forEach { url -> assertFalse(externalStreamTargetsLocalNetwork(url), url) }
    }

    @Test
    fun a_media_address_with_a_sign_in_credential_is_recognised() {
        assertTrue(mediaUrlCarriesCredential("https://emby.example/Videos/1/stream?Static=true&api_key=abc"))
        assertTrue(mediaUrlCarriesCredential("https://plex.example/library/parts/1/file.mkv?X-Plex-Token=abc"))
        assertTrue(mediaUrlCarriesCredential("https://user:secret@dav.example/film.mkv"))
        assertFalse(mediaUrlCarriesCredential("https://cdn.example/film.mkv?Static=true"))
        assertFalse(mediaUrlCarriesCredential("https://cdn.example/film.mkv?api_key="))
        assertFalse(mediaUrlCarriesCredential("content://com.example.documents/film.mkv"))
    }

    @Test
    fun web_address_is_trimmed_and_only_its_scheme_is_normalised() {
        assertEquals(
            ExternalStreamUrl.Accepted("https://Media.Example.com/Films/A%20B.mkv?Token=Q"),
            parseExternalStreamUrl("  HTTPS://Media.Example.com/Films/A%20B.mkv?Token=Q \n"),
        )
        assertEquals(
            ExternalStreamUrl.Accepted("http://192.168.1.20:8096/stream.m3u8"),
            parseExternalStreamUrl("http://192.168.1.20:8096/stream.m3u8"),
        )
    }

    @Test
    fun loopback_lan_ipv6_and_typed_credentials_are_all_addresses_people_paste() {
        listOf(
            "http://127.0.0.1:9978/proxy/video.mp4",
            "http://[::1]:8080/live/index.m3u8",
            "https://[2001:db8::7]/v.mp4",
            "https://user:secret@nas.local:5005/dav/film.mkv",
            "https://example.com",
        ).forEach { address ->
            assertIs<ExternalStreamUrl.Accepted>(parseExternalStreamUrl(address), address)
        }
    }

    @Test
    fun anything_but_http_or_https_is_refused() {
        listOf(
            "ftp://example.com/v.mp4",
            "rtsp://camera.local/stream",
            "file:///sdcard/Movies/a.mp4",
            "content://media/external/video/media/7",
            "javascript:alert(1)",
            "intent://play#Intent;scheme=http;end",
            "example.com/video.mp4",
            "http:/example.com/video.mp4",
            "://example.com",
        ).forEach { address ->
            assertEquals(
                ExternalStreamUrl.Rejected("只支持 http:// 或 https:// 开头的链接"),
                parseExternalStreamUrl(address),
                address,
            )
        }
    }

    @Test
    fun blank_padded_or_invisible_characters_are_refused_not_repaired() {
        assertEquals(ExternalStreamUrl.Rejected("请输入视频链接"), parseExternalStreamUrl("  \n "))
        listOf(
            "https://example.com/a b.mp4",
            "https://example.com/a\tb.mp4",
            "https://example.com/a\nb.mp4",
            "https://exa​mple.com/a.mp4",
            "https://example.com/‮gnp.exe",
            "https://example.com/\u0000.mp4",
        ).forEach { address ->
            assertEquals(
                ExternalStreamUrl.Rejected("链接中不能有空格、换行或不可见字符"),
                parseExternalStreamUrl(address),
            )
        }
    }

    @Test
    fun host_and_port_are_checked() {
        listOf("http://", "https:///path", "http://user@/", "http://:8080/a").forEach { address ->
            assertEquals(ExternalStreamUrl.Rejected("链接缺少主机地址"), parseExternalStreamUrl(address), address)
        }
        listOf("http://host:0/", "http://host:65536/", "http://host:80a/", "http://host:/a", "http://[::1]:x/")
            .forEach { address ->
                assertEquals(ExternalStreamUrl.Rejected("链接的端口无效"), parseExternalStreamUrl(address), address)
            }
        listOf("http://[::1/a", "http://[::1]8080/a").forEach { address ->
            assertEquals(ExternalStreamUrl.Rejected("链接的主机地址无效"), parseExternalStreamUrl(address), address)
        }
    }

    @Test
    fun an_address_longer_than_the_limit_is_refused_whole() {
        val prefix = "https://example.com/"
        val atLimit = prefix + "a".repeat(MAX_EXTERNAL_STREAM_URL_CHARS - prefix.length)
        assertIs<ExternalStreamUrl.Accepted>(parseExternalStreamUrl(atLimit))
        assertEquals(
            ExternalStreamUrl.Rejected("链接过长，最多 $MAX_EXTERNAL_STREAM_URL_CHARS 个字符"),
            parseExternalStreamUrl(atLimit + "a"),
        )
    }

    @Test
    fun rejection_reasons_never_echo_the_address() {
        val secret = "https://host:99999/private-token-abc"
        val rejected = assertIs<ExternalStreamUrl.Rejected>(parseExternalStreamUrl(secret))
        assertFalse("private-token-abc" in rejected.reason)
    }

    @Test
    fun shared_text_yields_its_first_link_without_the_words_around_it() {
        assertEquals(
            "https://example.com/v.m3u8",
            firstSharedWebLink("【预告】https://example.com/v.m3u8复制打开"),
        )
        assertEquals(
            "http://example.com/a.mp4?x=1&y=2",
            firstSharedWebLink("看这个 http://example.com/a.mp4?x=1&y=2. 然后 https://second.example/b.mp4"),
        )
        assertEquals("HTTPS://example.com/b.mp4", firstSharedWebLink("\"HTTPS://example.com/b.mp4\""))
        assertNull(firstSharedWebLink("没有链接的一段文字"))
        assertNull(firstSharedWebLink(""))
    }

    @Test
    fun external_entry_carries_no_server_identity() {
        val item = externalPlaybackItem(url = "https://example.com/v.mp4", title = "v")

        assertNull(item.serverId)
        assertEquals("https://example.com/v.mp4", item.url)
        assertEquals("", item.transcodeUrl)
        assertEquals("", item.fallbackTranscodeUrl)
        assertEquals("", item.playSessionId)
        assertEquals(PlaybackMethod.DirectPlay, item.playMethod)
        assertFalse(item.serverTranscodeSupported)
        assertNull(item.transportCredentials)
        assertTrue(item.isExternalPlayback)
    }

    @Test
    fun every_external_entry_gets_a_fresh_identity() {
        val first = externalPlaybackItem(url = "https://example.com/v.mp4", title = "v")
        val second = externalPlaybackItem(url = "https://example.com/v.mp4", title = "v")

        assertTrue(first.id != second.id)
    }

    @Test
    fun library_and_offline_entries_are_not_external() {
        val library = PlayerMediaItem("external-7", "https://emby.example/Videos/7/stream", "", "片", serverId = "s1")
        val legacy = PlayerMediaItem("7", "https://emby.example/Videos/7/stream", "", "片")

        assertFalse(library.isExternalPlayback)
        assertFalse(legacy.isExternalPlayback)
    }

    @Test
    fun file_titles_lose_only_a_real_extension() {
        assertEquals("电影.2024", externalFileTitle("电影.2024.mkv"))
        assertEquals("clip", externalFileTitle("clip.m2ts"))
        assertEquals("Movie.2024", externalFileTitle("Movie.2024"))
        assertEquals("电影.预告", externalFileTitle("电影.预告"))
        assertEquals(".hidden", externalFileTitle(".hidden"))
        assertEquals("archive.tar.gzipped", externalFileTitle("archive.tar.gzipped"))
        assertNull(externalFileTitle("   "))
        assertNull(externalFileTitle(null))
    }

    @Test
    fun stream_title_prefers_the_file_name_and_falls_back_to_the_host() {
        assertEquals("The Matrix", externalStreamTitle("https://cdn.example.com/films/The%20Matrix.mkv?sig=abc"))
        assertEquals("cdn.example.com", externalStreamTitle("https://cdn.example.com/live/abc/index.m3u8"))
        assertEquals("cdn.example.com", externalStreamTitle("https://user:pw@cdn.example.com:8443/"))
        assertEquals("::1", externalStreamTitle("http://[::1]:8080/hls/master.m3u8"))
        assertEquals("season 1", externalStreamTitle("https://example.com/shows/season%201/"))
        assertEquals("bad%zz", externalStreamTitle("https://example.com/bad%zz"))
    }

    @Test
    fun titles_from_outside_are_cleaned_bounded_and_never_empty() {
        assertEquals("片名", externalPlaybackTitle(null, " \u0007片名\n "))
        assertEquals("第一", externalPlaybackTitle("第一", "第二"))
        assertEquals(EXTERNAL_PLAYBACK_FALLBACK_TITLE, externalPlaybackTitle(null, "  ", "\u0000"))
        assertEquals(120, externalPlaybackTitle("长".repeat(500)).length)
    }
}
