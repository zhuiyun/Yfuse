package com.yfuse.core2.android

import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFailureKind
import com.yfuse.core2.network.YTransportFeature
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Prefers Cronet HTTP/2/HTTP/3 and falls back to the pinned OkHttp transport when unavailable. */
internal class AndroidAdaptiveHttpMediaTransport(
    private val routeState: AndroidAdaptiveHttpRouteState = AndroidAdaptiveHttpRouteState(),
    private val createCronet: () -> YMediaTransport,
    private val createOkHttp: () -> YMediaTransport = {
        AndroidHttpMediaTransport(
            followSafeRedirects = true,
            allowCrossProtocolRedirects = true,
            redirectState = routeState.redirectState,
        )
    },
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

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
        require(request.protocol in supportedProtocols)
        closeActive(propagateCancellation = true)
        if (routeState.cronetAvailable) {
            val cronet = preferred ?: runCatching(createCronet).getOrNull()
            if (cronet != null) {
                preferred = cronet
                try {
                    val response = cronet.open(request)
                    response.requireAcceptedRange(
                        request = request,
                        previouslyAcceptedRange = routeState.hasAcceptedRange,
                    )
                    routeState.recordAcceptedRange(request, response)
                    if (response.negotiatedProtocol.isLegacyHttp()) {
                        // A non-multiplexed Cronet route provides no HTTP/2 or HTTP/3 benefit and
                        // has repeatedly stalled on parallel CDN range reads. Keep this validated
                        // request, but send every later range through the shared OkHttp pool.
                        routeState.disableCronet()
                        preferred = null
                    }
                    bind(
                        transport = cronet,
                        request = request,
                        response = response,
                        cronet = true,
                    )
                    return response
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    routeState.rejectStaleAuthorizationRoute(request, failure)
                    closeTransport(cronet, propagateCancellation = false)
                    preferred = null
                    routeState.disableCronet()
                }
            } else {
                routeState.disableCronet()
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
        routeState.disableCronet()
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
            response.requireAcceptedRange(
                request = request,
                previouslyAcceptedRange = routeState.hasAcceptedRange,
            )
            routeState.recordAcceptedRange(request, response)
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
            routeState.rejectStaleAuthorizationRoute(request, failure)
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

/**
 * Per-media HTTP route memory shared by the primary reader and every prefetch transport.
 *
 * A provider redirect that Cronet cannot safely follow must disable that probe for the whole
 * source, not only for one 2 MiB block. The same state also remembers OkHttp's credential-safe
 * final media URL so later byte ranges do not repeat a slow origin/redirect chain.
 */
internal class AndroidAdaptiveHttpRouteState(
    val redirectState: AndroidHttpMediaRedirectState = AndroidHttpMediaRedirectState(),
) {
    private val cronetDisabled = AtomicBoolean(false)
    private val acceptedRange = AtomicBoolean(false)

    val cronetAvailable: Boolean
        get() = !cronetDisabled.get()

    val hasAcceptedRange: Boolean
        get() = acceptedRange.get()

    fun disableCronet() {
        cronetDisabled.set(true)
    }

    fun recordAcceptedRange(
        request: YMediaTransportRequest,
        response: YMediaTransportResponse,
    ) {
        if (request.range != null && response.statusCode == 206) acceptedRange.set(true)
    }

    fun rejectStaleAuthorizationRoute(
        request: YMediaTransportRequest,
        failure: Throwable,
    ) {
        if (
            request.range != null &&
            hasAcceptedRange &&
            failure is AndroidRangeResponseException &&
            failure.statusCode == 403
        ) {
            redirectState.disableReuse(request.uri)
        }
    }
}

private fun YMediaTransportResponse.requireAcceptedRange(
    request: YMediaTransportRequest,
    previouslyAcceptedRange: Boolean,
) {
    val requestedRange = request.range ?: return
    if (statusCode != 206) {
        throw AndroidRangeResponseException(
            failureKind = statusCode.toAdaptiveRangeFailureKind(previouslyAcceptedRange),
            statusCode = statusCode,
            expectedRangeStart = requestedRange.startInclusive,
            acceptedRangeStart = acceptedRange?.startInclusive,
            safeMessage = "Random-access transport did not accept byte range",
        )
    }
    if (acceptedRange?.startInclusive != requestedRange.startInclusive) {
        throw AndroidRangeResponseException(
            failureKind = YTransportFailureKind.InvalidRange,
            statusCode = statusCode,
            expectedRangeStart = requestedRange.startInclusive,
            acceptedRangeStart = acceptedRange?.startInclusive,
            safeMessage = "Random-access transport returned mismatched range metadata",
        )
    }
}

internal class AndroidRangeResponseException(
    val failureKind: YTransportFailureKind,
    val statusCode: Int,
    val expectedRangeStart: Long,
    val acceptedRangeStart: Long?,
    safeMessage: String,
) : IOException(safeMessage)

private fun Int.toAdaptiveRangeFailureKind(previouslyAcceptedRange: Boolean): YTransportFailureKind =
    when (this) {
        401 -> YTransportFailureKind.Authorization
        // Some media providers issue short-lived redirect targets that start returning 403 while
        // the authenticated origin remains valid. Once this source has already served a validated
        // range, treat only that later 403 as a bounded transport refresh instead of a bad login.
        403 ->
            if (previouslyAcceptedRange) {
                YTransportFailureKind.TransientIo
            } else {
                YTransportFailureKind.Authorization
            }
        408, 425, 429 -> YTransportFailureKind.ServerBusy
        in 500..599 -> YTransportFailureKind.ServerBusy
        else -> YTransportFailureKind.InvalidRange
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

private fun String.isLegacyHttp(): Boolean =
    trim().lowercase() == "http/1.0" || trim().lowercase() == "http/1.1"
