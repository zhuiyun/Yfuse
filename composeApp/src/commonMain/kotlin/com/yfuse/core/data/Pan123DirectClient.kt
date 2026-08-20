package com.yfuse.core.data

import com.yfuse.core.logging.AppLog
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private const val PAN123_API_BASE = "https://yun.123pan.com/api"
private const val PAN123_COPY_BASE = "https://yun.123pan.com/b/api/restful/goapi/v1/file/copy"
private const val PAN123_LOGIN_URL = "https://login.123pan.com/api/user/sign_in"
private const val PAN123_MAX_SHARE_ITEMS = 10_000

internal data class Pan123ShareLink(
    val key: String,
    val password: String,
)

internal fun parsePan123ShareLink(value: String): Pan123ShareLink {
    val normalized = value.trim().substringBefore('#')
    require(normalized.startsWith("https://", ignoreCase = true)) { "不是有效的 123 分享链接" }
    val authorityAndPath = normalized.substringAfter("://")
    val host = authorityAndPath.substringBefore('/').substringBefore('?').lowercase()
    require(host.contains("123pan") || host.contains("123865")) { "仅支持 123 云盘分享链接" }

    val path = authorityAndPath.substringAfter('/', "").substringBefore('?')
    val segments = path.split('/').filter(String::isNotBlank)
    val markerIndex = segments.indexOfFirst { it.equals("s", true) || it.equals("123pan", true) }
    val key =
        when {
            markerIndex >= 0 -> segments.getOrNull(markerIndex + 1)
            host.contains(".share.123pan.") -> segments.lastOrNull()
            else -> null
        }.orEmpty()
    require(key.matches(Regex("[A-Za-z0-9_-]{3,128}"))) { "123 分享链接缺少有效分享码" }

    val password =
        normalized
            .substringAfter('?', "")
            .split('&')
            .mapNotNull { parameter ->
                val name = parameter.substringBefore('=', "")
                val content = parameter.substringAfter('=', "")
                name.takeIf { it.equals("pwd", true) }?.let { content }
            }.firstOrNull()
            .orEmpty()
    require(password.matches(Regex("[A-Za-z0-9]*"))) { "123 分享链接的提取码格式无效" }
    return Pan123ShareLink(key, password)
}

