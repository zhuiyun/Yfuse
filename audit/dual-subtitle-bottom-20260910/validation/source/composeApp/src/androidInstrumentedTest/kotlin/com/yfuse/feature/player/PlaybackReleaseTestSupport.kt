package com.yfuse.feature.player

import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.BuildConfig
import com.yfuse.MainActivity
import com.yfuse.core.logging.DiagnosticLogStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assume.assumeFalse
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

/** Only these tests' isolated package and generated media may be used for release measurements. */
internal fun requireFullReleaseTestPackage() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    check(BuildConfig.DEBUG && context.packageName != "com.yfuse") {
        "Release instrumentation requires an isolated debug application id; main app data is not used"
    }
    assumeFalse(
        "Compatibility engines are intentionally absent from a native-only APK",
        BuildConfig.YFUSE_NATIVE_ONLY_RUNTIME,
    )
}

internal class PlaybackReleaseTestHost private constructor(
    private val scenario: ActivityScenario<MainActivity>,
    val view: SurfaceView,
    private val callback: SurfaceHolder.Callback,
) : Closeable {
    fun <T> onMain(block: (MainActivity) -> T): T {
        var outcome: Result<T>? = null
        scenario.onActivity { activity -> outcome = runCatching { block(activity) } }
        return checkNotNull(outcome).getOrThrow()
    }

    override fun close() {
        try {
            onMain {
                view.holder.removeCallback(callback)
                (view.parent as? ViewGroup)?.removeView(view)
            }
        } finally {
            scenario.close()
        }
    }

    companion object {
        suspend fun open(): PlaybackReleaseTestHost {
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            val ready = CompletableDeferred<Unit>()
            var view: SurfaceView? = null
            val callback =
                object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        if (holder.surface.isValid) ready.complete(Unit)
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) = Unit

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        if (!ready.isCompleted) {
                            ready.completeExceptionally(
                                AssertionError("Test Surface was destroyed before use"),
                            )
                        }
                    }
                }
            try {
                scenario.onActivity { activity ->
                    view =
                        SurfaceView(activity).also {
                            it.keepScreenOn = true
                            it.holder.addCallback(callback)
                            activity.setContentView(it, ViewGroup.LayoutParams(320, 180))
                        }
                }
                withTimeout(10_000L) { ready.await() }
                return PlaybackReleaseTestHost(scenario, checkNotNull(view), callback)
            } catch (error: Throwable) {
                scenario.close()
                throw error
            }
        }
    }
}

/** One loopback accept thread; responses remain blocked until explicitly released by the test. */
internal class PlaybackHeldResponseOrigin : Closeable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val sockets = Collections.synchronizedList(mutableListOf<Socket>())
    private val closed = AtomicBoolean(false)
    private val response = AtomicReference<ByteArray?>()
    private val responded = ConcurrentHashMap.newKeySet<Socket>()
    val requested = CompletableDeferred<String>()
    val url: String = "http://127.0.0.1:${server.localPort}/synthetic-test-media"
    private val thread =
        Thread({
            try {
                while (!closed.get()) {
                    val socket = server.accept()
                    sockets += socket
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val requestLine = input.readLine() ?: continue
                    var headerBytes = requestLine.length
                    while (true) {
                        val line = input.readLine() ?: break
                        headerBytes += line.length
                        check(headerBytes <= 16_384) { "Loopback request headers exceed test budget" }
                        if (line.isEmpty()) break
                    }
                    requested.complete(requestLine)
                    response.get()?.let { send(socket, it) }
                }
            } catch (error: Exception) {
                if (!closed.get()) requested.completeExceptionally(error)
            }
        }, "Yfuse-release-test-origin").apply {
            isDaemon = true
            start()
        }

    fun respond(
        status: Int = 200,
        body: String,
    ) {
        val content = body.encodeToByteArray()
        val headers =
            "HTTP/1.1 $status Test\r\nContent-Type: text/plain\r\n" +
                "Content-Length: ${content.size}\r\nConnection: close\r\n\r\n"
        val bytes = headers.toByteArray(Charsets.US_ASCII) + content
        response.set(bytes)
        synchronized(sockets) { sockets.toList() }.forEach { send(it, bytes) }
    }

    private fun send(
        socket: Socket,
        bytes: ByteArray,
    ) {
        if (!responded.add(socket)) return
        runCatching {
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
            socket.close()
        }
    }

    override fun close() {
        closed.set(true)
        server.close()
        synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
        thread.join(2_000L)
        check(!thread.isAlive) { "Loopback test origin did not stop" }
    }
}

/** Read only structured logs from the isolated application; the export flushes queued writes. */
internal fun exportedReleaseEntries(): List<JSONObject> {
    val bytes = ByteArrayOutputStream()
    DiagnosticLogStore.export(bytes)
    val entries = mutableListOf<JSONObject>()
    ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.name.startsWith("logs/")) {
                zip.readBytes().decodeToString().lineSequence().filter(String::isNotBlank).forEach { line ->
                    val item = JSONObject(line)
                    if (item.optString("category") == "player.release") entries += item
                }
            }
            zip.closeEntry()
        }
    }
    return entries
}
