package com.yfuse.core2.android

import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFeature
import kotlinx.coroutines.CancellationException

/** Prefers Cronet HTTP/2/HTTP/3 and falls back to the pinned OkHttp transport when unavailable. */
internal class AndroidAdaptiveHttpMediaTransport(
    private val createCronet: () -> YMediaTransport,
    private val createOkHttp: () -> YMediaTransport = ::AndroidHttpMediaTransport,
) : YMediaTransport {
    override val supportedProtocols: Set<YSourceProtocol> =
        setOf(YSourceProtocol.Http, YSourceProtocol.Https, YSourceProtocol.WebDav, YSourceProtocol.WebDavTls)
    override val features: Set<YTransportFeature> =
        setOf(
            YTransportFeature.ByteRange,
            YTransportFeature.Http2,
            YTransportFeature.Http3,
            YTransportFeature.ConnectionReuse,
            YTransportFeature.RandomAccess,
        )

    private var active: YMediaTransport? = null
    private var preferred: YMediaTransport? = null
    private var activeRequest: YMediaTransportRequest? = null
    private var bytesReadForRequest = 0L
    private var cronetUnavailable = false

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
        require(request.protocol in supportedProtocols)
        active?.close()
        active = null
        activeRequest = request
        bytesReadForRequest = 0L
        if (!cronetUnavailable) {
            val cronet = preferred ?: runCatching(createCronet).getOrNull()
            if (cronet != null) {
                preferred = cronet
                try {
                    return cronet.open(request).also { active = cronet }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    runCatching { cronet.close() }
                    preferred = null
                    cronetUnavailable = true
                }
            } else {
                cronetUnavailable = true
            }
        }
        return openFallback(request)
    }

    override suspend fun read(
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val transport = checkNotNull(active) { "HTTP transport is not open" }
        return try {
            transport.read(destination, offset, length).also(::recordRead)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (transport !== preferred || cronetUnavailable) throw failure
            recoverReadWithFallback(
                failed = transport,
                destination = destination,
                offset = offset,
                length = length,
                preferredFailure = failure,
            )
        }
    }

    override suspend fun close() {
        active?.close()
        active = null
        activeRequest = null
        bytesReadForRequest = 0L
    }

    private suspend fun openFallback(request: YMediaTransportRequest): YMediaTransportResponse {
        val transport = createOkHttp()
        return try {
            transport.open(request).also { active = transport }
        } catch (failure: Throwable) {
            runCatching { transport.close() }
            throw failure
        }
    }

    private suspend fun recoverReadWithFallback(
        failed: YMediaTransport,
        destination: ByteArray,
        offset: Int,
        length: Int,
        preferredFailure: Throwable,
    ): Int {
        val request = checkNotNull(activeRequest) { "HTTP transport request is unavailable" }
        val remainingRequest = request.remainingAfter(bytesReadForRequest) ?: throw preferredFailure
        runCatching { failed.close() }
        if (preferred === failed) preferred = null
        active = null
        cronetUnavailable = true

        val fallback = createOkHttp()
        try {
            val response = fallback.open(remainingRequest)
            response.requireMatchingRange(remainingRequest)
            active = fallback
            activeRequest = remainingRequest
            bytesReadForRequest = 0L
            return fallback.read(destination, offset, length).also(::recordRead)
        } catch (fallbackFailure: Throwable) {
            runCatching { fallback.close() }
            fallbackFailure.addSuppressed(preferredFailure)
            throw fallbackFailure
        }
    }

    private fun recordRead(count: Int) {
        if (count <= 0) return
        bytesReadForRequest =
            if (bytesReadForRequest > Long.MAX_VALUE - count.toLong()) {
                Long.MAX_VALUE
            } else {
                bytesReadForRequest + count
            }
    }
}

private fun YMediaTransportRequest.remainingAfter(bytesRead: Long): YMediaTransportRequest? {
    if (bytesRead <= 0L) return this
    val originalRange = range ?: return null
    if (bytesRead > Long.MAX_VALUE - originalRange.startInclusive) return null
    val nextStart = originalRange.startInclusive + bytesRead
    if (originalRange.endInclusive != null && nextStart > originalRange.endInclusive) return null
    return copy(range = YByteRange(nextStart, originalRange.endInclusive))
}

private fun YMediaTransportResponse.requireMatchingRange(request: YMediaTransportRequest) {
    val expected = request.range ?: return
    require(statusCode == 206) { "Fallback transport did not accept byte range" }
    require(acceptedRange?.startInclusive == expected.startInclusive) {
        "Fallback transport returned mismatched range metadata"
    }
}
