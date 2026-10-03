package com.yfuse.core.filesource

import com.yfuse.core2.network.YTransportCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FileSourceAddressTest {
    @Test
    fun webdav_fields_build_an_origin_and_a_decoded_root() {
        val address =
            FileSourceDraft(
                kind = FileSourceKind.WebDav,
                https = true,
                host = "nas.local",
                port = "5006",
                path = "/影视/电影/",
            ).resolveAddress()

        assertEquals(
            FileSourceAddress.Valid("https://nas.local:5006", listOf("影视", "电影"), name = "电影"),
            address,
        )
    }

    @Test
    fun a_standard_port_is_left_out_of_the_origin() {
        val https = FileSourceDraft(host = "dav.example.com", port = "443").resolveAddress()
        val http = FileSourceDraft(host = "dav.example.com", https = false).resolveAddress()

        assertEquals("https://dav.example.com", (https as FileSourceAddress.Valid).origin)
        assertEquals("http://dav.example.com", (http as FileSourceAddress.Valid).origin)
        assertEquals("WebDAV · dav.example.com", http.name)
    }

    @Test
    fun alist_gets_its_port_and_dav_root_filled_in() {
        val bare = FileSourceDraft(kind = FileSourceKind.Alist, https = false, host = "192.168.1.2").resolveAddress()
        val inside =
            FileSourceDraft(kind = FileSourceKind.Alist, https = false, host = "192.168.1.2", path = "阿里云盘")
                .resolveAddress()
        val explicit =
            FileSourceDraft(
                kind = FileSourceKind.Alist,
                https = true,
                host = "pan.example.com",
                path = "/alist/dav/115",
            ).resolveAddress()

        assertEquals(FileSourceAddress.Valid("http://192.168.1.2:5244", listOf("dav"), "Alist · 192.168.1.2"), bare)
        assertEquals(listOf("dav", "阿里云盘"), (inside as FileSourceAddress.Valid).rootSegments)
        assertEquals(
            FileSourceAddress.Valid("https://pan.example.com", listOf("alist", "dav", "115"), "115"),
            explicit,
        )
    }

    @Test
    fun smb_takes_a_share_path_and_a_unc_host() {
        val typed =
            FileSourceDraft(
                kind = FileSourceKind.Smb,
                host = "192.168.1.2",
                path = "Media/Movies",
            ).resolveAddress()
        val unc = FileSourceDraft(kind = FileSourceKind.Smb, host = "\\\\nas\\Media\\Movies").resolveAddress()
        val shares = FileSourceDraft(kind = FileSourceKind.Smb, host = "nas", port = "4450").resolveAddress()

        assertEquals(FileSourceAddress.Valid("smb://192.168.1.2", listOf("Media", "Movies"), "Movies"), typed)
        assertEquals(FileSourceAddress.Valid("smb://nas", listOf("Media", "Movies"), "Movies"), unc)
        assertEquals(FileSourceAddress.Valid("smb://nas:4450", emptyList(), "SMB · nas"), shares)
    }

    @Test
    fun a_pasted_address_is_split_into_the_form_fields() {
        val pasted =
            FileSourceDraft(
                host = "https://alice:s%3Acret@nas.local:5006/dav/%E5%BD%B1%E8%A7%86/?x=1",
            ).withPastedAddress()

        assertEquals(
            FileSourceDraft(
                kind = FileSourceKind.WebDav,
                https = true,
                host = "nas.local",
                port = "5006",
                path = "dav/影视",
                username = "alice",
                password = "s:cret",
            ),
            pasted,
        )
        assertEquals(
            FileSourceDraft(kind = FileSourceKind.Smb, https = false, host = "nas", path = "Media"),
            FileSourceDraft(host = "smb://nas/Media").withPastedAddress(),
        )
        // An Alist form keeps being an Alist when an http address is pasted into it.
        assertEquals(
            FileSourceKind.Alist,
            FileSourceDraft(kind = FileSourceKind.Alist, host = "http://nas:5244").withPastedAddress().kind,
        )
    }

    @Test
    fun ipv6_hosts_are_bracketed() {
        val bare = FileSourceDraft(https = false, host = "fe80::1", port = "8080").resolveAddress()
        val bracketed = FileSourceDraft(kind = FileSourceKind.Smb, host = "[fe80::1]:4450").resolveAddress()

        assertEquals("http://[fe80::1]:8080", (bare as FileSourceAddress.Valid).origin)
        assertEquals("smb://[fe80::1]:4450", (bracketed as FileSourceAddress.Valid).origin)
    }

    @Test
    fun bad_addresses_say_what_is_wrong_without_repeating_them() {
        assertEquals(FileSourceAddress.Invalid("请输入地址"), FileSourceDraft(host = "  ").resolveAddress())
        assertEquals(FileSourceAddress.Invalid("地址格式不正确"), FileSourceDraft(host = "nas local").resolveAddress())
        assertEquals(FileSourceAddress.Invalid("地址格式不正确"), FileSourceDraft(host = "u@nas").resolveAddress())
        assertEquals(
            FileSourceAddress.Invalid("端口需为 1–65535"),
            FileSourceDraft(host = "nas", port = "70000").resolveAddress(),
        )
        assertEquals(
            FileSourceAddress.Invalid("路径不能包含 .."),
            FileSourceDraft(host = "nas", path = "a/../b").resolveAddress(),
        )
    }

    @Test
    fun a_typed_name_wins_and_is_kept_to_one_line() {
        val address = FileSourceDraft(host = "nas", name = "  家里\nNAS  ").resolveAddress()

        assertEquals("家里 NAS", (address as FileSourceAddress.Valid).name)
    }

    @Test
    fun urls_encode_webdav_segments_and_leave_smb_paths_literal() {
        val webDav = FileSource("a", FileSourceKind.WebDav, "n", "https://nas:5006", listOf("dav", "影视"))
        val smb = FileSource("b", FileSourceKind.Smb, "n", "smb://nas", listOf("Media"))

        assertEquals("https://nas:5006/dav/%E5%BD%B1%E8%A7%86/", webDav.url(emptyList(), directory = true))
        assertEquals(
            "https://nas:5006/dav/%E5%BD%B1%E8%A7%86/A%20%231.mkv",
            webDav.url(listOf("A #1.mkv"), directory = false),
        )
        assertEquals("smb://nas/Media/My Movie (2019)/", smb.url(listOf("My Movie (2019)"), directory = true))
        assertEquals("smb://nas/", FileSource("c", FileSourceKind.Smb, "n", "smb://nas").url(emptyList(), true))
    }

    @Test
    fun credentials_split_an_smb_domain_but_not_a_webdav_name() {
        val smb =
            FileSourceCredentials("WORKGROUP\\alice", "pw").transportCredentials(FileSourceKind.Smb)
                as YTransportCredentials.UsernamePassword
        val webDav =
            FileSourceCredentials("WORKGROUP\\alice", "pw").transportCredentials(FileSourceKind.WebDav)
                as YTransportCredentials.UsernamePassword

        assertEquals("WORKGROUP" to "alice", smb.domain to smb.username)
        assertEquals("" to "WORKGROUP\\alice", webDav.domain to webDav.username)
        assertNull(FileSourceCredentials("", "").transportCredentials(FileSourceKind.Smb))
        assertEquals("" to "alice@corp.example", smbAccountParts("alice@corp.example"))
        assertEquals("FileSourceCredentials([redacted])", FileSourceCredentials("a", "secret").toString())
    }

    @Test
    fun a_draft_with_only_a_host_is_unchanged_by_paste_handling() {
        val draft = FileSourceDraft(host = "nas.local", port = "5006")

        assertIs<FileSourceAddress.Valid>(draft.resolveAddress())
        assertEquals(draft, draft.withPastedAddress())
    }
}
