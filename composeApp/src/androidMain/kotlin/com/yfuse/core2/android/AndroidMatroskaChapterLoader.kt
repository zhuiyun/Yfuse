package com.yfuse.core2.android

import android.content.Context
import com.yfuse.core2.api.YChapter
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.demux.readYMatroskaChapters
import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YSourceProtocol
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Background chapter I/O has its own connection and never seeks the active demuxer. */
internal suspend fun loadAndroidMatroskaChapters(
    context: Context,
    item: YMediaItem,
): List<YChapter> =
    withContext(Dispatchers.IO) {
        val scheme = item.uri.substringBefore(':').lowercase()
        val protocol =
            when (scheme) {
                "https" -> YSourceProtocol.Https
                "http" -> YSourceProtocol.Http
                "smb" -> YSourceProtocol.Smb
                "content", "file", "android.resource" -> YSourceProtocol.Local
                else -> return@withContext emptyList()
            }
        val transport: YMediaTransport =
            when (protocol) {
                YSourceProtocol.Smb -> AndroidSmbMediaTransport()
                YSourceProtocol.Local -> AndroidContentMediaTransport(context)
                else -> AndroidHttpMediaTransport(followSafeRedirects = true, allowCrossProtocolRedirects = true)
            }
        coroutineScope {
            // Cancellation/timeout closes the socket on another IO worker even during a blocking read.
            val closer =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        delay(CHAPTER_READ_TIMEOUT_MS)
                    } finally {
                        withContext(NonCancellable) { transport.close() }
                    }
                }
            val deadline = System.nanoTime() + CHAPTER_READ_TIMEOUT_MS * 1_000_000L
            try {
                readYMatroskaChapters { offset, maximum ->
                    currentCoroutineContext().ensureActive()
                    check(System.nanoTime() < deadline) { "Chapter metadata read timed out" }
                    transport.close()
                    val response =
                        transport.open(
                            YMediaTransportRequest(
                                item.uri,
                                protocol,
                                YByteRange(offset, offset + maximum - 1),
                                item.headers,
                                item.transportCredentials,
                            ),
                        )
                    require(response.statusCode in 200..299) { "Chapter metadata request failed" }
                    require(
                        offset == 0L || response.acceptedRange?.startInclusive == offset,
                    ) { "Chapter range was not honoured" }
                    val bytes = ByteArray(maximum)
                    var count = 0
                    while (count < maximum) {
                        currentCoroutineContext().ensureActive()
                        check(System.nanoTime() < deadline) { "Chapter metadata read timed out" }
                        val read = transport.read(bytes, count, maximum - count)
                        if (read <= 0) break
                        count += read
                    }
                    bytes.copyOf(count)
                }
            } finally {
                withContext(NonCancellable) {
                    closer.cancelAndJoin()
                    // The watchdog may have fired just before the last range was opened.
                    transport.close()
                }
            }
        }
    }

private const val CHAPTER_READ_TIMEOUT_MS = 5_000L
