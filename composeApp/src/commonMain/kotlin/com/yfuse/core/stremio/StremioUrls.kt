package com.yfuse.core.stremio

import io.ktor.http.Url
import io.ktor.http.encodeURLParameter

/** Configured paths may contain API keys. Never put these addresses in logs or error messages. */
fun normalizeStremioManifestUrl(input: String): String {
    val trimmed = input.trim()
    val url = if (trimmed.startsWith("stremio://", true)) "https://" + trimmed.substringAfter("://") else trimmed
    require(isStremioHttpUrl(url)) { "请输入有效的 HTTPS、HTTP 或 stremio:// 插件地址" }
    val parsed = Url(url)
    require(
        parsed.encodedPath.endsWith("/manifest.json") && parsed.encodedQuery.isEmpty() && parsed.fragment.isEmpty(),
    ) {
        "插件地址必须以 /manifest.json 结尾；请粘贴配置完成后的完整安装地址"
    }
    return parsed.toString()
}

internal fun isStremioHttpUrl(url: String?): Boolean {
    if (url.isNullOrBlank() ||
        url.length > 16_384 ||
        url.any { it.isWhitespace() || it.isISOControl() || it.category == CharCategory.FORMAT || it == '\\' }
    ) {
        return false
    }
    val parsed = runCatching { Url(url) }.getOrNull() ?: return false
    return parsed.protocol.name in setOf("http", "https") &&
        parsed.host.isNotBlank() &&
        parsed.user == null &&
        parsed.password == null
}

internal fun stremioResourceUrl(
    manifestUrl: String,
    resource: String,
    type: String,
    id: String,
    extras: Map<String, String> = emptyMap(),
): String {
    require(resource in setOf("catalog", "meta", "stream", "subtitles"))
    require(type.isNotBlank() && id.isNotBlank() && id.length <= 2_048)
    val base = normalizeStremioManifestUrl(manifestUrl).removeSuffix("/manifest.json")
    val extra =
        extras.filterValues { it.isNotBlank() }.entries.joinToString("&") { (key, value) ->
            "${key.encodeURLParameter()}=${value.encodeURLParameter()}"
        }
    return "$base/$resource/${type.encodeURLParameter()}/${id.encodeURLParameter()}" +
        (if (extra.isEmpty()) "" else "/$extra") + ".json"
}

fun stremioConfigurationUrl(manifestUrl: String): String =
    normalizeStremioManifestUrl(manifestUrl).removeSuffix("/manifest.json") + "/configure"
