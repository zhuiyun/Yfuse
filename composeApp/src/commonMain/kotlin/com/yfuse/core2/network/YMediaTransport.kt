package com.yfuse.core2.network

enum class YSourceProtocol {
    Local,
    Http,
    Https,
    WebDav,
    WebDavTls,
    Smb,
    Nfs,
}

enum class YTransportFeature {
    ByteRange,
    Http2,
    Http3,
    ConnectionReuse,
    RandomAccess,
}

data class YByteRange(
    val startInclusive: Long,
    val endInclusive: Long? = null,
) {
    init {
        require(startInclusive >= 0L)
        require(endInclusive == null || endInclusive >= startInclusive)
    }
}

data class YMediaTransportRequest(
    val uri: String,
    val protocol: YSourceProtocol,
    val range: YByteRange? = null,
    val headers: Map<String, String> = emptyMap(),
    val credentials: YTransportCredentials? = null,
) {
    init {
        require(uri.isNotBlank())
    }

    /** Safe for diagnostics: URI query and all authorization headers are intentionally omitted. */
    fun diagnosticSummary(): String =
        buildString {
            append(protocol)
            append(' ')
            append(uri.redactedTransportUri())
            range?.let {
                append(" bytes=")
                append(it.startInclusive)
                append('-')
                it.endInclusive?.let(::append)
            }
        }

    override fun toString(): String = "YMediaTransportRequest(${diagnosticSummary()})"
}

sealed interface YTransportCredentials {
    /** Kept out of diagnostics/toString; providers may still avoid storing it after open. */
    class UsernamePassword(
        val username: String,
        val password: String,
        val domain: String = "",
    ) : YTransportCredentials {
        init {
            require(username.isNotBlank())
        }

        override fun toString(): String = "UsernamePassword([redacted])"
    }
}

private fun String.redactedTransportUri(): String =
    substringBefore('?')
        .substringBefore('#')
        .replace(Regex("(?i)([a-z][a-z0-9+.-]*://)[^/@]+@"), "$1")

data class YMediaTransportResponse(
    val statusCode: Int,
    val contentLength: Long? = null,
    val acceptedRange: YByteRange? = null,
    val features: Set<YTransportFeature> = emptySet(),
)

interface YMediaTransport {
    val supportedProtocols: Set<YSourceProtocol>
    val features: Set<YTransportFeature>

    suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse

    suspend fun read(
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    suspend fun close()
}

data class YCacheIdentity(
    /** Account-scoped opaque namespace; never an access token or server URL. */
    val scope: String,
    /** Stable provider media id; never a signed playback URI. */
    val mediaId: String,
    val version: String = "",
) {
    init {
        require(scope.isNotBlank())
        require(mediaId.isNotBlank())
        require('\t' !in scope && '\t' !in mediaId && '\t' !in version)
    }

    fun key(): String = listOf(scope, mediaId, version).joinToString("\t")
}

data class YCacheConditions(
    val remote: Boolean,
    val live: Boolean,
    val seekable: Boolean,
    val mediaBitRateBitsPerSecond: Long = 0L,
    val availableBytes: Long,
) {
    init {
        require(mediaBitRateBitsPerSecond >= 0L)
        require(availableBytes >= 0L)
    }
}

data class YCachePlan(
    val enabled: Boolean,
    val maximumBytes: Long,
    val readAheadBytes: Long,
)

/** Protocol-neutral bounded cache policy used by HTTP range, WebDAV, SMB and NFS transports. */
object YCachePlanner {
    fun plan(conditions: YCacheConditions): YCachePlan {
        if (!conditions.remote || conditions.live || !conditions.seekable || conditions.availableBytes <= 0L) {
            return YCachePlan(enabled = false, maximumBytes = 0L, readAheadBytes = 0L)
        }
        val maximum = minOf(conditions.availableBytes, MAX_CACHE_BYTES)
        val oneSecond =
            if (conditions.mediaBitRateBitsPerSecond > 0L) {
                (conditions.mediaBitRateBitsPerSecond / 8L).coerceAtLeast(MIN_READ_AHEAD_BYTES)
            } else {
                DEFAULT_READ_AHEAD_BYTES
            }
        return YCachePlan(
            enabled = true,
            maximumBytes = maximum,
            readAheadBytes = minOf(oneSecond, maximum, MAX_READ_AHEAD_BYTES),
        )
    }
}

private const val MIN_READ_AHEAD_BYTES = 256L * 1024L
private const val DEFAULT_READ_AHEAD_BYTES = 2L * 1024L * 1024L
private const val MAX_READ_AHEAD_BYTES = 16L * 1024L * 1024L
private const val MAX_CACHE_BYTES = 4L * 1024L * 1024L * 1024L
