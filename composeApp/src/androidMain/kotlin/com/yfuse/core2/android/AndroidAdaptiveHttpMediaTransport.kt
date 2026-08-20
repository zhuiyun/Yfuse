package com.yfuse.core2.android

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
    private var cronetUnavailable = false

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
        require(request.protocol in supportedProtocols)
        active?.close()
        active = null
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
        return createOkHttp().let { transport ->
            transport.open(request).also { active = transport }
        }
    }

    override suspend fun read(
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int = checkNotNull(active) { "HTTP transport is not open" }.read(destination, offset, length)

    override suspend fun close() {
        active?.close()
        active = null
    }
}
