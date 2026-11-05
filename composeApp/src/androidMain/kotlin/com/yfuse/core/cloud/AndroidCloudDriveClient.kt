package com.yfuse.core.cloud

import com.yfuse.core.filesource.FileSource
import com.yfuse.core.filesource.FileSourceClient
import com.yfuse.core.filesource.FileSourceCredentials
import com.yfuse.core.filesource.FileSourceEntry
import com.yfuse.core.filesource.FileSourceException
import com.yfuse.core.filesource.FileSourceFailure
import com.yfuse.core.filesource.FileSourceKind
import com.yfuse.core.filesource.FileSourceListing
import com.yfuse.core.filesource.FileSourcePlaybackTarget
import com.yfuse.core.filesource.FileSourceSubtitleCache
import com.yfuse.core.filesource.MAX_FILE_SOURCE_LISTING_ENTRIES
import com.yfuse.core.filesource.utf8SubtitleBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.OffsetDateTime

/** Provider APIs resolve fresh signed links; player engines receive media URLs, never API secrets. */
internal class AndroidCloudDriveClient(
    http: OkHttpClient,
    private val subtitles: FileSourceSubtitleCache,
    private val tokens: CloudTokenRepository = EncryptedCloudTokenRepository(),
    private val pan123Origin: String = "https://open-api.123pan.com",
    private val baiduOrigin: String = "https://pan.baidu.com",
    private val oauthOrigin: String = "https://openapi.baidu.com",
    private val now: () -> Long = System::currentTimeMillis,
) : FileSourceClient {
    private val api =
        http
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    private val media =
        http
            .newBuilder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    private val resolved =
        object : LinkedHashMap<String, CloudEntry>(128, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CloudEntry>): Boolean = size > 2048
        }

    override suspend fun list(
        source: FileSource,
        credentials: FileSourceCredentials,
        path: List<String>,
    ): List<FileSourceEntry> = listBounded(source, credentials, path, MAX_FILE_SOURCE_LISTING_ENTRIES).entries

    override suspend fun listBounded(
        source: FileSource,
        credentials: FileSourceCredentials,
        path: List<String>,
        maxEntries: Int,
    ): FileSourceListing {
        require(maxEntries in 1..MAX_FILE_SOURCE_LISTING_ENTRIES)
        val fullPath = checkedPath(source.rootSegments + path)
        val parent = if (source.kind == FileSourceKind.Pan123) resolve(source, credentials, fullPath).id else "0"
        return authorized(source, credentials) { token ->
            directory(source, credentials, token, fullPath, parent, maxEntries)
        }
    }

    override suspend fun resolvePlayback(
        source: FileSource,
        credentials: FileSourceCredentials,
        path: List<String>,
    ): FileSourcePlaybackTarget {
        val fullPath = checkedPath(source.rootSegments + path)
        // Re-list the parent at playback time: renamed/replaced files must not reuse stale ids.
        val entry = resolve(source, credentials, fullPath, refresh = true)
        if (entry.entry.directory) throw FileSourceException(FileSourceFailure.NotFound)
        return authorized(source, credentials) { token ->
            if (source.kind == FileSourceKind.Pan123) {
                val result = pan123(token, "/api/v1/file/download_info", mapOf("fileId" to entry.id))
                FileSourcePlaybackTarget(
                    mediaUrl(result["data"]?.jsonObject?.text("downloadUrl").orEmpty()).toString(),
                    objectIdentity = entry.identity,
                    expectedSizeBytes = entry.entry.sizeBytes,
                )
            } else {
                val result =
                    baidu(
                        token,
                        "multimedia",
                        mapOf(
                            "method" to "filemetas",
                            "dlink" to "1",
                            "fsids" to "[${entry.id}]",
                        ),
                    )
                val item = result["list"]?.jsonArray?.firstOrNull()?.jsonObject ?: invalid()
                val url = mediaUrl(item.text("dlink"))
                // A provider access token belongs only on its own download endpoint.
                if (url.host != "baidu.com" && !url.host.endsWith(".baidu.com")) invalid()
                FileSourcePlaybackTarget(
                    url
                        .newBuilder()
                        .setQueryParameter("access_token", token)
                        .build()
                        .toString(),
                    headers =
                        mapOf(
                            "User-Agent" to "pan.baidu.com",
                        ),
                    objectIdentity = entry.identity,
                    expectedSizeBytes = entry.entry.sizeBytes,
                )
            }
        }
    }

    override suspend fun cacheSubtitle(
        source: FileSource,
        credentials: FileSourceCredentials,
        path: List<String>,
        entry: FileSourceEntry,
    ): String {
        val extension = entry.name.substringAfterLast('.', "").lowercase()
        if (extension !in setOf("srt", "ass", "ssa", "vtt", "ttml", "sub")) invalid()
        val target = resolvePlayback(source, credentials, path)
        val key =
            cloudDigest(
                "${identity(
                    source,
                    credentials,
                )}|${path.joinToString("/")}|${target.objectIdentity}",
            ).take(16)
        withContext(Dispatchers.IO) { subtitles.cached(key, extension) }?.let { return it }
        val request =
            Request
                .Builder()
                .url(target.url)
                .apply {
                    target.headers.forEach { (name, value) ->
                        header(name, value)
                    }
                }.build()
        val bytes =
            media.newCall(request).withCloudResponse { response ->
                if (!response.isSuccessful) {
                    throw FileSourceException(
                        if (response.code in
                            listOf(401, 403)
                        ) {
                            FileSourceFailure.Unauthorized
                        } else {
                            FileSourceFailure.Server
                        },
                    )
                }
                withContext(Dispatchers.IO) { response.cloudReadBounded(8 * 1024 * 1024) }
            }
        return withContext(Dispatchers.IO) { subtitles.store(key, extension, utf8SubtitleBytes(bytes, entry.name)) }
    }

    private suspend fun resolve(
        source: FileSource,
        credentials: FileSourceCredentials,
        path: List<String>,
        refresh: Boolean = false,
    ): CloudEntry {
        if (path.isEmpty()) return CloudEntry("0", FileSourceEntry("", true))
        val key = pathKey(source, credentials, path)
        if (refresh) synchronized(resolved) { resolved.remove(key) }
        if (!refresh) synchronized(resolved) { resolved[key] }?.let { return it }
        val parentPath = path.dropLast(1)
        val parentId = if (source.kind == FileSourceKind.Pan123) resolve(source, credentials, parentPath).id else "0"
        val found =
            authorized(source, credentials) { token ->
                directory(
                    source,
                    credentials,
                    token,
                    parentPath,
                    parentId,
                    MAX_FILE_SOURCE_LISTING_ENTRIES,
                    path.last(),
                )
            }
        synchronized(resolved) { resolved[key] }?.let { return it }
        throw FileSourceException(if (found.truncated) FileSourceFailure.TooLarge else FileSourceFailure.NotFound)
    }

    private suspend fun directory(
        source: FileSource,
        credentials: FileSourceCredentials,
        token: String,
        path: List<String>,
        parent: String,
        maximum: Int,
        findName: String? = null,
    ): FileSourceListing {
        val output = mutableListOf<FileSourceEntry>()
        val sourceIdentity = identity(source, credentials)
        var cursor = "0"
        var inspected = 0
        val seen = mutableSetOf<String>()
        while (inspected < maximum) {
            val limit = minOf(100, maximum - inspected)
            val is123 = source.kind == FileSourceKind.Pan123
            val result =
                if (is123) {
                    pan123(
                        token,
                        "/api/v2/file/list",
                        mapOf(
                            "parentFileId" to parent,
                            "limit" to limit.toString(),
                            "lastFileId" to cursor,
                        ),
                    )
                } else {
                    baidu(
                        token,
                        "file",
                        mapOf(
                            "method" to "list",
                            "dir" to "/${path.joinToString("/")}",
                            "order" to "name",
                            "start" to inspected.toString(),
                            "limit" to limit.toString(),
                        ),
                    )
                }
            val data = if (is123) result["data"]?.jsonObject ?: invalid() else result
            val files = data[if (is123) "fileList" else "list"]?.jsonArray ?: invalid()
            if (files.size > limit) invalid()
            for (raw in files) {
                inspected++
                val item = raw.jsonObject
                if (is123 && (item.number("trashed") != 0L || item.number("status") > 100L)) continue
                val name = item.text(if (is123) "filename" else "server_filename")
                if (name.isBlank() ||
                    name in listOf(".", "..") ||
                    name.contains('/') ||
                    name.contains('\u0000')
                ) {
                    continue
                }
                val id = item.text(if (is123) "fileId" else "fs_id")
                if (id.toLongOrNull() == null) invalid()
                val entry =
                    FileSourceEntry(
                        name,
                        item.number(if (is123) "type" else "isdir") == 1L,
                        item.number("size").coerceAtLeast(0),
                        if (is123) {
                            parseTime(
                                item.text("updateAt"),
                            )
                        } else {
                            item.number("server_mtime").takeIf { it > 0 }?.times(1000)
                        },
                    )
                val identity =
                    cloudDigest(
                        "$id|${item.text(if (is123) "etag" else "md5")}|${entry.modifiedEpochMs}|${entry.sizeBytes}",
                    )
                synchronized(resolved) {
                    resolved["$sourceIdentity|${(path + name).joinToString("/")}"] =
                        CloudEntry(id, entry, identity)
                }
                output += entry
                if (name == findName) return FileSourceListing(output, true)
            }
            if (is123) {
                val next = data.text("lastFileId")
                if (next == "-1") return FileSourceListing(output)
                if (files.isEmpty() || next.isBlank() || next == cursor || !seen.add(next)) invalid()
                cursor = next
            } else if (files.size < limit) {
                return FileSourceListing(output)
            }
        }
        return FileSourceListing(output, true)
    }

    private suspend fun <T> authorized(
        source: FileSource,
        credentials: FileSourceCredentials,
        block: suspend (String) -> T,
    ): T {
        val first = token(source, credentials)
        try {
            return block(first)
        } catch (error: FileSourceException) {
            if (error.failure != FileSourceFailure.Unauthorized) throw error
        }
        return block(token(source, credentials, rejected = first))
    }

    private suspend fun token(
        source: FileSource,
        credentials: FileSourceCredentials,
        rejected: String? = null,
    ): String =
        tokenMutex.withLock {
            val key = identity(source, credentials)
            val supplied =
                try {
                    CloudDriveCredentials.decode(credentials.password)
                } catch (
                    _: Exception,
                ) {
                    throw FileSourceException(FileSourceFailure.Unauthorized)
                }
            val current = withContext(Dispatchers.IO) { tokens.read(key) } ?: supplied
            if (current.accessToken.isNotBlank() &&
                current.accessToken != rejected &&
                (current.expiresAtEpochMs == 0L || current.expiresAtEpochMs > now() + 60_000)
            ) {
                return@withLock current.accessToken
            }
            if (current.clientId.isBlank() ||
                current.clientSecret.isBlank()
            ) {
                throw FileSourceException(FileSourceFailure.Unauthorized)
            }
            val refreshed =
                if (source.kind == FileSourceKind.Pan123) {
                    val payload =
                        buildJsonObject {
                            put("clientID", current.clientId)
                            put("clientSecret", current.clientSecret)
                        }
                    val result =
                        api.cloudJson(
                            Request
                                .Builder()
                                .url("$pan123Origin/api/v1/access_token")
                                .header("Platform", "open_platform")
                                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                                .build(),
                        )
                    check123(result)
                    val data = result["data"]?.jsonObject ?: invalid()
                    current.copy(
                        accessToken = data.text("accessToken"),
                        expiresAtEpochMs =
                            parseTime(data.text("expiredAt")) ?: now() + 3_600_000,
                    )
                } else {
                    if (current.refreshToken.isBlank()) throw FileSourceException(FileSourceFailure.Unauthorized)
                    val url =
                        "$oauthOrigin/oauth/2.0/token"
                            .toHttpUrl()
                            .newBuilder()
                            .addQueryParameter("grant_type", "refresh_token")
                            .addQueryParameter(
                                "refresh_token",
                                current.refreshToken,
                            ).addQueryParameter(
                                "client_id",
                                current.clientId,
                            ).addQueryParameter("client_secret", current.clientSecret)
                            .build()
                    val result = api.cloudJson(Request.Builder().url(url).build(), allowOAuthError = true)
                    current.copy(
                        accessToken = result.text("access_token"),
                        refreshToken = result.text("refresh_token").ifBlank { current.refreshToken },
                        expiresAtEpochMs = now() + result.number("expires_in").coerceIn(1, 31_536_000) * 1000,
                    )
                }
            if (refreshed.accessToken.isBlank()) throw FileSourceException(FileSourceFailure.Unauthorized)
            withContext(Dispatchers.IO) { tokens.write(key, refreshed) }
            refreshed.accessToken
        }

    private suspend fun pan123(
        token: String,
        path: String,
        params: Map<String, String>,
    ): JsonObject {
        val url =
            (pan123Origin + path)
                .toHttpUrl()
                .newBuilder()
                .apply {
                    params.forEach { (k, v) ->
                        addQueryParameter(k, v)
                    }
                }.build()
        return api
            .cloudJson(
                Request
                    .Builder()
                    .url(
                        url,
                    ).header("Platform", "open_platform")
                    .header("Authorization", "Bearer $token")
                    .build(),
            ).also(::check123)
    }

    private fun check123(result: JsonObject) {
        if (!result.containsKey("code")) invalid()
        when (result.number("code")) {
            0L -> Unit
            401L -> throw FileSourceException(FileSourceFailure.Unauthorized)
            403L -> throw FileSourceException(FileSourceFailure.Forbidden)
            404L, 5066L -> throw FileSourceException(FileSourceFailure.NotFound)
            5113L -> throw FileSourceException(FileSourceFailure.Forbidden)
            else -> throw FileSourceException(FileSourceFailure.Server)
        }
    }

    private suspend fun baidu(
        token: String,
        resource: String,
        params: Map<String, String>,
    ): JsonObject {
        val url =
            "$baiduOrigin/rest/2.0/xpan/$resource"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("access_token", token)
                .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
                .build()
        val result = api.cloudJson(Request.Builder().url(url).build())
        if (!result.containsKey("errno")) invalid()
        when (result.number("errno")) {
            0L -> return result
            -6L, 110L, 111L -> throw FileSourceException(FileSourceFailure.Unauthorized)
            -9L -> throw FileSourceException(FileSourceFailure.NotFound)
            else -> throw FileSourceException(FileSourceFailure.Server)
        }
    }

    private fun identity(
        source: FileSource,
        credentials: FileSourceCredentials,
    ) = "${cloudDigest(source.id)}:${cloudDigest("${source.kind}|${credentials.password}")}"

    private fun pathKey(
        source: FileSource,
        credentials: FileSourceCredentials,
        path: List<String>,
    ) = "${identity(source, credentials)}|${path.joinToString("/")}"

    private fun checkedPath(path: List<String>): List<String> {
        if (path.size > 64 ||
            path.any { it.isBlank() || it in listOf(".", "..") || '/' in it || '\u0000' in it }
        ) {
            invalid()
        }
        return path
    }

    private fun mediaUrl(value: String): HttpUrl =
        try {
            value.toHttpUrl()
        } catch (_: IllegalArgumentException) {
            invalid()
        }

    private fun parseTime(value: String): Long? =
        runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private fun invalid(): Nothing = throw FileSourceException(FileSourceFailure.InvalidResponse)

    private data class CloudEntry(
        val id: String,
        val entry: FileSourceEntry,
        val identity: String? = null,
    )

    private companion object {
        // Several browsers/scans can create clients; rotating one-use refresh tokens is process-wide.
        val tokenMutex = Mutex()
    }
}
