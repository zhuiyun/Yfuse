package com.yfuse.core.filesource

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Passive FTP. Commands and file names are never logged; each operation owns its connections. */
internal class FtpFileSourceClient(
    private val subtitles: FileSourceSubtitleCache,
) : FileSourceClient {
    override suspend fun list(
        source: FileSource,
        credentials: FileSourceCredentials,
        path: List<String>,
    ) = listBounded(source, credentials, path, MAX_FILE_SOURCE_LISTING_ENTRIES).entries

    override suspend fun listBounded(
        source: FileSource,
        credentials: FileSourceCredentials,
        path: List<String>,
        maxEntries: Int,
    ): FileSourceListing =
        runInterruptible(Dispatchers.IO) {
            require(maxEntries in 1..MAX_FILE_SOURCE_LISTING_ENTRIES)
            FtpSession(source.origin, credentials).use { it.list(source.rootSegments + path, maxEntries) }
        }

    override suspend fun cacheSubtitle(
        source: FileSource,
        credentials: FileSourceCredentials,
        path: List<String>,
        entry: FileSourceEntry,
    ): String {
        val key = subtitleCacheKey(source.id, path, entry)
        subtitles.cached(key, entry.extension)?.let { return it }
        val bytes =
            runInterruptible(Dispatchers.IO) {
                FtpSession(source.origin, credentials).use { it.read(source.rootSegments + path, 4 * 1024 * 1024) }
            }
        return subtitles.store(key, entry.extension, utf8SubtitleBytes(bytes, entry.name))
    }

    override fun capabilities(source: FileSource) = setOf(FileSourceCapability.Rename, FileSourceCapability.Move)

    override suspend fun move(
        source: FileSource,
        credentials: FileSourceCredentials,
        from: List<String>,
        to: List<String>,
        directory: Boolean,
    ) = runInterruptible(Dispatchers.IO) {
        validateFileSourceMove(from, to)
        FtpSession(source.origin, credentials).use { session ->
            val destination = session.list(source.rootSegments + to.dropLast(1), MAX_FILE_SOURCE_LISTING_ENTRIES)
            require(!destination.truncated) { "目标文件夹过大，无法确认是否存在同名文件" }
            require(destination.entries.none { it.name == to.last() }) { "目标已存在同名文件" }
            session.move(source.rootSegments + from, source.rootSegments + to)
        }
    }
}

