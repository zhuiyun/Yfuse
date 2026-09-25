package com.yfuse.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EmbyErrorMessagesTest {
    @Test
    fun throwable_preserves_cloudflare_specific_access_denied_message() {
        val error =
            EmbyErrorException(
                EmbyError.AccessDenied(
                    provider = "Cloudflare",
                ),
            )

        assertEquals(
            "访问被 Cloudflare 拦截，请更换网络或联系服务器管理员",
            error.toUserMessage("加载失败"),
        )
    }

    @Test
    fun each_named_connection_failure_says_what_to_check_next() {
        assertEquals(
            "证书校验失败：如果服务器只开了 HTTP 或用的是自签名证书，请改用 HTTP 或检查证书",
            EmbyError.Certificate.toUserMessage(),
        )
        assertEquals(
            "找不到这个地址：请检查服务器地址是否拼写正确，并确认设备已联网",
            EmbyError.HostNotFound.toUserMessage(),
        )
        assertEquals(
            "连接超时：请检查端口（Emby/Jellyfin 默认 8096，HTTPS 默认 8920）和网络",
            EmbyError.Timeout.toUserMessage(),
        )
        assertEquals("连接被拒绝：端口没有开放，或服务器没有运行", EmbyError.ConnectionRefused.toUserMessage())
        assertEquals(
            "这个地址不是 Emby/Jellyfin 服务器，或路径不对，请检查端口和路径",
            EmbyError.NotMediaServer.toUserMessage(),
        )
    }

    @Test
    fun an_unrecognized_failure_keeps_its_raw_text_out_of_the_message() {
        val raw = "Unexpected JSON token at offset 0: Expected start of the object"
        val message = EmbyError.Unknown(raw).toUserMessage()

        assertEquals("出错了，请稍后重试", message)
        assertFalse("JSON" in message)
    }

    @Test
    fun a_screen_reading_the_exception_message_gets_the_user_text() {
        // Several screens still show Throwable.message; it used to read "Server(code=500)".
        assertEquals("服务器错误（500），请稍后重试", EmbyErrorException(EmbyError.Server(500)).message)
        assertEquals("无法连接服务器，请检查网络后重试", EmbyErrorException(EmbyError.Network).message)
    }
}
