package com.yfuse.core.network

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
