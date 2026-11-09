package com.yfuse.core.localimport

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.yfuse.core.filesource.FileSourceEntry
import com.yfuse.core.util.androidAppContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

actual fun createWifiImportService(): WifiImportService =
    AndroidWifiImportService(
        directory = { requireNotNull(androidAppContext).filesDir.resolve("wifi-import") },
        address = ::localAddress,
    )

/** Only an explicitly started, foreground session accepts uploads. No remote read/delete routes. */
internal class AndroidWifiImportService(
    private val directory: () -> File,
    private val address: () -> InetAddress? = ::localAddress,
    private val maxFileBytes: Long = 2L * 1024 * 1024 * 1024,
    private val maxSessionBytes: Long = 4L * 1024 * 1024 * 1024,
) : WifiImportService {
    private val _state = MutableStateFlow(WifiImportState())
    override val state = _state.asStateFlow()

    @Volatile private var session: Session? = null
    private var generation = 0L

    override suspend fun load() =
        withContext(Dispatchers.IO) {
            directory()
                .listFiles()
                ?.filter {
                    it.name.endsWith(".partial") &&
                        it.name.removeSuffix(".partial").matches(SAFE_ID)
                }?.forEach { confined(directory(), it.name).delete() }
            refreshFiles()
        }

    override suspend fun start() {
        val expectedGeneration = synchronized(this) { generation }
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            synchronized(this@AndroidWifiImportService) {
                if (session != null || generation != expectedGeneration) return@synchronized
                val ip = address() ?: throw IOException("请连接局域网 Wi-Fi 后重试")
                val folder = directory().apply { mkdirs() }
                require(folder.isDirectory)
                val server =
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(ip, 0), 4)
                    }
                val code = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')
                val current = Session(server, code)
                session = current
                _state.update {
                    it.copy(
                        running = true,
                        address = "http://${ip.hostAddress}:${server.localPort}",
                        pairingCode = code,
                        message = null,
                    )
                }
                thread(name = "yfuse-wifi-import", isDaemon = true) { accept(current) }
            }
        }
    }

    override fun stop() {
        val closing =
            synchronized(this) {
                generation++
                session.also { session = null }
            } ?: return
        closing.closed.set(true)
        runCatching { closing.server.close() }
        synchronized(closing.connections) { closing.connections.toList() }.forEach { runCatching { it.close() } }
        closing.workers.shutdownNow()
        _state.update { it.copy(running = false, address = null, pairingCode = null, receiving = false) }
    }

    override suspend fun remove(id: String) =
        withContext(Dispatchers.IO) {
            require(id.matches(SAFE_ID))
            val target = confined(directory(), id)
            require(target.isFile && target.delete()) { "无法移除文件" }
            refreshFiles()
        }

    private fun accept(current: Session) {
        try {
            while (!current.closed.get()) {
                val socket = current.server.accept()
                socket.soTimeout = 20_000
                if (!current.slots.tryAcquire()) {
                    socket.close()
                    continue
                }
                current.connections += socket
                try {
                    current.workers.execute {
                        try {
                            socket.use { handle(current, it) }
                        } catch (_: Exception) {
                            if (!current.closed.get() &&
                                session === current
                            ) {
                                _state.update { it.copy(message = "传输中断，未完成的文件没有保存") }
                            }
                        } finally {
                            current.connections -= socket
                            current.slots.release()
                        }
                    }
                } catch (_: Exception) {
                    current.connections -= socket
                    current.slots.release()
                    socket.close()
                }
            }
        } catch (_: IOException) {
            if (!current.closed.get()) {
                _state.update { it.copy(message = "接收服务已停止，请重新开启") }
                stop()
            }
        }
    }

    private fun handle(
        current: Session,
        socket: Socket,
    ) {
        val input = BufferedInputStream(socket.getInputStream())
        val first = readHttpLine(input, 4096) ?: return
        val parts = first.split(' ')
        if (parts.size != 3 || parts[2] !in setOf("HTTP/1.1", "HTTP/1.0")) {
            reply(socket, 400, "无效请求")
            return
        }
        val headers = linkedMapOf<String, String>()
        var bytes = 0
        while (true) {
            val line = readHttpLine(input, 4096) ?: return
            bytes += line.length
            if (line.isEmpty()) break
            if (bytes > 16384 || headers.size >= 32) {
                reply(socket, 431, "请求头过长")
                return
            }
            val split = line.indexOf(':')
            if (split <= 0) {
                reply(socket, 400, "无效请求头")
                return
            }
            val key = line.take(split).lowercase()
            if (key in headers) {
                reply(socket, 400, "重复请求头")
                return
            }
            headers[key] = line.substring(split + 1).trim()
        }
        val host = socket.localAddress.hostAddress + ":" + socket.localPort
        if (headers["host"] != host) {
            reply(socket, 403, "地址不匹配")
            return
        }
        if (parts[0] == "GET" &&
            parts[1] == "/"
        ) {
            reply(socket, 200, UPLOAD_PAGE, "text/html; charset=utf-8")
            return
        }
        if (parts[0] != "PUT" || !parts[1].startsWith("/upload?name=")) {
            reply(socket, 404, "不存在")
            return
        }
        val origin = headers["origin"]
        if (origin != null && origin != "http://$host") {
            reply(socket, 403, "来源不匹配")
            return
        }
        val provided = headers["x-yfuse-pair-code"].orEmpty()
        if (current.failedCodes.get() >= 10 ||
            !MessageDigest.isEqual(provided.toByteArray(), current.code.toByteArray())
        ) {
            current.failedCodes.incrementAndGet()
            reply(socket, 403, "配对码错误；错误 10 次后需重新开启接收")
            return
        }
        if ("transfer-encoding" in headers || "expect" in headers) {
            reply(socket, 400, "不支持此传输方式")
            return
        }
        val length = headers["content-length"]?.toLongOrNull()
        if (length == null || length <= 0 || length > maxFileBytes) {
            reply(socket, 413, "文件大小超过限制")
            return
        }
        if (!current.upload.tryAcquire()) {
            reply(socket, 409, "已有文件正在传输")
            return
        }
        try {
            val name = URLDecoder.decode(parts[1].substringAfter("/upload?name="), "UTF-8")
            if (name.isBlank() ||
                name.length > 128 ||
                name.any { it == '/' || it == '\\' || it.isISOControl() } ||
                !FileSourceEntry(name, false).isVideo
            ) {
                reply(socket, 400, "请选择有效视频文件名")
                return
            }
            val folder = directory()
            val existing = folder.listFiles()?.filter { it.isFile && it.name.matches(SAFE_ID) }.orEmpty()
            if (existing.size >= 200 ||
                existing.sumOf { it.length() } + length > MAX_SAVED_BYTES ||
                current.received.get() + length > maxSessionBytes ||
                folder.usableSpace < length + RESERVED_BYTES
            ) {
                reply(socket, 507, "存储空间或本次接收额度不足")
                return
            }
            val id = UUID.randomUUID().toString() + "__" + name
            val target = confined(folder, id)
            val partial = confined(folder, "$id.partial")
            _state.update { it.copy(receiving = true, message = "正在接收：$name") }
            try {
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = length
                    while (remaining > 0) {
                        if (current.closed.get()) throw InterruptedIOException()
                        val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                        if (count < 0) throw EOFException()
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                    output.fd.sync()
                }
                synchronized(this@AndroidWifiImportService) {
                    if (current.closed.get() || session !== current) throw InterruptedIOException()
                    Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    current.received.addAndGet(length)
                    refreshFiles()
                    _state.update { it.copy(message = "已接收：$name") }
                }
                reply(socket, 201, "已保存，可以在 Yfuse 播放")
            } finally {
                partial.delete()
                if (session === current) _state.update { it.copy(receiving = false) }
            }
        } finally {
            current.upload.release()
        }
    }

    private fun refreshFiles() {
        val files =
            directory()
                .listFiles()
                ?.filter {
                    it.isFile && it.name.matches(SAFE_ID)
                }?.sortedByDescending(File::lastModified)
                ?.take(200)
                ?.map {
                    WifiImportedFile(it.name, it.name.substringAfter("__"), it.toURI().toString(), it.length())
                }.orEmpty()
        _state.update { it.copy(files = files) }
    }

    private class Session(
        val server: ServerSocket,
        val code: String,
    ) {
        val closed = AtomicBoolean()
        val failedCodes = AtomicLong()
        val received = AtomicLong()
        val upload = Semaphore(1)
        val slots = Semaphore(3)
        val connections = Collections.synchronizedSet(mutableSetOf<Socket>())
        val workers =
            Executors.newFixedThreadPool(3) { task ->
                Thread(task, "yfuse-wifi-request").apply {
                    isDaemon =
                        true
                }
            }
    }

    companion object {
        private val SAFE_ID = Regex("[0-9a-f-]{36}__[^/\\\\\u0000-\u001f]{1,128}")
        private const val MAX_SAVED_BYTES = 8L * 1024 * 1024 * 1024
        private const val RESERVED_BYTES = 64L * 1024 * 1024
    }
}

