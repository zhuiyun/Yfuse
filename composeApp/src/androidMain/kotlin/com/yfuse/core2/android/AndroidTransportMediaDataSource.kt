package com.yfuse.core2.android

import android.media.MediaDataSource
import android.os.Looper
import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YSourceProtocol
import kotlinx.coroutines.runBlocking

/** Adapts protocol transports to MediaExtractor without ever materializing the full remote file. */
internal class AndroidTransportMediaDataSource(
    private val uri: String,
    private val protocol: YSourceProtocol,
    private val headers: Map<String, String>,
    private val createTransport: () -> YMediaTransport,
) : MediaDataSource() {
    private var knownSize = -1L
    private var closed = false

    @Synchronized
    override fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        size: Int,
    ): Int {
        checkWorkerThread()
        check(!closed)
        require(position >= 0L && offset >= 0 && size >= 0 && offset <= buffer.size - size)
        if (size == 0) return 0
        if (knownSize >= 0L && position >= knownSize) return -1
        return runBlocking {
            val transport = createTransport()
            try {
                val end = position.saturatedAdd(size.toLong() - 1L)
                val response =
                    transport.open(
                        YMediaTransportRequest(
                            uri = uri,
                            protocol = protocol,
                            range = YByteRange(position, end),
                            headers = headers,
                        ),
                    )
                require(response.statusCode == 206) { "Random-access transport did not accept byte range" }
                require(response.acceptedRange?.startInclusive == position) {
                    "Random-access transport returned mismatched range metadata"
                }
                response.contentLength?.takeIf { it >= 0L }?.let { knownSize = it }
                var total = 0
                while (total < size) {
                    val count = transport.read(buffer, offset + total, size - total)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                }
                if (total == 0) -1 else total
            } finally {
                transport.close()
            }
        }
    }

    @Synchronized
    override fun getSize(): Long {
        checkWorkerThread()
        check(!closed)
        if (knownSize >= 0L) return knownSize
        val probe = ByteArray(1)
        readAt(0L, probe, 0, 1)
        return knownSize
    }

    @Synchronized
    override fun close() {
        closed = true
    }
}

private fun checkWorkerThread() {
    check(Looper.myLooper() != Looper.getMainLooper()) { "Remote media I/O is forbidden on the main thread" }
}

private fun Long.saturatedAdd(other: Long): Long =
    if (other > 0L && this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other
