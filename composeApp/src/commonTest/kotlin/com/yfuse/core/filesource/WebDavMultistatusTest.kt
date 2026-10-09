package com.yfuse.core.filesource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebDavMultistatusTest {
    @Test
    fun alist_listing_resolves_prefixed_names_and_skips_the_folder_itself() {
        val resources = parseWebDavMultistatus(ALIST_LISTING)
        val children = webDavChildren(listOf("dav", "阿里云盘"), resources)

        assertEquals(
            listOf(
                FileSourceEntry("电影", directory = true, modifiedEpochMs = 1_700_000_000_000L),
                FileSourceEntry(
                    "Dune.Part.Two.2024.2160p.UHD.BluRay.DV.HDR.mkv",
                    directory = false,
                    sizeBytes = 64_424_509_440L,
                    modifiedEpochMs = 1_700_000_000_000L,
                ),
                FileSourceEntry("Dune.Part.Two.2024.chs.ass", directory = false, sizeBytes = 91_234L),
            ),
            children,
        )
    }

    @Test
    fun apache_live_property_prefixes_are_read_through_their_namespace() {
        val resources = parseWebDavMultistatus(APACHE_LISTING)

        assertEquals(
            listOf(
                WebDavResource(listOf("webdav", "Movies"), collection = true, lastModifiedEpochMs = 1_726_912_800_000L),
                WebDavResource(
                    listOf("webdav", "Movies", "A B & C.mkv"),
                    collection = false,
                    contentLength = 1_024L,
                    lastModifiedEpochMs = 1_726_912_800_000L,
                ),
            ),
            resources,
        )
    }

    @Test
    fun a_property_reported_missing_is_absent_rather_than_zero() {
        val resource = parseWebDavMultistatus(APACHE_LISTING).first()

        assertNull(resource.contentLength)
    }

    @Test
    fun default_namespace_and_absolute_hrefs_are_both_understood() {
        val xml =
            """
            <multistatus xmlns="DAV:">
              <response><href>https://nas.example:5006/share/</href>
                <propstat><prop><resourcetype><collection/></resourcetype></prop>
                <status>HTTP/1.1 200 OK</status></propstat></response>
              <response><href>https://nas.example:5006/share/Clip%201.mp4</href>
                <propstat><prop><resourcetype/><getcontentlength>42</getcontentlength></prop>
                <status>HTTP/1.1 200 OK</status></propstat></response>
            </multistatus>
            """.trimIndent()

        assertEquals(
            listOf(FileSourceEntry("Clip 1.mp4", directory = false, sizeBytes = 42L)),
            webDavChildren(listOf("share"), parseWebDavMultistatus(xml)),
        )
    }

    @Test
    fun an_href_from_another_namespace_is_not_the_resource_path() {
        val xml =
            """
            <D:multistatus xmlns:D="DAV:" xmlns:x="urn:example">
              <D:response>
                <D:href>/dav/a.mkv</D:href>
                <D:propstat><D:prop><x:href>/elsewhere</x:href><D:getcontentlength>7</D:getcontentlength></D:prop>
                <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
            </D:multistatus>
            """.trimIndent()

        assertEquals(listOf("dav", "a.mkv"), parseWebDavMultistatus(xml).single().pathSegments)
    }

    @Test
    fun a_response_with_an_error_status_is_dropped() {
        val xml =
            """
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/dav/gone.mkv</D:href><D:status>HTTP/1.1 404 Not Found</D:status></D:response>
              <D:response><D:href>/dav/kept.mkv</D:href>
                <D:propstat><D:prop><D:getcontentlength>1</D:getcontentlength></D:prop>
                <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
            </D:multistatus>
            """.trimIndent()

        assertEquals(listOf(listOf("dav", "kept.mkv")), parseWebDavMultistatus(xml).map { it.pathSegments })
    }

    @Test
    fun a_proxy_that_rewrote_the_prefix_still_yields_the_children() {
        // Asked for /webdav/Movies/, answered as /Movies/: the shallowest response is the folder.
        val xml =
            """
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/Movies/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
                <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
              <D:response><D:href>/Movies/Up.mkv</D:href>
                <D:propstat><D:prop><D:resourcetype/></D:prop>
                <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
              <D:response><D:href>/Movies/Extras/deep.mkv</D:href>
                <D:propstat><D:prop><D:resourcetype/></D:prop>
                <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
            </D:multistatus>
            """.trimIndent()

        assertEquals(
            listOf("Up.mkv"),
            webDavChildren(listOf("webdav", "Movies"), parseWebDavMultistatus(xml)).map { it.name },
        )
    }

    @Test
    fun cdata_entities_and_comments_are_handled() {
        val xml =
            """
            <?xml version="1.0"?>
            <!DOCTYPE multistatus [ <!ENTITY boom "never expanded"> ]>
            <!-- a comment with <tags> -->
            <D:multistatus xmlns:D='DAV:'>
              <D:response><D:href><![CDATA[/dav/Tom & Jerry.mkv]]></D:href>
                <D:propstat><D:prop><D:getcontentlength>&#51;</D:getcontentlength></D:prop>
                <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
              <D:response><D:href>/dav/R&amp;D &#x4E2D;&boom;.mkv</D:href>
                <D:propstat><D:prop/><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
            </D:multistatus>
            """.trimIndent()

        val resources = parseWebDavMultistatus(xml)

        assertEquals(listOf("dav", "Tom & Jerry.mkv"), resources[0].pathSegments)
        assertEquals(3L, resources[0].contentLength)
        assertEquals(listOf("dav", "R&D 中&boom;.mkv"), resources[1].pathSegments)
    }

    @Test
    fun an_html_error_page_is_not_a_listing() {
        assertFailsWith<WebDavParseException> {
            parseWebDavMultistatus("<html><body>Login required</body></html>")
        }
        assertFailsWith<WebDavParseException> {
            parseWebDavMultistatus("<D:multistatus xmlns:D=\"DAV:\"><D:response")
        }
    }

    @Test
    fun malformed_escapes_are_kept_and_utf8_is_decoded() {
        assertEquals(listOf("dav", "100% 真实.mkv"), webDavHrefSegments("/dav/100%25%20%E7%9C%9F%E5%AE%9E.mkv"))
        assertEquals(listOf("a%zz"), webDavHrefSegments("/a%zz"))
        assertEquals(listOf("x"), webDavHrefSegments("http://host/x?query=1#frag"))
    }

    @Test
    fun http_dates_parse_to_epoch_and_other_formats_are_null() {
        assertEquals(784_887_151_000L, parseHttpDate("Tue, 15 Nov 1994 08:12:31 GMT"))
        assertEquals(0L, parseHttpDate("Thu, 01 Jan 1970 00:00:00 GMT"))
        assertNull(parseHttpDate("Mon, 01 Jan 0001 00:00:00 GMT"))
        assertNull(parseHttpDate("2024-01-01T00:00:00Z"))
        assertNull(parseHttpDate(""))
    }

    @Test
    fun encoded_path_segments_round_trip() {
        listOf("电影", "A B+C;D", "100%", "Tom & Jerry", "emoji 🎬", "slash/inside").forEach { name ->
            val encoded = encodePathSegment(name)
            assertTrue(encoded.all { it.code < 0x80 && it != '/' && it != ' ' }, encoded)
            assertEquals(name, decodePathSegment(encoded))
        }
    }

    private companion object {
        val ALIST_LISTING =
            """
            <?xml version="1.0" encoding="UTF-8"?><D:multistatus xmlns:D="DAV:"><D:response><D:href>/dav/%E9%98%BF%E9%87%8C%E4%BA%91%E7%9B%98/</D:href><D:propstat><D:prop><D:displayname>阿里云盘</D:displayname><D:getlastmodified>Mon, 01 Jan 0001 00:00:00 GMT</D:getlastmodified><D:resourcetype><D:collection xmlns:D="DAV:"/></D:resourcetype><D:supportedlock><D:lockentry xmlns:D="DAV:"><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype></D:lockentry></D:supportedlock></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response><D:response><D:href>/dav/%E9%98%BF%E9%87%8C%E4%BA%91%E7%9B%98/%E7%94%B5%E5%BD%B1/</D:href><D:propstat><D:prop><D:displayname>电影</D:displayname><D:getlastmodified>Tue, 14 Nov 2023 22:13:20 GMT</D:getlastmodified><D:resourcetype><D:collection xmlns:D="DAV:"/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response><D:response><D:href>/dav/%E9%98%BF%E9%87%8C%E4%BA%91%E7%9B%98/Dune.Part.Two.2024.2160p.UHD.BluRay.DV.HDR.mkv</D:href><D:propstat><D:prop><D:displayname>Dune.Part.Two.2024.2160p.UHD.BluRay.DV.HDR.mkv</D:displayname><D:getcontentlength>64424509440</D:getcontentlength><D:getlastmodified>Tue, 14 Nov 2023 22:13:20 GMT</D:getlastmodified><D:getcontenttype>video/x-matroska</D:getcontenttype><D:resourcetype></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response><D:response><D:href>/dav/%E9%98%BF%E9%87%8C%E4%BA%91%E7%9B%98/Dune.Part.Two.2024.chs.ass</D:href><D:propstat><D:prop><D:getcontentlength>91234</D:getcontentlength><D:resourcetype></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>
            """.trimIndent()

        val APACHE_LISTING =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:ns0="DAV:">
            <D:response xmlns:lp1="DAV:" xmlns:lp2="http://apache.org/dav/props/">
            <D:href>/webdav/Movies/</D:href>
            <D:propstat>
            <D:prop>
            <lp1:resourcetype><D:collection/></lp1:resourcetype>
            <lp1:getlastmodified>Sat, 21 Sep 2024 10:00:00 GMT</lp1:getlastmodified>
            </D:prop>
            <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
            <D:propstat>
            <D:prop>
            <ns0:getcontentlength/>
            </D:prop>
            <D:status>HTTP/1.1 404 Not Found</D:status>
            </D:propstat>
            </D:response>
            <D:response xmlns:lp1="DAV:" xmlns:lp2="http://apache.org/dav/props/">
            <D:href>/webdav/Movies/A%20B%20&amp;%20C.mkv</D:href>
            <D:propstat>
            <D:prop>
            <lp1:resourcetype/>
            <lp1:getcontentlength>1024</lp1:getcontentlength>
            <lp1:getlastmodified>Sat, 21 Sep 2024 10:00:00 GMT</lp1:getlastmodified>
            </D:prop>
            <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
            </D:response>
            </D:multistatus>
            """.trimIndent()
    }
}