private fun confined(
    root: File,
    name: String,
): File {
    val base = root.canonicalFile
    val result = File(base, name).canonicalFile
    require(result.parentFile == base) { "Invalid import path" }
    return result
}

private fun readHttpLine(
    input: InputStream,
    maximum: Int,
): String? {
    val output = ByteArrayOutputStream()
    while (true) {
        val byte = input.read()
        if (byte == -1) return null
        if (byte == 10) return output.toString("ISO-8859-1").trimEnd('\r')
        if (output.size() >= maximum) throw IOException("HTTP line too long")
        output.write(byte)
    }
}

private fun reply(
    socket: Socket,
    status: Int,
    body: String,
    contentType: String = "text/plain; charset=utf-8",
) {
    val bytes = body.toByteArray()
    val header =
        "HTTP/1.1 $status Result\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\n" +
            "Connection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
            "connect-src 'self'; frame-ancestors 'none'\r\n\r\n"
    socket.getOutputStream().apply {
        write(header.toByteArray())
        write(bytes)
        flush()
    }
}

private fun localAddress(): InetAddress? {
    val context = androidAppContext ?: return null
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return manager.allNetworks
        .asSequence()
        .filter { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@filter false
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                )
        }.flatMap {
            manager
                .getLinkProperties(it)
                ?.linkAddresses
                .orEmpty()
                .asSequence()
        }.map { it.address }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
}