internal class Pan123DirectClient(
    private val preferences: TgtoMediaPreferences,
    private val client: HttpClient,
) {
    suspend fun authorize(
        phone: String,
        password: String,
    ): TgtoDirectoryListing =
        loggedPan123Call(
            stage = "authorization",
            successAttributes = { listing -> mapOf("directoryCount" to listing.count.toString()) },
        ) {
            val normalizedPhone = phone.filterNot(Char::isWhitespace)
            require(normalizedPhone.isNotBlank()) { "请输入 123 登录手机号" }
            require(password.isNotBlank()) { "请输入 123 登录密码" }

            val response =
                client.post(PAN123_LOGIN_URL) {
                    pan123Headers()
                    contentType(ContentType.Application.Json)
                    setBody(
                        buildJsonObject {
                            put("passport", normalizedPhone)
                            put("password", password)
                            put("remember", true)
                        },
                    )
                }
            val payload = response.requirePan123Payload("123 登录失败")
            val token = payload.objectValue("data")?.stringValue("token").orEmpty()
            if (token.isBlank()) throw TgtoApiException("123 登录成功但没有返回授权令牌")

            val listing = listDirectoriesWithToken(token, "0")
            preferences.savePan123Authorization(normalizedPhone, token)
            listing
        }

    suspend fun listDirectories(parentId: String = "0"): TgtoDirectoryListing =
        loggedPan123Call(
            stage = "directory_listing",
            successAttributes = { listing -> mapOf("directoryCount" to listing.count.toString()) },
        ) {
            val token = preferences.pan123Token()
            if (token.isBlank()) throw TgtoApiException("请先在设置中登录 123 云盘")
            listDirectoriesWithToken(token, parentId)
        }

    fun clearAuthorization() {
        preferences.clearPan123Authorization()
        AppLog.info(
            category = "media.pan123",
            event = "authorization_cleared",
            message = "123 cloud authorization was cleared",
        )
    }

    suspend fun transfer(
        shareUrl: String,
        targetFolderId: String,
    ): String =
        loggedPan123Call(
            stage = "transfer",
            successAttributes = { result ->
                mapOf("outcome" to if (result == "转存成功") "completed" else "submitted")
            },
        ) {
            transferDirect(shareUrl, targetFolderId)
        }

    private suspend fun transferDirect(
        shareUrl: String,
        targetFolderId: String,
    ): String {
        val token = preferences.pan123Token()
        if (token.isBlank()) throw TgtoApiException("请先在设置中登录 123 云盘")
        require(targetFolderId.isNotBlank()) { "请先选择 123 保存目录" }
        val share = parsePan123ShareLink(shareUrl)
        val files = readSharedFiles(token, share)
        if (files.isEmpty()) throw TgtoApiException("该 123 分享中没有可转存的文件")

        val response =
            client.post("$PAN123_COPY_BASE/save") {
                pan123Headers(token)
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put(
                            "fileList",
                            buildJsonArray {
                                files.forEach { file ->
                                    add(
                                        buildJsonObject {
                                            put("fileID", file.id.asJsonNumberOrString())
                                            put("size", file.size)
                                            put("etag", file.etag)
                                            put("type", file.type)
                                            put("parentFileID", targetFolderId.asJsonNumberOrString())
                                            put("fileName", file.name)
                                            put("driveID", 0)
                                        },
                                    )
                                }
                            },
                        )
                        put("shareKey", share.key)
                        put("sharePwd", share.password)
                        put("currentLevel", 0)
                    },
                )
            }
        val submitted = response.requirePan123Payload("提交 123 转存任务失败")
        val taskId = submitted.objectValue("data")?.stringValue("taskID").orEmpty()
        AppLog.info(
            category = "media.pan123",
            event = "transfer_submitted",
            message = "123 cloud transfer request was accepted",
            attributes =
                mapOf(
                    "itemCount" to files.size.toString(),
                    "hasTask" to taskId.isNotBlank().toString(),
                ),
        )
        if (taskId.isBlank()) return "转存任务已提交"

        repeat(60) {
            delay(1_000L)
            val polled =
                client
                    .get("$PAN123_COPY_BASE/save/get") {
                        pan123Headers(token)
                        parameter("taskID", taskId)
                    }.requirePan123Payload("查询 123 转存进度失败")
            val data = polled.objectValue("data")
            when (data?.intValue("status")) {
                2 -> return "转存成功"
                3, 4, -1 -> throw TgtoApiException(polled.message("123 转存失败"))
            }
        }
        throw TgtoApiException("123 转存任务仍在处理中，请稍后到云盘目录查看")
    }

    private suspend fun <T> loggedPan123Call(
        stage: String,
        successAttributes: (T) -> Map<String, String> = { emptyMap() },
        block: suspend () -> T,
    ): T {
        AppLog.info(
            category = "media.pan123",
            event = "${stage}_started",
            message = "123 cloud operation started",
        )
        return try {
            block().also { result ->
                AppLog.info(
                    category = "media.pan123",
                    event = "${stage}_succeeded",
                    message = "123 cloud operation succeeded",
                    attributes = successAttributes(result),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AppLog.warning(
                category = "media.pan123",
                event = "${stage}_failed",
                message = "123 cloud operation failed",
                attributes = error.pan123DiagnosticAttributes(stage),
            )
            throw error
        }
    }

    private suspend fun listDirectoriesWithToken(
        token: String,
        parentId: String,
    ): TgtoDirectoryListing {
        val normalizedParentId = parentId.ifBlank { "0" }
        val items = mutableListOf<TgtoDirectoryItem>()
        var next = "0"
        var pageCount = 0
        while (pageCount++ < 100) {
            val payload = requestFileList(token, normalizedParentId, next)
            val data = payload.objectValue("data")
            data
                ?.arrayValue("InfoList")
                .orEmpty()
                .mapNotNull { it as? JsonObject }
                .filter { it.intValue("Type") == 1 && it.booleanValue("Trashed") != true }
                .mapNotNullTo(items) { item ->
                    val id = item.stringValue("FileId").orEmpty()
                    if (id.isBlank()) return@mapNotNullTo null
                    TgtoDirectoryItem(
                        id = id,
                        name = item.stringValue("FileName").orEmpty(),
                        parentId = item.stringValue("ParentFileId") ?: normalizedParentId,
                    )
                }
            val following = data?.stringValue("Next").orEmpty()
            if (following.isBlank() || following == "-1" || following == next) break
            next = following
        }
        return TgtoDirectoryListing(
            success = true,
            count = items.size,
            items = items,
            parentId = normalizedParentId,
        )
    }

    private suspend fun requestFileList(
        token: String,
        parentId: String,
        next: String,
    ): JsonObject =
        client
            .get("$PAN123_API_BASE/file/list") {
                pan123Headers(token)
                parameter("driveId", 0)
                parameter("limit", 100)
                parameter("next", next)
                parameter("orderDirection", "asc")
                parameter("parentFileId", parentId)
                parameter("inDirectSpace", false)
                parameter("event", "homeListFile")
                parameter("trashed", false)
            }.requirePan123Payload("读取 123 云盘目录失败")

    private suspend fun readSharedFiles(
        token: String,
        share: Pan123ShareLink,
    ): List<Pan123SharedFile> {
        val files = mutableListOf<Pan123SharedFile>()
        val pendingParents = mutableListOf("0")
        var parentIndex = 0
        while (parentIndex < pendingParents.size) {
            val parentId = pendingParents[parentIndex++]
            for (page in 1..100) {
                val payload =
                    client
                        .get("$PAN123_API_BASE/share/get") {
                            pan123Headers(token)
                            parameter("ShareKey", share.key)
                            parameter("SharePwd", share.password)
                            parameter("limit", 100)
                            parameter("Page", page)
                            parameter("parentFileId", parentId)
                            parameter("orderBy", "file_name")
                            parameter("orderDirection", "asc")
                            parameter("event", "homeListFile")
                        }.requirePan123Payload("读取 123 分享内容失败")
                val data = payload.objectValue("data")
                val pageItems = data?.arrayValue("InfoList").orEmpty().mapNotNull { it as? JsonObject }
                pageItems.forEach { item ->
                    val id = item.stringValue("FileId").orEmpty()
                    val name = item.stringValue("FileName").orEmpty()
                    if (id.isBlank() || name.isBlank()) return@forEach
                    val file =
                        Pan123SharedFile(
                            id = id,
                            name = name,
                            etag = item.stringValue("Etag").orEmpty(),
                            size = item.longValue("Size") ?: 0L,
                            type = item.intValue("Type") ?: 0,
                        )
                    files += file
                    if (file.type == 1) pendingParents += file.id
                    if (files.size > PAN123_MAX_SHARE_ITEMS) {
                        throw TgtoApiException("分享内容超过 $PAN123_MAX_SHARE_ITEMS 项，无法在 App 内转存")
                    }
                }
                val next = data?.stringValue("Next")
                if (pageItems.size < 100 || next == "-1") break
            }
        }
        return files
    }
}

private data class Pan123SharedFile(
    val id: String,
    val name: String,
    val etag: String,
    val size: Long,
    val type: Int,
)

private fun HttpRequestBuilder.pan123Headers(token: String = "") {
    header(HttpHeaders.Accept, "*/*")
    header(HttpHeaders.UserAgent, "Mozilla/5.0 (Linux; Android) Yfuse/1.0")
    header("App-Version", "3")
    header("Platform", "open_platform")
    if (token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $token")
}

private suspend fun HttpResponse.requirePan123Payload(action: String): JsonObject {
    val payload =
        try {
            body<JsonObject>()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw TgtoApiException("$action（HTTP ${status.value}）", "http_${status.value}")
        }
    val code = payload.intValue("code")
    if (status.value !in 200..299 || code != 0) {
        throw TgtoApiException(
            payload.message("$action（HTTP ${status.value}）"),
            code?.toString() ?: "http_${status.value}",
        )
    }
    return payload
}

private fun JsonObject.message(fallback: String): String =
    stringValue("message")
        ?.takeUnless { it.equals("ok", true) }
        ?: stringValue("error")
        ?: objectValue("data")?.stringValue("message")
        ?: fallback

private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arrayValue(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.stringValue(key: String): String? =
    this[key]
        ?.takeUnless { it.toString() == "null" }
        ?.jsonPrimitive
        ?.contentOrNull

private fun JsonObject.intValue(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

private fun JsonObject.longValue(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

private fun JsonObject.booleanValue(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

private fun String.asJsonNumberOrString(): JsonPrimitive = toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(this)

private fun Throwable.pan123DiagnosticAttributes(stage: String): Map<String, String> {
    val diagnosticMessage = message.orEmpty()
    return buildMap {
        put("stage", stage)
        put(
            "failureType",
            when (this@pan123DiagnosticAttributes) {
                is TgtoApiException -> "api"
                is IllegalArgumentException -> "validation"
                else -> "network"
            },
        )
        put(
            "reason",
            when {
                this@pan123DiagnosticAttributes is IllegalArgumentException -> "invalid_input"
                diagnosticMessage.contains("授权") || diagnosticMessage.contains("登录") ->
                    "authorization"
                diagnosticMessage.contains("目录") -> "directory_listing"
                diagnosticMessage.contains("分享") -> "share_listing"
                diagnosticMessage.contains("转存") -> "transfer"
                diagnosticMessage.contains("HTTP") -> "http_error"
                else -> "request_failed"
            },
        )
        (this@pan123DiagnosticAttributes as? TgtoApiException)
            ?.code
            ?.take(32)
            ?.let { put("code", it) }
    }
}
