package com.yfuse.core.network

/** Maps a domain error to a short, user-facing Chinese message: the cause, then what to try. */
fun EmbyError.toUserMessage(): String =
    when (this) {
        EmbyError.Network -> "无法连接服务器，请检查网络后重试"
        EmbyError.Certificate -> "证书校验失败：如果服务器只开了 HTTP 或用的是自签名证书，请改用 HTTP 或检查证书"
        EmbyError.HostNotFound -> "找不到这个地址：请检查服务器地址是否拼写正确，并确认设备已联网"
        EmbyError.Timeout -> "连接超时：请检查端口（Emby/Jellyfin 默认 8096，HTTPS 默认 8920）和网络"
        EmbyError.ConnectionRefused -> "连接被拒绝：端口没有开放，或服务器没有运行"
        EmbyError.Unauthorized -> "认证失败，请检查登录信息或重新登录该服务器"
        is EmbyError.AccessDenied ->
            if (provider == "Cloudflare") {
                "访问被 Cloudflare 拦截，请更换网络或联系服务器管理员"
            } else {
                "服务器拒绝访问，请检查防火墙或反向代理访问策略"
            }
        EmbyError.NotFound -> "服务器上找不到该内容，可能已被删除或移动"
        EmbyError.NotMediaServer -> "这个地址不是 Emby/Jellyfin 服务器，或路径不对，请检查端口和路径"
        is EmbyError.Server ->
            when (code) {
                502, 503, 504, 522, 524 -> "服务器暂时不可用或响应超时（$code），请稍后重试或切换线路"
                else -> "服务器错误（$code），请稍后重试"
            }
        // The raw text is a parser or HTTP-client message: it belongs in the diagnostic log, which
        // embyApiCall writes, not in front of the user.
        is EmbyError.Unknown -> "出错了，请稍后重试"
    }

/** Convenience: pull a user message out of any throwable. */
fun Throwable.toUserMessage(fallback: String): String =
    when (this) {
        is EmbyErrorException -> error.toUserMessage()
        is LocalNetworkPermissionRequiredException -> message ?: fallback
        else -> fallback
    }
