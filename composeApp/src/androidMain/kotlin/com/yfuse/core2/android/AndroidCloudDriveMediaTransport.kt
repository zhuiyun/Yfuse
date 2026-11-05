package com.yfuse.core2.android

import com.yfuse.core.filesource.FileSourceClient
import com.yfuse.core.filesource.FileSourcePlaybackTarget
import com.yfuse.core.filesource.FileSourceRegistry
import com.yfuse.core.filesource.decodePathSegment
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFeature
import com.yfuse.core2.network.YTransportMethod
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.URI
import java.security.MessageDigest

/** Re-resolves provider links at range-open time, with a short cache and one expired-link retry. */
internal class AndroidCloudDriveMediaTransport(
    private val registry: FileSourceRegistry =
        org.koin.core.context.GlobalContext
            .get()
            .get(),
    private val client: FileSourceClient =
        org.koin.core.context.GlobalContext
            .get()
            .get(),
    private val http: YMediaTransport =
        AndroidHttpMediaTransport(followSafeRedirects = true, allowCrossProtocolRedirects = true),
) : YMediaTransport {
    override val supportedProtocols = setOf(YSourceProtocol.CloudDrive)
    override val features =
        setOf(YTransportFeature.ByteRange, YTransportFeature.RandomAccess, YTransportFeature.ConnectionReuse)
    private var openedUri: String? = null
    private var representation: String? = null
    private var representationSize: Long? = null
    private var representationHttpTag: String? = null

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
        require(request.protocol == YSourceProtocol.CloudDrive && request.method == YTransportMethod.Get)
        val uri = URI(request.uri)
        require(uri.scheme == "yfuse-cloud" && uri.userInfo == null && uri.query == null && uri.fragment == null)
        val segments =
            uri.rawPath
                .split('/')
                .filter(String::isNotEmpty)
                .map(::decodePathSegment)
        val revision = segments.firstOrNull()?.toLongOrNull() ?: throw IOException("Invalid cloud source revision")
        val path = segments.drop(1)
        require(
            path.isNotEmpty() &&
                path.all { it !in setOf(".", "..") && it.none { c -> c == '/' || c == '\\' || c.isISOControl() } },
        )
        val source = registry.source(uri.host) ?: throw IOException("Cloud source was removed")
        if (!source.isCloud ||
            source.revision != revision
        ) {
            throw IOException("Cloud source changed; reopen from the library")
        }
        val credentials = registry.credentials(source.id) ?: throw IOException("Cloud authorization unavailable")
        val cacheKey =
            digest(
                source.id + "|" + revision + "|" + path.joinToString("/") + "|" + credentials.username + "|" +
                    credentials.password,
            )
        if (openedUri !=
            request.uri
        ) {
            representation = null
            representationSize = null
            representationHttpTag = null
            openedUri =
                request.uri
        }
        repeat(2) { attempt ->
            val target =
                CloudPlaybackLinks.get(
                    cacheKey,
                    force = attempt > 0,
                ) { client.resolvePlayback(source, credentials, path) }
            if (registry.source(source.id) != source) throw IOException("Cloud source changed during open")
            val identity = target.objectIdentity ?: throw IOException("Cloud provider returned no object identity")
            val expected = target.expectedSizeBytes
            if (representation != null &&
                (
                    representation != identity ||
                        (expected != null && representationSize != null && representationSize != expected)
                )
            ) {
                throw IOException("Cloud object changed; reopen playback")
            }
            val protocol =
                when (URI(target.url).scheme?.lowercase()) {
                    "https" -> YSourceProtocol.Https
                    "http" -> YSourceProtocol.Http
                    else -> throw IOException("Unsupported cloud media URL")
                }
            // Our representation token is provider metadata, not the CDN's ETag. Never send it as an If-Range validator.
            val headers =
                request.headers.filterNot { (name, value) ->
                    name.equals("If-Range", true) &&
                        value.startsWith("\"yfuse-cloud-")
                } +
                    target.headers
            val response =
                http.open(
                    request.copy(
                        uri = target.url,
                        protocol = protocol,
                        headers = headers,
                        credentials = target.credentials,
                        originBoundHeaderNames = request.originBoundHeaderNames + target.headers.keys,
                    ),
                )
            if (response.statusCode in setOf(401, 403) && attempt == 0) {
                http.close()
                return@repeat
            }
            if (response.statusCode in 200..299) {
                if (expected != null &&
                    response.contentLength != null &&
                    expected != response.contentLength
                ) {
                    http.close()
                    throw IOException("Cloud object length changed")
                }
                if (representationHttpTag != null &&
                    response.entityTag != representationHttpTag
                ) {
                    http.close()
                    throw IOException("Cloud object ETag changed")
                }
                representation = identity
                representationSize = response.contentLength ?: expected
                representationHttpTag = response.entityTag
            }
            return response.copy(
                implementation = "cloud-link/" + response.implementation,
                entityTag = "\"yfuse-cloud-${digest(
                    identity + "|" + response.entityTag.orEmpty(),
                )}\"",
                contentLength = response.contentLength ?: expected,
            )
        }
        error("Unreachable")
    }

    override suspend fun read(
        destination: ByteArray,
        offset: Int,
        length: Int,
    ) = http.read(destination, offset, length)

    override suspend fun close() = http.close()
}

private object CloudPlaybackLinks {
    private data class Cached(
        val target: FileSourcePlaybackTarget,
        val expiresAt: Long,
    )

    private val mutex = Mutex()
    private val entries = linkedMapOf<String, Cached>()

    suspend fun get(
        key: String,
        force: Boolean,
        resolve: suspend () -> FileSourcePlaybackTarget,
    ): FileSourcePlaybackTarget =
        mutex.withLock {
            val now = System.nanoTime() / 1_000_000
            if (!force) entries[key]?.takeIf { it.expiresAt > now }?.let { return@withLock it.target }
            entries.remove(key)
            val result = resolve()
            entries[key] = Cached(result, System.nanoTime() / 1_000_000 + 30_000)
            while (entries.size > 64) entries.remove(entries.keys.first())
            result
        }
}

private fun digest(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
        "%02x".format(it)
    }
