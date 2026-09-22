package com.yfuse.core.network

/** Maps a domain error to a short, user-facing Chinese message. */
fun EmbyError.toUserMessage(): String =
    when (this) {
        EmbyError.Network -> "无法连接服务器，请检查网络后重试"
        EmbyError.Unauthorized -> "用户名或密码错误"
        is EmbyError.AccessDenied ->
            if (provider == "Cloudflare") {
                "访问被 Cloudflare 拦截，请更换网络或联系服务器管理员"
            } else {
                "服务器拒绝访问，请检查防火墙或反向代理访问策略"
            }
        EmbyError.NotFound -> "服务器上找不到该内容，可能已被删除或移动"
        is EmbyError.Server -> "服务器错误($code)"
        is EmbyError.Unknown -> "出错了:$message"
    }

/** Convenience: pull a user message out of any throwable. */
fun Throwable.toUserMessage(fallback: String): String =
    when (this) {
        is EmbyErrorException -> error.toUserMessage()
        is LocalNetworkPermissionRequiredException -> message ?: fallback
        else -> fallback
    }
