package com.yfuse.core.data

import com.yfuse.core.logging.AppLog
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Hard cap applied after ContentEncoding has decompressed the response body. */
internal const val MAX_DANMAKU_RESPONSE_BYTES: Int = 4 * 1024 * 1024

internal class DanmakuResponseTooLargeException(
    val maximumBytes: Int,
) : IllegalStateException("Danmaku response exceeds $maximumBytes bytes")

/**
 * Reads at most one byte beyond [maximumBytes], then cancels the remaining stream.
 * This keeps a compressed response from expanding into an unbounded in-memory String.
 */
internal suspend fun ByteReadChannel.readBoundedDanmakuText(
    maximumBytes: Int = MAX_DANMAKU_RESPONSE_BYTES,
): String {
    require(maximumBytes > 0) { "maximumBytes must be positive" }
    val bytes = readRemaining(maximumBytes.toLong() + 1L).readByteArray()
    if (bytes.size > maximumBytes) {
        cancel()
        throw DanmakuResponseTooLargeException(maximumBytes)
    }
    return bytes.decodeToString()
}

enum class DanmakuKind { Scroll, Top, Bottom }

data class DanmakuComment(
    val timeMs: Long,
    val text: String,
    val color: Long = 0xFFFFFF,
    val kind: DanmakuKind = DanmakuKind.Scroll,
    /**
     * How many identical lines this one stands for once 合并重复 has run. 1 means it
     * stands for itself, which is what everything loaded off the wire starts as.
     */
    val repeats: Int = 1,
) {
    /** `笑死 ×128` — what the overlay draws. The count only appears once there is one. */
    val displayText: String get() = if (repeats > 1) "$text ×$repeats" else text
}

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
 *
 * A source that is not a template is treated as a **dandanplay-compatible API root** — the
 * shape every self-hosted 弹幕 server speaks — which is what [search], [episodes] and
 * [match] talk to. That is the whole reason 搜索弹幕 can exist: a template answers "give me
 * the file for this entry" and nothing else, while a root can be asked what it holds.
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
            fetchComments(url)
        }

    /**
     * 搜索弹幕 — what this server has under a keyword.
     *
     * The keyword is the one thing the app cannot guess: a library's folder name, a
     * release group's title and the name a 弹幕 site files a show under routinely differ,
     * and only a person looking at both can say they are the same show.
     */
    suspend fun search(
        source: DanmakuSource,
        keyword: String,
    ): Result<List<DanmakuSearchResult>> = withContext(Dispatchers.Default) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return@withContext Result.success(emptyList())
        val root = source.apiRoot()
            ?: return@withContext Result.failure(
                IllegalArgumentException("这个弹幕源不支持搜索，请填写弹幕服务器地址"),
            )
        fetch("$root/api/v2/search/anime?keyword=${encodeUrlComponent(trimmed)}")
            .map { body -> DanmakuApi.parseSearch(body) }
    }

    /** The 集 list for one search result. */
    suspend fun episodes(
        source: DanmakuSource,
        result: DanmakuSearchResult,
    ): Result<List<DanmakuEpisode>> = withContext(Dispatchers.Default) {
        val root = source.apiRoot()
            ?: return@withContext Result.failure(
                IllegalArgumentException("这个弹幕源不支持搜索，请填写弹幕服务器地址"),
            )
        fetch("$root/api/v2/bangumi/${encodeUrlComponent(result.animeId)}")
            .map { body -> DanmakuApi.parseEpisodes(body, result.title) }
    }

    /**
     * The server's own guess for what is playing, so most entries need no search at all.
     *
     * `search/episodes` is dandanplay's one-shot title+episode lookup. It is a guess and it
     * is sometimes wrong, which is why a hand-picked match outranks it and outlives it.
     */
    suspend fun match(
        source: DanmakuSource,
        media: DanmakuMedia,
    ): Result<DanmakuEpisode?> = withContext(Dispatchers.Default) {
        val root = source.apiRoot() ?: return@withContext Result.success(null)
        val title = media.title.trim()
        if (title.isEmpty()) return@withContext Result.success(null)
        val query = buildString {
            append("$root/api/v2/search/episodes?anime=")
            append(encodeUrlComponent(title))
            media.episode?.let { append("&episode=$it") }
        }
        fetch(query).map { body -> DanmakuApi.parseMatch(body, media.episode) }
    }

    /** The comments for one episode id, which is what a match or a hand-pick resolves to. */
    suspend fun loadEpisode(
        source: DanmakuSource,
        episodeId: String,
    ): Result<List<DanmakuComment>> = withContext(Dispatchers.Default) {
        val root = source.apiRoot()
            ?: return@withContext Result.failure(
                IllegalArgumentException("这个弹幕源不支持按集加载"),
            )
        fetchComments(
            "$root/api/v2/comment/${encodeUrlComponent(episodeId)}?withRelated=true&chConvert=0",
        )
    }

    /**
     * 发送弹幕 — post one line to the episode currently matched.
     *
     * `POST /api/v2/comment/{episodeId}` is dandanplay's shape and what its clones accept.
     * Whether a given server allows anonymous writes is the server's business, so a refusal
     * comes back as the plain HTTP reason rather than being pre-empted here: a 403 means
     * "this server does not take comments from you", which is exactly what the user needs
     * to be told, and guessing in advance would forbid every server that does allow it.
     */
    suspend fun send(
        source: DanmakuSource,
        episodeId: String,
        text: String,
        positionMs: Long,
        color: Long = 0xFFFFFF,
    ): Result<Unit> = withContext(Dispatchers.Default) {
        val message = text.trim().take(120)
        if (message.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("弹幕内容不能为空"))
        }
        val root = source.apiRoot()
            ?: return@withContext Result.failure(
                IllegalArgumentException("这个弹幕源不支持发送弹幕"),
            )
        val body = buildJsonObject {
            // Seconds with two decimals, which is the unit the `p` attribute reads back in.
            put("time", JsonPrimitive(positionMs / 1000.0))
            put("mode", JsonPrimitive(1))
            put("color", JsonPrimitive(color))
            put("comment", JsonPrimitive(message))
        }
        post("$root/api/v2/comment/${encodeUrlComponent(episodeId)}", body.toString()).map { }
    }

    private suspend fun post(url: String, body: String): Result<String> = request(url) {
        client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    private suspend fun fetchComments(url: String): Result<List<DanmakuComment>> =
        fetch(url).mapCatching { body ->
            val comments = DanmakuParser.parse(body)
            if (comments.isEmpty()) {
                AppLog.warning(
                    category = "danmaku",
                    event = "response_unrecognized",
                    message = "Danmaku endpoint returned no recognized comments",
                    attributes = mapOf("responseChars" to body.length.toString()),
                )
                throw IllegalStateException("接口已响应，但没有识别到弹幕数据")
            }
            AppLog.info(
                category = "danmaku",
                event = "loaded",
                message = "Danmaku comments loaded",
                attributes = mapOf("commentCount" to comments.size.toString()),
            )
            comments
        }

    private suspend fun fetch(url: String): Result<String> = request(url) { client.get(url) }

    /**
     * One request, with every failure turned into something safe to put on screen.
     *
     * The scheme check lives here rather than at each call site so a verb added later
     * cannot skip it — a template pointing at `file://` is a user typo, not a request.
     */
    private suspend fun request(
        url: String,
        call: suspend () -> HttpResponse,
    ): Result<String> {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            AppLog.warning(
                category = "danmaku",
                event = "scheme_invalid",
                message = "Danmaku URL uses an unsupported scheme",
            )
            return Result.failure(
                IllegalArgumentException("弹幕链接必须以 http:// 或 https:// 开头"),
            )
        }
        return try {
            // bodyAsChannel runs through Ktor's response pipeline, so ContentEncoding has
            // already decompressed gzip data before this hard limit is applied.
            val body = call().bodyAsChannel().readBoundedDanmakuText()
            Result.success(body)
        } catch (error: CancellationException) {
            throw error
        } catch (error: DanmakuResponseTooLargeException) {
            AppLog.warning(
                category = "danmaku",
                event = "response_too_large",
                message = "Danmaku response exceeded the decompressed size limit",
                attributes = mapOf("maximumBytes" to error.maximumBytes.toString()),
            )
            Result.failure(IllegalStateException("弹幕响应过大，已停止读取"))
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
        /**
         * The server root a `/api/v2/...` path can be hung off, or null when this source is
         * a per-entry template and has no index to ask.
         *
         * A trailing `/api/v2` is accepted and stripped: it is what the server's own docs
         * print, so it is what people paste.
         */
        fun DanmakuSource.apiRoot(): String? {
            if (!supportsSearch) return null
            var value = url.trim().trimEnd('/')
            listOf("/api/v2", "/api/v1").forEach { suffix ->
                if (value.endsWith(suffix, ignoreCase = true)) {
                    value = value.dropLast(suffix.length).trimEnd('/')
                }
            }
            return value.takeIf { it.isNotBlank() }
        }

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
            parseP(match.groupValues[1], decodeXml(match.groupValues[2]))
        }.toList()

    /**
     * The comma-packed attribute both wire formats put everything-but-the-text in.
     *
     * Bilibili's XML writes `time,mode,fontSize,color,timestamp,pool,user,row`; dandanplay
     * and the servers that clone its API write `time,mode,color,uid`. Same name, same first
     * two fields, colour one index apart — so the field count decides, which is exact
     * rather than a guess about which numbers look like a colour.
     */
    private fun parseP(attribute: String, text: String): DanmakuComment? {
        val parts = attribute.split(',')
        val timeMs = parts.getOrNull(0)?.trim()?.toDoubleOrNull()?.times(1_000)?.toLong()
            ?: return null
        val colorIndex = if (parts.size >= 5) 3 else 2
        return DanmakuComment(
            timeMs = timeMs,
            text = text,
            color = parts.getOrNull(colorIndex)?.trim()?.toLongOrNull()?.coerceIn(0, 0xFFFFFF)
                ?: 0xFFFFFF,
            kind = when (parts.getOrNull(1)?.trim()?.toIntOrNull()) {
                5 -> DanmakuKind.Top
                4 -> DanmakuKind.Bottom
                else -> DanmakuKind.Scroll
            },
        )
    }

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
        // `{"p": "12.3,1,16777215,uid", "m": "text"}` — the dandanplay comment. Read first
        // because such an object also has no `time` key at all for the fallback to find.
        (value["p"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.contains(',') }
            ?.let { attribute -> parseP(attribute, text)?.let { return it } }
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

/**
 * The index side of a dandanplay-compatible server: 搜索, 集列表, 自动匹配.
 *
 * Read key by key out of [JsonObject] rather than through `@Serializable` classes. Every one
 * of these servers is somebody's reimplementation and they differ in the small things — an
 * id that is a number here and a string there, `animeTitle` vs `title`, the episode list
 * nested under `bangumi` or sitting at the root. A strict decoder turns each of those into
 * a total failure; this turns them into a missing field.
 */
internal object DanmakuApi {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseSearch(body: String): List<DanmakuSearchResult> =
        root(body)?.animeArray().orEmpty().mapNotNull { entry ->
            val anime = entry as? JsonObject ?: return@mapNotNull null
            val id = anime.textOf("animeId", "id", "bangumiId") ?: return@mapNotNull null
            val title = anime.textOf("animeTitle", "title", "name") ?: return@mapNotNull null
            DanmakuSearchResult(
                animeId = id,
                title = title,
                typeLabel = anime.textOf("typeDescription", "type"),
                episodeCount = anime.textOf("episodeCount")?.toIntOrNull(),
                year = anime.year(),
            )
        }

    fun parseEpisodes(body: String, animeTitle: String): List<DanmakuEpisode> {
        val root = root(body) ?: return emptyList()
        val holder = (root["bangumi"] as? JsonObject) ?: root
        return holder.episodeArray().mapNotNull { it.toEpisode(animeTitle) }
    }

    /**
     * The best of the server's guesses, or null when it made none.
     *
     * Prefers the episode whose number is the one being played: a one-shot lookup for a
     * series returns the whole season when the episode filter is loose, and taking the
     * first entry there would pin every episode to 第1集.
     */
    fun parseMatch(body: String, episodeNumber: Int?): DanmakuEpisode? {
        val anime = root(body)?.animeArray()?.firstNotNullOfOrNull { it as? JsonObject }
            ?: return null
        val title = anime.textOf("animeTitle", "title", "name").orEmpty()
        val episodes = anime.episodeArray().mapNotNull { it.toEpisode(title) }
        if (episodes.isEmpty()) return null
        val wanted = episodeNumber?.toString()
        return episodes.firstOrNull { it.number == wanted } ?: episodes.first()
    }

    private fun root(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun JsonObject.animeArray(): List<JsonElement> =
        (this["animes"] as? JsonArray)
            ?: (this["animeList"] as? JsonArray)
            ?: (this["data"] as? JsonArray)
            ?: emptyList()

    private fun JsonObject.episodeArray(): List<JsonElement> =
        (this["episodes"] as? JsonArray) ?: (this["episodeList"] as? JsonArray) ?: emptyList()

    private fun JsonElement.toEpisode(animeTitle: String): DanmakuEpisode? {
        val episode = this as? JsonObject ?: return null
        val id = episode.textOf("episodeId", "id") ?: return null
        val number = episode.textOf("episodeNumber", "number", "episode")
        val title = episode.textOf("episodeTitle", "title", "name")
            ?: number?.let { "第 $it 集" }
            ?: return null
        return DanmakuEpisode(
            episodeId = id,
            title = title,
            animeTitle = animeTitle,
            number = number,
        )
    }

    /** `2026`, from a plain year field or the leading four digits of a date. */
    private fun JsonObject.year(): String? {
        textOf("year")?.takeIf { it.length == 4 }?.let { return it }
        return textOf("startDate", "airDate", "premiereDate")
            ?.take(4)
            ?.takeIf { candidate -> candidate.length == 4 && candidate.all { it.isDigit() } }
    }

    /** The first of these keys that holds a scalar, number or string alike. */
    private fun JsonObject.textOf(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    }
}
