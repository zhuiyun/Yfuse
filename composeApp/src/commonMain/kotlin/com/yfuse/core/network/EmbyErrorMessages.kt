package com.yfuse.core.network

/** Maps a domain error to a short, user-facing Chinese message. */
fun EmbyError.toUserMessage(): String =
    when (this) {
        EmbyError.Network -> "无法连接服务器，请检查网络后重试"
        EmbyError.Unauthorized -> "用户名或密码错误"
        is EmbyError.AccessDenied -> "服务器拒绝访问（HTTP 403），请检查服务器访问策略"
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