private val UPLOAD_PAGE =
    """
    <!doctype html><html lang="zh"><meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1"><title>Yfuse Wi-Fi 导入</title>
    <style>
    body{font:17px system-ui;max-width:640px;margin:50px auto;padding:20px;background:#10151e;color:#edf3fb}
    input,button{font:inherit;margin:12px 0;padding:12px;display:block;max-width:90%}
    button{background:#8cc8ff;border:0;border-radius:10px}p{line-height:1.6}
    </style><h1>Yfuse Wi-Fi 导入</h1>
    <p>在 Yfuse 查看配对码。每次上传一个视频，单文件最多 2 GiB；接收页面需保持在前台。</p>
    <label>配对码<input id="code" inputmode="numeric" maxlength="6" autocomplete="off"></label>
    <input id="file" type="file" aria-label="选择视频文件" accept="video/*,.mkv,.iso,.rmvb">
    <button id="send">上传视频</button><p id="status" role="status"></p>
    <script>
    const button=document.getElementById('send');
    button.onclick=async()=>{
      const file=document.getElementById('file').files[0];if(!file)return;
      button.disabled=true;const status=document.getElementById('status');
      status.textContent='正在上传，请勿关闭此页…';
      try{
        const response=await fetch('/upload?name='+encodeURIComponent(file.name),{
          method:'PUT',headers:{'X-Yfuse-Pair-Code':document.getElementById('code').value},body:file
        });
        status.textContent=await response.text();
      }catch(e){status.textContent='连接中断，请确认 Yfuse 接收页面仍在前台'}
      finally{button.disabled=false}
    };
    </script></html>
    """.trimIndent()
