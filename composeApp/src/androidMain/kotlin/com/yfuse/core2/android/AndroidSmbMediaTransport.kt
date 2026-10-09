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

/**
 * SMB2/SMB3 random-access transport backed by jcifs-ng; SMB1 is explicitly disabled.
 *
 * Connections and logins come from [AndroidSmbContexts.shared], and an open file stays open across
 * the ranges a reader asks of it. Building a context per open cost a TCP connect, a negotiate and
 * an NTLM exchange for every 2 MiB block, which left remote UHD remuxes and ISOs starved.
 */
internal class AndroidSmbMediaTransport : YMediaTransport {
    override val supportedProtocols: Set<YSourceProtocol> = setOf(YSourceProtocol.Smb)
    override val features: Set<YTransportFeature> =
        setOf(YTransportFeature.ByteRange, YTransportFeature.RandomAccess, YTransportFeature.ConnectionReuse)

    private var openedUri: String? = null
    private var openedCredentials: YTransportCredentials? = null
    private var file: SmbFile? = null
    private var randomAccess: SmbRandomAccessFile? = null

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse =
        withContext(Dispatchers.IO) {
            require(request.protocol == YSourceProtocol.Smb)
            require(request.uri.startsWith("smb://", ignoreCase = true))
            val access =
                randomAccess?.takeIf { openedUri == request.uri && sameLogin(openedCredentials, request.credentials) }
                    ?: openFile(request)
            val length = access.length()
            val range = request.range
            access.seek(range?.startInclusive?.coerceAtMost(length) ?: 0L)
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

    private fun openFile(request: YMediaTransportRequest): SmbRandomAccessFile {
        closeCurrent()
        val openedFile = SmbFile(request.uri, AndroidSmbContexts.forLogin(request.credentials))
        try {
            val access = SmbRandomAccessFile(openedFile, "r")
            file = openedFile
            randomAccess = access
            openedUri = request.uri
            openedCredentials = request.credentials
            return access
        } catch (throwable: Throwable) {
            runCatching { openedFile.close() }
            throw throwable
        }
    }

    // The context is shared, so it is never closed here: that would drop every other reader's
    // connection. jcifs closes a connection itself once it has idled past the socket timeout.
    private fun closeCurrent() {
        runCatching { randomAccess?.close() }
        runCatching { file?.close() }
        randomAccess = null
        file = null
        openedUri = null
        openedCredentials = null
    }
}

/**
 * The process's jcifs context. jcifs pools connections per base context and reuses a session for
 * equal credentials, so every reader of a share shares one connection and one login.
 */
internal object AndroidSmbContexts {
    val shared: CIFSContext by lazy { BaseContext(PropertyConfiguration(smbProperties())) }

    fun forLogin(credentials: YTransportCredentials?): CIFSContext {
        val login = credentials as? YTransportCredentials.UsernamePassword ?: return shared
        return shared.withCredentials(NtlmPasswordAuthenticator(login.domain, login.username, login.password))
    }
}

private fun sameLogin(
    first: YTransportCredentials?,
    second: YTransportCredentials?,
): Boolean {
    if (first === second) return true
    val a = first as? YTransportCredentials.UsernamePassword ?: return false
    val b = second as? YTransportCredentials.UsernamePassword ?: return false
    return a.username == b.username && a.password == b.password && a.domain == b.domain
}

/** Shared with the 文件来源 browser, so a share that lists is one this transport can open. */
internal fun smbProperties(): Properties =
    Properties().apply {
        setProperty("jcifs.smb.client.minVersion", "SMB202")
        setProperty("jcifs.smb.client.maxVersion", "SMB311")
        setProperty("jcifs.smb.client.enableSMB2", "true")
        setProperty("jcifs.smb.client.disableSMB1", "true")
        setProperty("jcifs.smb.client.responseTimeout", "15000")
        setProperty("jcifs.smb.client.soTimeout", "15000")
    }

internal fun YByteRange.boundedTo(length: Long): YByteRange? {
    if (startInclusive >= length) return null
    return YByteRange(startInclusive, minOf(endInclusive ?: (length - 1L), length - 1L))
}
