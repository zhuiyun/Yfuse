package com.yfuse.core.network

/** Maps a domain error to a short, user-facing Chinese message. */
fun EmbyError.toUserMessage(): String =
    when (this) {
        EmbyError.Network -> "无法连接服务器，请检查网络后重试"
        EmbyError.Unauthorized -> "认证失败，请检查登录信息或重新登录该服务器"
        is EmbyError.AccessDenied ->
            if (provider == "Cloudflare") {
                "访问被 Cloudflare 拦截，请更换网络或联系服务器管理员"
            } else {
                "服务器拒绝访问，请检查防火墙或反向代理访问策略"
            }
        EmbyError.NotFound -> "服务器上找不到该内容，可能已被删除或移动"
        is EmbyError.Server ->
            when (code) {
                502, 503, 504, 522, 524 -> "服务器暂时不可用或响应超时（$code），请稍后重试或切换线路"
                else -> "服务器错误($code)"
            }
        is EmbyError.Unknown -> "出错了:$message"
    }

/** Convenience: pull a user message out of any throwable. */
fun Throwable.toUserMessage(fallback: String): String =
    when (this) {
        is EmbyErrorException -> error.toUserMessage()
        is LocalNetworkPermissionRequiredException -> message ?: fallback
        else -> fallback
    }
