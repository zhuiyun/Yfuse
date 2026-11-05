package com.yfuse.core2.android

import com.yfuse.core.filesource.FileSourceCredentials
import com.yfuse.core.filesource.FtpSession
import com.yfuse.core.filesource.decodePathSegment
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportCredentials
import com.yfuse.core2.network.YTransportFeature
import com.yfuse.core2.network.YTransportMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.Socket
import java.net.URI

/** FTP byte ranges via SIZE/REST/RETR, shared by extractor, disc reader and the native proxy. */
internal class AndroidFtpMediaTransport : YMediaTransport {
    override val supportedProtocols = setOf(YSourceProtocol.Ftp)
    override val features = setOf(YTransportFeature.ByteRange, YTransportFeature.RandomAccess)
    private var session: FtpSession? = null
    private var data: Socket? = null
    private var remaining = 0L

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse =
        runInterruptible(Dispatchers.IO) {
            require(request.protocol == YSourceProtocol.Ftp && request.method == YTransportMethod.Get)
            closeCurrent()
            val uri = URI(request.uri)
            require(uri.scheme.equals("ftp", true) && uri.userInfo == null)
            val login = request.credentials as? YTransportCredentials.UsernamePassword
            val opened =
                FtpSession(
                    URI("ftp", null, uri.host, uri.port, null, null, null).toString(),
                    FileSourceCredentials(login?.username.orEmpty(), login?.password.orEmpty()),
                )
            session = opened
            try {
                val path =
                    uri.rawPath
                        .split('/')
                        .filter(String::isNotEmpty)
                        .map(::decodePathSegment)
                val size = opened.size(path)
                val range = request.range?.boundedTo(size)
                val start = request.range?.startInclusive ?: 0L
                remaining =
                    if (request.range == null) size else range?.let { it.endInclusive!! - it.startInclusive + 1 } ?: 0L
                if (remaining > 0) data = opened.openRead(path, start)
                YMediaTransportResponse(
                    if (request.range ==
                        null
                    ) {
                        200
                    } else if (range == null) {
                        416
                    } else {
                        206
                    },
                    size,
                    range,
                    features,
                    implementation = "ftp-rest",
                    negotiatedProtocol = "FTP",
                    finalProtocol = YSourceProtocol.Ftp,
                )
            } catch (error: Throwable) {
                closeCurrent()
                throw error
            }
        }

    override suspend fun read(
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int =
        runInterruptible(Dispatchers.IO) {
            require(offset >= 0 && length >= 0 && offset <= destination.size - length)
            if (length == 0) return@runInterruptible 0
            if (remaining <= 0) return@runInterruptible -1
            val count =
                data?.getInputStream()?.read(destination, offset, minOf(length.toLong(), remaining).toInt()) ?: -1
            if (count > 0) remaining -= count
            count
        }

    override suspend fun close() = runInterruptible(Dispatchers.IO) { closeCurrent() }

    private fun closeCurrent() {
        runCatching { data?.close() }
        data = null
        runCatching { session?.close() }
        session = null
        remaining = 0
    }
}
