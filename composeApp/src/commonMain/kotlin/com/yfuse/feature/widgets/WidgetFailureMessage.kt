package com.yfuse.feature.widgets

/** Scripts and Java bridges can return stacks or signed URLs. None belong in a feed's inline error. */
internal fun widgetFailureMessage(raw: String): String {
    val message = raw.take(8192).lowercase()
    return when {
        "未开播" in message || "已下播" in message || "直播已结束" in message ->
            "直播尚未开始或已经结束。"
        "未返回播放地址" in message || "没有播放地址" in message ->
            "此来源尚未提供可播放地址。"
        "connection reset" in message || "socketexception" in message || "连接中断" in message ->
            "连接暂时中断，请稍后重试或切换网络。"
        "timeout" in message || "timed out" in message || "超时" in message ->
            "内容源响应较慢，请稍后重试。"
        "unknownhost" in message || "unable to resolve" in message || "无法解析" in message ->
            "暂时无法连接内容源，请检查网络后重试。"
        "ssl" in message || "certificate" in message || "证书" in message ->
            "暂时无法建立安全连接，请切换网络后重试。"
        "401" in message || "未登录" in message || "需要登录" in message ->
            "此内容源需要登录，请检查账号状态。"
        "403" in message || "429" in message || "访问受限" in message ->
            "内容源暂时限制访问，请稍后再试。"
        "已停用" in message || "未启用" in message ->
            "组件已停用，可在组件管理中重新启用。"
        "组件不存在" in message || "组件未安装" in message ->
            "此组件尚未添加，请在组件管理中添加。"
        else -> "暂时无法获取内容，请稍后重试。"
    }
}
