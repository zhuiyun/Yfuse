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
    private var activeExpectedBytes: Long? = null
    private var activeBytesRead = 0L
    private var activeIsCronet = false
    private var cronetUnavailable = false

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
        require(request.protocol in supportedProtocols)
        closeActive(propagateCancellation = true)
        if (!cronetUnavailable) {
            val cronet = preferred ?: runCatching(createCronet).getOrNull()
            if (cronet != null) {
                preferred = cronet
                try {
                    val response = cronet.open(request)
                    response.requireAcceptedRange(request)
                    bind(
                        transport = cronet,
                        request = request,
                        response = response,
                        cronet = true,
                    )
                    return response
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    closeTransport(cronet, propagateCancellation = false)
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
        require(offset >= 0 && length >= 0 && offset <= destination.size - length)
        if (length == 0) return 0
        val transport = checkNotNull(active) { "HTTP transport is not open" }
        return try {
            val count = transport.read(destination, offset, length)
            if (count > 0) activeBytesRead += count
            if (
                count < 0 &&
                activeIsCronet &&
                activeExpectedBytes?.let { expected -> activeBytesRead < expected } == true
            ) {
                resumeWithOkHttp(
                    destination = destination,
                    offset = offset,
                    length = length,
                    cronetFailure = IllegalStateException("Cronet ended before the requested byte range"),
                )
            } else {
                count
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (!activeIsCronet) throw failure
            resumeWithOkHttp(destination, offset, length, failure)
        }
    }

    override suspend fun close() {
        closeActive(propagateCancellation = true)
    }

    private suspend fun resumeWithOkHttp(
        destination: ByteArray,
        offset: Int,
        length: Int,
        cronetFailure: Throwable,
    ): Int {
        val request = checkNotNull(activeRequest) { "Cronet request metadata is unavailable" }
        val resumedRequest = request.resumeAfter(activeBytesRead) ?: return -1
        cronetUnavailable = true
        preferred = null
        closeActive(propagateCancellation = true)
        try {
            openOkHttp(resumedRequest)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fallbackFailure: Throwable) {
            fallbackFailure.addSuppressed(cronetFailure)
            throw fallbackFailure
        }
        return checkNotNull(active) { "Fallback transport is not open" }
            .read(destination, offset, length)
            .also { count -> if (count > 0) activeBytesRead += count }
    }

    private suspend fun openOkHttp(request: YMediaTransportRequest): YMediaTransportResponse {
        val transport = createOkHttp()
        return try {
            val response = transport.open(request)
            response.requireAcceptedRange(request)
            bind(
                transport = transport,
                request = request,
                response = response,
                cronet = false,
            )
            response
        } catch (cancelled: CancellationException) {
            closeTransport(transport, propagateCancellation = false)
            throw cancelled
        } catch (failure: Throwable) {
            closeTransport(transport, propagateCancellation = false)
            throw failure
        }
    }

    private fun bind(
        transport: YMediaTransport,
        request: YMediaTransportRequest,
        response: YMediaTransportResponse,
        cronet: Boolean,
    ) {
        active = transport
        activeRequest = request
        activeExpectedBytes = response.expectedBodyBytes(request)
        activeBytesRead = 0L
        activeIsCronet = cronet
    }

    private suspend fun closeActive(propagateCancellation: Boolean) {
        val transport = active
        clearActive()
        transport?.let { closeTransport(it, propagateCancellation) }
    }

    private fun clearActive() {
        active = null
        activeRequest = null
        activeExpectedBytes = null
        activeBytesRead = 0L
        activeIsCronet = false
    }

    private suspend fun closeTransport(
        transport: YMediaTransport,
        propagateCancellation: Boolean,
    ) {
        try {
            transport.close()
        } catch (cancelled: CancellationException) {
            if (propagateCancellation) throw cancelled
        } catch (_: Throwable) {
            // A failed transport is already being abandoned; its close error must not prevent the
            // bounded OkHttp recovery path from opening the exact remaining byte range.
        }
    }
}

private fun YMediaTransportResponse.requireAcceptedRange(request: YMediaTransportRequest) {
    val requestedRange = request.range ?: return
    require(statusCode == 206) { "Random-access transport did not accept byte range" }
    require(acceptedRange?.startInclusive == requestedRange.startInclusive) {
        "Random-access transport returned mismatched range metadata"
    }
}

private fun YMediaTransportRequest.resumeAfter(bytesRead: Long): YMediaTransportRequest? {
    if (bytesRead <= 0L) return this
    val base = range?.startInclusive ?: 0L
    if (bytesRead > Long.MAX_VALUE - base) return null
    val start = base + bytesRead
    val end = range?.endInclusive
    if (end != null && start > end) return null
    return copy(range = YByteRange(start, end))
}

private fun YMediaTransportResponse.expectedBodyBytes(request: YMediaTransportRequest): Long? {
    val servedRange = acceptedRange
    if (servedRange?.endInclusive != null) {
        return servedRange.endInclusive - servedRange.startInclusive + 1L
    }
    val requestedRange = request.range
    return requestedRange
        ?.endInclusive
        ?.let { end -> end - requestedRange.startInclusive + 1L }
        ?.takeIf { statusCode == 206 }
}
