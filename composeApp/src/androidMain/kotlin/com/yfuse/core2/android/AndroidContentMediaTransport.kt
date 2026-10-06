package com.yfuse.core2.android

import android.content.Context
import android.net.Uri
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** One opened document: its bytes, and the length its provider reports when it knows it. */
internal class AndroidOpenedDocument(
    val channel: FileChannel,
    val length: Long?,
    /** False for a pipe (a provider streaming the document); such a channel only reads forward. */
    val seekable: Boolean,
    private val release: () -> Unit = {},
) : Closeable {
    override fun close() {
        runCatching { channel.close() }
        runCatching(release)
    }
}

/**
 * Random-access transport over a ContentResolver document: a SAF pick, a downloads tree.
 *
 * The FFmpeg routes read such documents through YCore's loopback proxy rather than FFmpeg's own
 * android_content protocol. That protocol reads through an application-context global reference
 * that libmpv hands FFmpeg and deletes when an mpv player is destroyed; a read afterwards aborts
 * the process, and without libmpv it never works at all.
 */
internal class AndroidContentMediaTransport(
    private val openDocument: (String) -> AndroidOpenedDocument,
) : YMediaTransport {
    constructor(context: Context) : this(contentResolverOpener(context.applicationContext))

    override val supportedProtocols: Set<YSourceProtocol> = setOf(YSourceProtocol.Local)
    override val features: Set<YTransportFeature> =
        setOf(YTransportFeature.ByteRange, YTransportFeature.RandomAccess)

    private var document: AndroidOpenedDocument? = null

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse =
        withContext(Dispatchers.IO) {
            require(request.protocol == YSourceProtocol.Local)
            require(request.uri.startsWith("content://", ignoreCase = true))
            closeCurrent()
            val opened = openDocument(request.uri)
            try {
                val start = request.range?.startInclusive ?: 0L
                if (start > 0L && (opened.length == null || start < opened.length)) {
                    if (opened.seekable) opened.channel.position(start) else opened.channel.skipForward(start)
                }
                document = opened
                YMediaTransportResponse(
                    statusCode = if (request.range == null) 200 else 206,
                    contentLength = opened.length,
                    acceptedRange =
                        request.range?.let { range ->
                            if (opened.length != null) range.boundedTo(opened.length) else range
                        },
                    features = if (opened.seekable) features else emptySet(),
                    implementation = "content-resolver",
                )
            } catch (throwable: Throwable) {
                opened.close()
                throw throwable
            }
        }

    override suspend fun read(
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int =
        withContext(Dispatchers.IO) {
            require(offset >= 0 && length >= 0 && offset + length <= destination.size)
            if (length == 0) return@withContext 0
            val channel = document?.channel ?: return@withContext -1
            channel.read(ByteBuffer.wrap(destination, offset, length))
        }

    override suspend fun close() {
        withContext(Dispatchers.IO) { closeCurrent() }
    }

    private fun closeCurrent() {
        document?.close()
        document = null
    }
}

private fun contentResolverOpener(context: Context): (String) -> AndroidOpenedDocument =
    { uri ->
        val descriptor =
            context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")
                ?: throw FileNotFoundException("The document provider returned no file")
        try {
            val size = descriptor.statSize
            AndroidOpenedDocument(
                channel = FileInputStream(descriptor.fileDescriptor).channel,
                length = size.takeIf { it >= 0L },
                // statSize is -1 exactly when the descriptor is not a regular file.
                seekable = size >= 0L,
                release = descriptor::close,
            )
        } catch (throwable: Throwable) {
            runCatching { descriptor.close() }
            throw throwable
        }
    }

/** Reads and discards up to [count] bytes from a channel that cannot seek. */
private fun FileChannel.skipForward(count: Long) {
    val scratch = ByteBuffer.allocate(SKIP_BUFFER_BYTES)
    var remaining = count
    while (remaining > 0L) {
        scratch.clear()
        if (remaining < SKIP_BUFFER_BYTES) scratch.limit(remaining.toInt())
        val read = read(scratch)
        if (read < 0) throw IOException("Document ended before the requested offset")
        remaining -= read
    }
}

private const val SKIP_BUFFER_BYTES = 64 * 1024