internal class FtpSession(
    origin: String,
    credentials: FileSourceCredentials,
) : Closeable {
    private val address = URI(origin)
    private val control = Socket()
    private val input: BufferedInputStream
    private val output: BufferedOutputStream

    init {
        require(address.scheme == "ftp" && address.host != null)
        try {
            control.connect(InetSocketAddress(address.host, address.port.takeIf { it > 0 } ?: 21), TIMEOUT)
            control.soTimeout = TIMEOUT
            input = BufferedInputStream(control.getInputStream())
            output = BufferedOutputStream(control.getOutputStream())
            checkCode(response(), 220)
            val user = command("USER " + credentials.username.ifBlank { "anonymous" })
            if (user.first ==
                331
            ) {
                checkCode(
                    command(
                        "PASS " + credentials.password.ifBlank { if (credentials.anonymous) "anonymous@" else "" },
                    ),
                    230,
                )
            } else {
                checkCode(user, 230)
            }
            command("OPTS UTF8 ON")
            checkCode(command("TYPE I"), 200)
        } catch (error: Throwable) {
            control.close()
            throw error
        }
    }

    fun list(
        path: List<String>,
        maxEntries: Int,
    ): FileSourceListing {
        checkCode(command("CWD " + path(path)), 250)
        var machine = true
        var data = passive()
        var reply = command("MLSD")
        if (reply.first in setOf(500, 501, 502, 504)) {
            data.close()
            machine = false
            data = passive()
            reply = command("LIST")
        }
        if (reply.first !in setOf(125, 150)) {
            data.close()
            checkCode(reply, 150)
        }
        val result = ArrayList<FileSourceEntry>()
        var visited = 0
        var truncated = false
        data.use { socket ->
            val stream = BufferedInputStream(socket.getInputStream())
            while (true) {
                val line = readLine(stream) ?: break
                if (visited++ >= maxEntries) {
                    truncated = true
                    break
                }
                val entry = if (machine) parseFtpMlsd(line) else parseFtpList(line)
                if (entry != null) result += entry
            }
        }
        if (!truncated) checkCode(response(), 226, 250)
        return FileSourceListing(result, truncated)
    }

    fun read(
        path: List<String>,
        maxBytes: Int,
    ): ByteArray {
        val data = passive()
        val reply = command("RETR " + path(path))
        if (reply.first !in setOf(125, 150)) {
            data.close()
            checkCode(reply, 150)
        }
        val bytes =
            data.use { socket ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                socket.getInputStream().use { stream ->
                    while (true) {
                        val count = stream.read(chunk)
                        if (count < 0) break
                        if (buffer.size() + count > maxBytes) throw FileSourceException(FileSourceFailure.TooLarge)
                        buffer.write(chunk, 0, count)
                    }
                }
                buffer.toByteArray()
            }
        checkCode(response(), 226, 250)
        return bytes
    }

    fun move(
        from: List<String>,
        to: List<String>,
    ) {
        checkCode(command("RNFR " + path(from)), 350)
        checkCode(command("RNTO " + path(to)), 250)
    }

    fun size(segments: List<String>): Long {
        val reply = command("SIZE " + path(segments))
        checkCode(reply, 213)
        return reply.second
            .substringAfter(' ')
            .trim()
            .toLongOrNull()
            ?.takeIf { it >= 0 }
            ?: throw FileSourceException(FileSourceFailure.InvalidResponse)
    }

    /** REST is required for nonzero offsets; servers without it fail instead of returning wrong bytes. */
    fun openRead(
        segments: List<String>,
        offset: Long,
    ): Socket {
        require(offset >= 0)
        if (offset > 0) checkCode(command("REST $offset"), 350)
        val data = passive()
        try {
            checkCode(command("RETR " + path(segments)), 125, 150)
            return data
        } catch (error: Throwable) {
            data.close()
            throw error
        }
    }

    private fun path(segments: List<String>): String {
        require(segments.all { it !in setOf(".", "..") && it.none { c -> c == '/' || c == '\\' || c.isISOControl() } })
        return "/" + segments.joinToString("/")
    }

    private fun passive(): Socket {
        val extended = command("EPSV")
        val port =
            if (extended.first == 229) {
                Regex("\\(.(?:.){2}(\\d+).\\)")
                    .find(extended.second)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
            } else {
                val passive = command("PASV")
                checkCode(passive, 227)
                val parts =
                    Regex(
                        "\\((\\d+(?:,\\d+){5})\\)",
                    ).find(passive.second)?.groupValues?.get(1)?.split(',')?.map { it.toInt() }
                parts?.takeIf { it.all { n -> n in 0..255 } }?.let { it[4] * 256 + it[5] }
            } ?: throw FileSourceException(FileSourceFailure.InvalidResponse)
        require(port in 1..65535)
        return Socket().apply {
            try {
                connect(InetSocketAddress(control.inetAddress, port), TIMEOUT)
                soTimeout = TIMEOUT
            } catch (
                error: Throwable,
            ) {
                close()
                throw error
            }
        }
    }

    private fun command(value: String): Pair<Int, String> {
        require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) { "FTP 参数含控制字符" }
        output.write((value + "\r\n").toByteArray(Charsets.UTF_8))
        output.flush()
        return response()
    }

    private fun response(): Pair<Int, String> {
        val first = readLine(input) ?: throw FileSourceException(FileSourceFailure.Unreachable)
        val code = first.take(3).toIntOrNull() ?: throw FileSourceException(FileSourceFailure.InvalidResponse)
        if (first.getOrNull(3) == '-') {
            repeat(100) {
                val line = readLine(input) ?: throw FileSourceException(FileSourceFailure.InvalidResponse)
                if (line.startsWith("$code ")) return code to line
            }
            throw FileSourceException(FileSourceFailure.InvalidResponse)
        }
        return code to first
    }

    private fun checkCode(
        reply: Pair<Int, String>,
        vararg expected: Int,
    ) {
        if (reply.first in expected) return
        throw FileSourceException(
            when (reply.first) {
                530 -> FileSourceFailure.Unauthorized
                550 -> FileSourceFailure.NotFound
                else -> FileSourceFailure.Server
            },
            reply.first,
        )
    }

    override fun close() {
        control.close()
    }

    companion object {
        private const val TIMEOUT = 15_000
    }
}

private fun readLine(stream: InputStream): String? {
    val bytes = java.io.ByteArrayOutputStream()
    while (true) {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
        val value = stream.read()
        if (value == -1) return if (bytes.size() == 0) null else bytes.toString("UTF-8").trimEnd('\r')
        if (value == 10) return bytes.toString("UTF-8").trimEnd('\r')
        if (bytes.size() >= 8192) throw FileSourceException(FileSourceFailure.InvalidResponse)
        bytes.write(value)
    }
}

internal fun parseFtpMlsd(line: String): FileSourceEntry? {
    val split = line.indexOf(' ')
    if (split <= 0) return null
    val name = line.substring(split + 1)
    if (name.isBlank() ||
        name in setOf(".", "..") ||
        name.any { it == '/' || it == '\\' || it.isISOControl() }
    ) {
        return null
    }
    val facts =
        line
            .substring(0, split)
            .split(';')
            .mapNotNull { fact ->
                val equals = fact.indexOf('=')
                if (equals <
                    0
                ) {
                    null
                } else {
                    fact.take(equals).lowercase() to fact.substring(equals + 1)
                }
            }.toMap()
    val type = facts["type"]?.lowercase()
    if (type !in setOf("file", "dir")) return null
    val modified =
        facts["modify"]?.take(14)?.let {
            runCatching {
                LocalDateTime
                    .parse(
                        it,
                        DateTimeFormatter.ofPattern("yyyyMMddHHmmss"),
                    ).toInstant(ZoneOffset.UTC)
                    .toEpochMilli()
            }.getOrNull()
        }
    return FileSourceEntry(name, type == "dir", facts["size"]?.toLongOrNull()?.takeIf { it >= 0 }, modified)
}

internal fun parseFtpList(line: String): FileSourceEntry? {
    val fields = line.trim().split(Regex("\\s+"), limit = 9)
    if (fields.size != 9 || fields[0].firstOrNull() !in setOf('d', '-')) return null
    val name = fields[8]
    if (name in setOf(".", "..") || name.any { it == '/' || it == '\\' || it.isISOControl() }) return null
    return FileSourceEntry(name, fields[0].startsWith('d'), fields[4].toLongOrNull()?.takeIf { it >= 0 })
}
