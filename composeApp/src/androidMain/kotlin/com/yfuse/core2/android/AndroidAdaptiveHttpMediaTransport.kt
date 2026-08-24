package com.yfuse.core2.android

import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFeature
import kotlinx.coroutines.CancellationException

/** Prefers Cronet HTTP/2/HTTP/3 and resumes failed byte-range reads with pinned OkHttp. */
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
    private var cronetUnavailable = false
    private var activeRequest: YMediaTransportRequest? = null
    private var activeBytesRead = 0L
    private var activeUsesCronet = false

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
        require(request.protocol in supportedProtocols)
        active?.close()
        resetActiveRequest()
        if (!cronetUnavailable) {
            val cronet = preferred ?: runCatching(createCronet).getOrNull()
            if (cronet != null) {
                preferred = cronet
                try {
                    return cronet.open(request).also {
                        active = cronet
                        activeRequest = request
                        activeUsesCronet = true
                    }
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
        return openOkHttp(request)
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
        } catch (cronetFailure: Throwable) {
            if (!activeUsesCronet) throw cronetFailure
            resumeWithOkHttp(
                destination = destination,
                offset = offset,
                length = length,
                cronetFailure = cronetFailure,
            )
        }
    }

    override suspend fun close() {
        active?.close()
        resetActiveRequest()
    }

    private suspend fun openOkHttp(request: YMediaTransportRequest): YMediaTransportResponse {
        val transport = createOkHttp()
        return try {
            transport.open(request).also {
                active = transport
                activeRequest = request
                activeBytesRead = 0L
                activeUsesCronet = false
            }
        } catch (cancelled: CancellationException) {
            runCatching { transport.close() }
            throw cancelled
        } catch (error: Throwable) {
            runCatching { transport.close() }
            throw error
        }
    }

    private suspend fun resumeWithOkHttp(
        destination: ByteArray,
        offset: Int,
        length: Int,
        cronetFailure: Throwable,
    ): Int {
        val request = checkNotNull(activeRequest) { "Cronet request metadata is unavailable" }
        if (request.range == null && activeBytesRead > 0L) throw cronetFailure
        val resumedRequest = request.resumeAfter(activeBytesRead) ?: return -1

        runCatching { active?.close() }
        active = null
        activeUsesCronet = false
        preferred = null
        cronetUnavailable = true

        val fallback = createOkHttp()
        return try {
            val response = fallback.open(resumedRequest)
            response.requireMatchingRange(resumedRequest)
            active = fallback
            activeRequest = resumedRequest
            activeBytesRead = 0L
            fallback.read(destination, offset, length).also(::recordRead)
        } catch (cancelled: CancellationException) {
            runCatching { fallback.close() }
            throw cancelled
        } catch (fallbackFailure: Throwable) {
            runCatching { fallback.close() }
            fallbackFailure.addSuppressed(cronetFailure)
            throw fallbackFailure
        }
    }

    private fun recordRead(count: Int) {
        if (count > 0) activeBytesRead += count.toLong()
    }

    private fun resetActiveRequest() {
        active = null
        activeRequest = null
        activeBytesRead = 0L
        activeUsesCronet = false
    }
}

private fun YMediaTransportRequest.resumeAfter(bytesRead: Long): YMediaTransportRequest? {
    require(bytesRead >= 0L)
    if (bytesRead == 0L) return this
    val requestedRange = range ?: return null
    val resumedStart = requestedRange.startInclusive.saturatedAdd(bytesRead)
    val end = requestedRange.endInclusive
    if (end != null && resumedStart > end) return null
    return copy(range = YByteRange(resumedStart, end))
}

private fun YMediaTransportResponse.requireMatchingRange(request: YMediaTransportRequest) {
    val requestedRange = request.range ?: return
    require(statusCode == 206) { "Fallback transport did not accept byte range" }
    require(acceptedRange?.startInclusive == requestedRange.startInclusive) {
        "Fallback transport returned mismatched range metadata"
    }
}

private fun Long.saturatedAdd(other: Long): Long =
    if (other > 0L && this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other
