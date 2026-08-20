package com.yfuse.core2.android

import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportCredentials
import com.yfuse.core2.network.YTransportFeature
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties

/** SMB2/SMB3 random-access transport backed by jcifs-ng; SMB1 is explicitly disabled. */
internal class AndroidSmbMediaTransport : YMediaTransport {
    override val supportedProtocols: Set<YSourceProtocol> = setOf(YSourceProtocol.Smb)
    override val features: Set<YTransportFeature> =
        setOf(YTransportFeature.ByteRange, YTransportFeature.RandomAccess, YTransportFeature.ConnectionReuse)

    private var context: CIFSContext? = null
    private var file: SmbFile? = null
    private var randomAccess: SmbRandomAccessFile? = null

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse =
        withContext(Dispatchers.IO) {
            require(request.protocol == YSourceProtocol.Smb)
            require(request.uri.startsWith("smb://", ignoreCase = true))
            closeCurrent()
            val base = BaseContext(PropertyConfiguration(smbProperties()))
            val credentials = request.credentials as? YTransportCredentials.UsernamePassword
            val activeContext =
                if (credentials != null) {
                    base.withCredentials(
                        NtlmPasswordAuthenticator(
                            credentials.domain,
                            credentials.username,
                            credentials.password,
                        ),
                    )
                } else {
                    base
                }
            val openedFile = SmbFile(request.uri, activeContext)
            val openedRandomAccess = SmbRandomAccessFile(openedFile, "r")
            val length = openedRandomAccess.length()
            val range = request.range
            if (range != null) openedRandomAccess.seek(range.startInclusive)
            context = activeContext
            file = openedFile
            randomAccess = openedRandomAccess
            YMediaTransportResponse(
                statusCode = if (range == null) 200 else 206,
                contentLength = length,
                acceptedRange = range?.boundedTo(length),
                features = features,
            )
        }

    override suspend fun read(
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int =
        withContext(Dispatchers.IO) {
            require(offset >= 0 && length >= 0 && offset + length <= destination.size)
            if (length == 0) 0 else randomAccess?.read(destination, offset, length) ?: -1
        }

    override suspend fun close() {
        withContext(Dispatchers.IO) { closeCurrent() }
    }

    private fun closeCurrent() {
        runCatching { randomAccess?.close() }
        runCatching { file?.close() }
        runCatching { context?.close() }
        randomAccess = null
        file = null
        context = null
    }
}

private fun smbProperties(): Properties =
    Properties().apply {
        setProperty("jcifs.smb.client.minVersion", "SMB202")
        setProperty("jcifs.smb.client.maxVersion", "SMB311")
        setProperty("jcifs.smb.client.enableSMB2", "true")
        setProperty("jcifs.smb.client.disableSMB1", "true")
        setProperty("jcifs.smb.client.responseTimeout", "15000")
        setProperty("jcifs.smb.client.soTimeout", "15000")
    }

private fun YByteRange.boundedTo(length: Long): YByteRange? {
    if (startInclusive >= length) return null
    return YByteRange(startInclusive, minOf(endInclusive ?: (length - 1L), length - 1L))
}
