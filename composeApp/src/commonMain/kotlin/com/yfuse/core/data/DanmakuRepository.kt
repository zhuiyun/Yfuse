package com.yfuse.core.data

import com.yfuse.core.logging.AppLog
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

enum class DanmakuKind { Scroll, Top, Bottom }

data class DanmakuComment(
    val timeMs: Long,
    val text: String,
    val color: Long = 0xFFFFFF,
    val kind: DanmakuKind = DanmakuKind.Scroll,
)

data class DanmakuMedia(
    val id: String,
    val title: String,
    val episode: Int?,
    val season: Int? = null,
    val serverId: String?,
)

/**
 * Loads an arbitrary user-provided endpoint. The endpoint can be a direct URL or a template using
 * `{id}`, `{title}`, `{season}`, `{episode}` and `{serverId}`. Bilibili XML, DPlayer tuples
 * and common JSON object formats are accepted.
 */
class DanmakuRepository(private val client: HttpClient) {

    suspend fun load(template: String, media: DanmakuMedia): Result<List<DanmakuComment>> =
        withContext(Dispatchers.Default) {
            val url = try {
                resolveUrl(template, media)
            } catch (error: IllegalArgumentException) {
                AppLog.warning(
                    category = "danmaku",
                    event = "template_invalid",
                    message = "Danmaku URL template is invalid",
                    throwable = error,
                )
                return@withContext Result.failure(error)
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                AppLog.warning(
                    category = "danmaku",
                    event = "scheme_invalid",
                    message = "Danmaku URL uses an unsupported scheme",
                )
                return@withContext Result.failure(
                    IllegalArgumentException("弹幕链接必须以 http:// 或 https:// 开头"),
                )
            }
            try {
                val body: String = client.get(url).body()
                val comments = DanmakuParser.parse(body)
                if (comments.isEmpty()) {
                    AppLog.warning(
                        category = "danmaku",
                        event = "response_unrecognized",
                        message = "Danmaku endpoint returned no recognized comments",
                        attributes = mapOf("responseChars" to body.length.toString()),
                    )
                    Result.failure(IllegalStateException("接口已响应，但没有识别到弹幕数据"))
                } else {
                    AppLog.info(
                        category = "danmaku",
                        event = "loaded",
                        message = "Danmaku comments loaded",
                        attributes = mapOf("commentCount" to comments.size.toString()),
                    )
                    Result.success(comments)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ResponseException) {
                AppLog.warning(
                    category = "danmaku",
                    event = "http_failed",
                    message = "Danmaku endpoint returned an HTTP error",
                    throwable = error,
                    attributes = mapOf("status" to error.response.status.value.toString()),
                )
                Result.failure(IllegalStateException(error.response.status.value.toDanmakuError()))
            } catch (error: Throwable) {
                // Ktor exception messages include the full request URL. A user template may
                // carry a token, so never surface the raw exception in the player UI.
                AppLog.error(
                    category = "danmaku",
                    event = "request_failed",
                    message = "Danmaku endpoint request failed",
                    throwable = error,
                )
                Result.failure(IllegalStateException("弹幕接口连接失败，请检查地址和网络"))
            }
        }

    companion object {
        fun resolveUrl(template: String, media: DanmakuMedia): String {
            require(template.isNotBlank()) { "请先在个人中心配置弹幕链接" }
            return template
                .replace("{id}", encodeUrlComponent(media.id))
                .replace("{title}", encodeUrlComponent(media.title))
                .replace("{season}", media.season?.toString().orEmpty())
                .replace("{episode}", media.episode?.toString().orEmpty())
                .replace("{serverId}", encodeUrlComponent(media.serverId.orEmpty()))
        }

        private fun encodeUrlComponent(value: String): String = buildString {
            value.encodeToByteArray().forEach { byte ->
                val unsigned = byte.toInt() and 0xFF
                val safe =
                    unsigned in 'a'.code..'z'.code ||
                        unsigned in 'A'.code..'Z'.code ||
                        unsigned in '0'.code..'9'.code ||
                        unsigned == '-'.code ||
                        unsigned == '_'.code ||
                        unsigned == '.'.code ||
                        unsigned == '~'.code
                if (safe) {
                    append(unsigned.toChar())
                } else {
                    append('%')
                    append(HEX[unsigned ushr 4])
                    append(HEX[unsigned and 0x0F])
                }
            }
        }

        private const val HEX = "0123456789ABCDEF"

        private fun Int.toDanmakuError(): String = when (this) {
            401, 403 -> "弹幕接口拒绝访问（$this）"
            404 -> "弹幕接口不存在（404）"
            408 -> "弹幕接口请求超时（408）"
            429 -> "弹幕接口请求过于频繁（429）"
            in 500..599 -> "弹幕接口暂时不可用（$this）"
            else -> "弹幕接口请求失败（$this）"
        }
    }
}

object DanmakuParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val xmlComment = Regex(
        """<d\b[^>]*\bp\s*=\s*["']([^"']*)["'][^>]*>(.*?)</d>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    fun parse(body: String): List<DanmakuComment> {
        val value = body.trim()
        if (value.isEmpty()) return emptyList()
        val comments = if (value.startsWith("<")) {
            parseXml(value)
        } else {
            runCatching { parseJson(json.parseToJsonElement(value)) }.getOrDefault(emptyList())
        }
        return comments
            .asSequence()
            .filter { it.timeMs >= 0L && it.text.isNotBlank() }
            .map { it.copy(text = it.text.trim().take(240)) }
            .sortedBy(DanmakuComment::timeMs)
            .take(5_000)
            .toList()
    }

    private fun parseXml(body: String): List<DanmakuComment> =
        xmlComment.findAll(body).mapNotNull { match ->
            val parts = match.groupValues[1].split(',')
            val timeMs = parts.getOrNull(0)?.toDoubleOrNull()?.times(1_000)?.toLong()
                ?: return@mapNotNull null
            val mode = parts.getOrNull(1)?.toIntOrNull()
            DanmakuComment(
                timeMs = timeMs,
                text = decodeXml(match.groupValues[2]),
                color = parts.getOrNull(3)?.toLongOrNull()?.coerceIn(0, 0xFFFFFF) ?: 0xFFFFFF,
                kind = when (mode) {
                    5 -> DanmakuKind.Top
                    4 -> DanmakuKind.Bottom
                    else -> DanmakuKind.Scroll
                },
            )
        }.toList()

    private fun parseJson(root: JsonElement): List<DanmakuComment> = buildList {
        collectJson(root, this)
    }

    private fun collectJson(element: JsonElement, output: MutableList<DanmakuComment>) {
        when (element) {
            is JsonObject -> {
                val comment = parseObject(element)
                if (comment != null) {
                    output += comment
                } else {
                    element.values.forEach { child -> collectJson(child, output) }
                }
            }
            is JsonArray -> {
                parseTuple(element)?.let(output::add) ?: element.forEach { child ->
                    collectJson(child, output)
                }
            }
            else -> Unit
        }
    }

    private fun parseObject(value: JsonObject): DanmakuComment? {
        val text = value.textValue() ?: return null
        val timeEntry = listOf("progress", "timeMs", "time", "t", "timestamp")
            .firstNotNullOfOrNull { key ->
                (value[key] as? JsonPrimitive)?.doubleOrNull?.let { key to it }
            }
            ?: return null
        val timeMs = when (timeEntry.first) {
            "progress", "timeMs" -> timeEntry.second.toLong()
            else -> if (timeEntry.second > 86_400) {
                timeEntry.second.toLong()
            } else {
                (timeEntry.second * 1_000).toLong()
            }
        }
        val mode = (value["mode"] as? JsonPrimitive)?.contentOrNull
            ?: (value["type"] as? JsonPrimitive)?.contentOrNull
            ?: (value["position"] as? JsonPrimitive)?.contentOrNull
        val color = (value["color"] as? JsonPrimitive)?.contentOrNull.toColor()
        return DanmakuComment(timeMs, text, color, mode.toKind())
    }

    /** DPlayer: `[timeSeconds, type, color, author, text]`; compact `[time, text]` also works. */
    private fun parseTuple(value: JsonArray): DanmakuComment? {
        val time = (value.firstOrNull() as? JsonPrimitive)?.doubleOrNull ?: return null
        val text = when {
            value.size >= 5 -> (value[4] as? JsonPrimitive)?.contentOrNull
            value.size >= 2 -> (value.last() as? JsonPrimitive)?.contentOrNull
            else -> null
        } ?: return null
        val type = (value.getOrNull(1) as? JsonPrimitive)?.intOrNull
        val color = (value.getOrNull(2) as? JsonPrimitive)?.contentOrNull.toColor()
        return DanmakuComment(
            timeMs = (time * 1_000).toLong(),
            text = text,
            color = color,
            kind = when (type) {
                1 -> DanmakuKind.Top
                2 -> DanmakuKind.Bottom
                else -> DanmakuKind.Scroll
            },
        )
    }

    private fun JsonObject.textValue(): String? =
        listOf("content", "text", "body", "message", "m")
            .firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.contentOrNull }

    private fun String?.toKind(): DanmakuKind = when (this?.lowercase()) {
        "5", "top", "fixed-top" -> DanmakuKind.Top
        "4", "bottom", "fixed-bottom" -> DanmakuKind.Bottom
        else -> DanmakuKind.Scroll
    }

    private fun String?.toColor(): Long {
        val value = this?.trim().orEmpty()
        if (value.isEmpty()) return 0xFFFFFF
        return runCatching {
            when {
                value.startsWith("#") -> value.drop(1).toLong(16)
                value.startsWith("0x", ignoreCase = true) -> value.drop(2).toLong(16)
                else -> value.toLong()
            }.coerceIn(0, 0xFFFFFF)
        }.getOrDefault(0xFFFFFF)
    }

    private fun decodeXml(value: String): String = value
        .replace("<![CDATA[", "")
        .replace("]]>", "")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
