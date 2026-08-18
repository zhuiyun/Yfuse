package com.yfuse.feature.player

import android.net.Uri
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.playback.PlaybackDiscChapter
import com.yfuse.core.playback.PlaybackDiscKind
import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core.playback.PlaybackDiscNavigationState
import com.yfuse.deviceId
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Java object handed to the custom libmpv/libbluray JNI bridge.
 *
 * The native side sees [readBlocksNative] plus the state/overlay callbacks below. Every HTTP request
 * resolves the currently saved server route/token again, so a route failover or renewed session does
 * not leave a 100 GiB movie tied to stale credentials. Tokens are headers, never part of the source URL.
 *
 * A small aligned read-ahead cache is intentionally above [HttpRangeDiscBlockSource]: libudfread can
 * ask for tiny random UDF reads, while origins perform much better when adjacent 2 KiB blocks become
 * one 64 KiB byte-range request. The cache is bounded and contains media bytes only.
 */
class NativeRemoteBluRayBlockSource(
    private val serverRegistry: ServerRegistry,
    private val serverId: String,
    private val itemId: String,
    private val mediaSourceId: String,
    private val playSessionId: String,
) {
    private data class Window(
        val startLba: Int,
        val blockCount: Int,
        val bytes: ByteArray,
    ) {
        fun contains(lba: Int, requestedBlocks: Int): Boolean {
            if (requestedBlocks <= 0) return false
            val endExclusive = lba.toLong() + requestedBlocks.toLong()
            val windowEnd = startLba.toLong() + blockCount.toLong()
            return lba >= startLba && endExclusive <= windowEnd
        }
    }

    private val cache =
        object : LinkedHashMap<Int, Window>(MAX_CACHE_WINDOWS, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Window>?): Boolean =
                size > MAX_CACHE_WINDOWS
        }

    private val rangeRequests = AtomicLong(0L)
    private val bytesFetched = AtomicLong(0L)
    private val cacheHits = AtomicLong(0L)
    private val hdmvSession = NativeRemoteBluRayHdmvSession(this)

    @Volatile
    private var nativeId: Long = 0L

    @Volatile
    private var closed = false

    internal fun bindNativeId(id: Long) {
        nativeId = id.takeIf { it > 0L } ?: 0L
        hdmvSession.bindNativeId(nativeId)
    }

    /**
     * JNI contract. Returns complete 2048-byte blocks, 0 at EOF, -1 on failure.
     *
     * Synchronized because one libbluray handle owns a logical cursor. It also keeps cache mutation
     * deterministic if a navigation/event thread asks for metadata while playback is reading.
     */
    @Synchronized
    fun readBlocksNative(
        lba: Int,
        blockCount: Int,
        target: ByteArray,
        targetOffset: Int,
    ): Int {
        if (closed || lba < 0 || blockCount <= 0 || targetOffset < 0) return -1
        val requestedBytes = blockCount.toLong() * BLURAY_UDF_BLOCK_SIZE
        if (requestedBytes > Int.MAX_VALUE) return -1
        if (targetOffset > target.size || requestedBytes > target.size.toLong() - targetOffset) return -1

        cachedWindow(lba, blockCount)?.let { window ->
            cacheHits.incrementAndGet()
            copyFromWindow(window, lba, blockCount, target, targetOffset)
            return blockCount
        }

        val fetchBlocks = maxOf(blockCount, READ_AHEAD_BLOCKS).coerceAtMost(MAX_RANGE_BLOCKS)
        val fetchBytes = fetchBlocks * BLURAY_UDF_BLOCK_SIZE
        val buffer = ByteArray(fetchBytes)
        val server = serverRegistry.serverById(serverId) ?: return -1
        val sourceUrl = buildRawDiscUrl(server.baseUrl) ?: return -1
        val headerProvider =
            RemoteDiscHeaderProvider {
                val current = serverRegistry.serverById(serverId) ?: return@RemoteDiscHeaderProvider emptyMap()
                buildMap {
                    current.accessToken.takeIf(String::isNotBlank)?.let { put("X-Emby-Token", it) }
                }
            }
        val read =
            HttpRangeDiscBlockSource(
                sourceUrl = sourceUrl,
                headerProvider = headerProvider,
            ).use { source ->
                rangeRequests.incrementAndGet()
                source.readBlocks(
                    lba = lba,
                    blockCount = fetchBlocks,
                    target = buffer,
                    targetOffset = 0,
                )
            }
        if (read <= 0) return read

        val completeBytes = read * BLURAY_UDF_BLOCK_SIZE
        bytesFetched.addAndGet(completeBytes.toLong())
        val window = Window(startLba = lba, blockCount = read, bytes = buffer.copyOf(completeBytes))
        cache[lba] = window
        val available = minOf(blockCount, read)
        copyFromWindow(window, lba, available, target, targetOffset)
        return available
    }

    /** Called by libbluray/mpv after a real stream session has been opened. */
    @Suppress("unused")
    fun onNativeSessionState(
        titleCount: Int,
        selectedTitleIndex: Int,
        chapterCount: Int,
        selectedChapterIndex: Int,
        angleCount: Int,
        selectedAngleIndex: Int,
        menuSupported: Boolean,
        menuActive: Boolean,
    ) {
        if (closed) return
        hdmvSession.updateNavigation(
            titleCount = titleCount,
            selectedTitleIndex = selectedTitleIndex,
            chapterCount = chapterCount,
            selectedChapterIndex = selectedChapterIndex,
            angleCount = angleCount,
            selectedAngleIndex = selectedAngleIndex,
            menuSupported = menuSupported,
            menuActive = menuActive,
        )
        NativeRemoteBluRaySessionRegistry.activate(hdmvSession)
    }

    /** Full IG plane pushed only on libbluray FLUSH events, not on every RLE draw operation. */
    @Suppress("unused")
    fun onNativeOverlayFrame(
        width: Int,
        height: Int,
        argb: IntArray,
    ) {
        if (closed || width <= 0 || height <= 0 || argb.size != width * height) return
        NativeRemoteBluRaySessionRegistry.updateOverlay(
            hdmvSession,
            NativeBluRayOverlayFrame(width = width, height = height, argb = argb.copyOf()),
        )
    }

    @Suppress("unused")
    fun onNativeOverlayCleared() {
        NativeRemoteBluRaySessionRegistry.updateOverlay(hdmvSession, null)
    }

    @Suppress("unused")
    fun onNativeSessionClosed() {
        hdmvSession.markClosed()
        NativeRemoteBluRaySessionRegistry.deactivate(hdmvSession)
    }

    @Synchronized
    fun closeNativeSource() {
        if (closed) return
        closed = true
        cache.clear()
        onNativeSessionClosed()
    }

    fun metrics(): NativeRemoteBluRayMetrics =
        NativeRemoteBluRayMetrics(
            rangeRequests = rangeRequests.get(),
            bytesFetched = bytesFetched.get(),
            cacheHits = cacheHits.get(),
        )

    private fun cachedWindow(
        lba: Int,
        blockCount: Int,
    ): Window? = cache.values.firstOrNull { it.contains(lba, blockCount) }

    private fun copyFromWindow(
        window: Window,
        lba: Int,
        blockCount: Int,
        target: ByteArray,
        targetOffset: Int,
    ) {
        val sourceOffset = (lba - window.startLba) * BLURAY_UDF_BLOCK_SIZE
        val count = blockCount * BLURAY_UDF_BLOCK_SIZE
        window.bytes.copyInto(
            destination = target,
            destinationOffset = targetOffset,
            startIndex = sourceOffset,
            endIndex = sourceOffset + count,
        )
    }

    private fun buildRawDiscUrl(baseUrl: String): String? {
        val root = baseUrl.trim().trimEnd('/').takeIf(String::isNotEmpty) ?: return null
        val path = "$root/Videos/${Uri.encode(itemId)}/stream"
        return runCatching {
            Uri.parse(path)
                .buildUpon()
                .appendQueryParameter("static", "true")
                .appendQueryParameter("MediaSourceId", mediaSourceId)
                .appendQueryParameter("DeviceId", deviceId())
                .apply {
                    playSessionId.takeIf(String::isNotBlank)?.let {
                        appendQueryParameter("PlaySessionId", it)
                    }
                }
                .build()
                .toString()
        }.getOrNull()
    }

    private companion object {
        const val READ_AHEAD_BLOCKS = 32 // 64 KiB
        const val MAX_RANGE_BLOCKS = 256 // 512 KiB hard ceiling per native callback
        const val MAX_CACHE_WINDOWS = 8 // <= 4 MiB media-byte cache at the largest window
    }
}

internal data class NativeRemoteBluRayMetrics(
    val rangeRequests: Long,
    val bytesFetched: Long,
    val cacheHits: Long,
)

internal data class NativeBluRayOverlayFrame(
    val width: Int,
    val height: Int,
    /** Straight ARGB pixels in authored overlay-plane coordinates. */
    val argb: IntArray,
)

/** Menu-side adapter. Video/title/chapter rendering still belongs to the active MPV engine. */
private class NativeRemoteBluRayHdmvSession(
    private val source: NativeRemoteBluRayBlockSource,
) : HdmvDiscSession {
    @Volatile
    private var nativeId: Long = 0L

    @Volatile
    private var closed = false

    @Volatile
    private var navigationState = PlaybackDiscNavigationState(kind = PlaybackDiscKind.BluRay)

    @Volatile
    private var listener: (() -> Unit)? = null

    fun bindNativeId(id: Long) {
        nativeId = id
    }

    fun updateNavigation(
        titleCount: Int,
        selectedTitleIndex: Int,
        chapterCount: Int,
        selectedChapterIndex: Int,
        angleCount: Int,
        selectedAngleIndex: Int,
        menuSupported: Boolean,
        menuActive: Boolean,
    ) {
        if (closed) return
        navigationState =
            PlaybackDiscNavigationState(
                kind = PlaybackDiscKind.BluRay,
                titleCount = titleCount.coerceAtLeast(0),
                selectedTitleIndex = selectedTitleIndex.coerceAtLeast(0),
                chapterCount = chapterCount.coerceAtLeast(0),
                selectedChapterIndex = selectedChapterIndex.coerceAtLeast(0),
                angleCount = angleCount.coerceAtLeast(0),
                selectedAngleIndex =
                    selectedAngleIndex
                        .coerceAtLeast(0)
                        .coerceAtMost((angleCount - 1).coerceAtLeast(0)),
                chapters =
                    List(chapterCount.coerceAtLeast(0)) { index ->
                        PlaybackDiscChapter(index = index)
                    },
                menuSupported = menuSupported,
                menuActive = menuSupported && menuActive,
            )
        listener?.invoke()
    }

    fun markClosed() {
        if (closed) return
        closed = true
        navigationState = navigationState.copy(menuActive = false, menuSupported = false)
        listener?.invoke()
    }

    override fun navigation(): PlaybackDiscNavigationState = navigationState

    // CompositeDiscNavigationBackend deliberately keeps these on the MPV engine.
    override fun selectTitle(index: Int): Boolean = false

    override fun selectChapter(index: Int): Boolean = false

    override fun selectAngle(index: Int): Boolean {
        if (closed || index !in 0 until navigationState.effectiveAngleCount) return false
        return NativeRemoteBluRayRegistry.selectAngle(nativeId, index)
    }

    override fun sendMenuCommand(command: PlaybackDiscMenuCommand): Boolean {
        if (closed || !navigationState.menuSupported) return false
        return NativeRemoteBluRayRegistry.sendMenuCommand(nativeId, command.nativeMenuCode())
    }

    override fun selectMenuPoint(
        x: Int,
        y: Int,
        activate: Boolean,
    ): Boolean {
        if (closed || !navigationState.menuActive || x < 0 || y < 0) return false
        return NativeRemoteBluRayRegistry.selectMenuPoint(nativeId, x, y, activate)
    }

    override fun setNavigationChangedListener(listener: (() -> Unit)?) {
        this.listener = listener
    }

    /** UI/backend release must never close the block source owned by the native stream. */
    override fun close() {
        listener = null
    }
}

/** One PlayerActivity owns one visible optical menu, while queued sources can remain pre-registered. */
internal object NativeRemoteBluRaySessionRegistry {
    private val mutableActiveSession = MutableStateFlow<HdmvDiscSession?>(null)
    val activeSession: StateFlow<HdmvDiscSession?> = mutableActiveSession.asStateFlow()

    private val mutableOverlay = MutableStateFlow<NativeBluRayOverlayFrame?>(null)
    val overlay: StateFlow<NativeBluRayOverlayFrame?> = mutableOverlay.asStateFlow()

    fun activate(session: HdmvDiscSession) {
        if (mutableActiveSession.value !== session) {
            mutableOverlay.value = null
            mutableActiveSession.value = session
        }
    }

    fun deactivate(session: HdmvDiscSession) {
        if (mutableActiveSession.value === session) {
            mutableOverlay.value = null
            mutableActiveSession.value = null
        }
    }

    fun updateOverlay(
        session: HdmvDiscSession,
        frame: NativeBluRayOverlayFrame?,
    ) {
        if (mutableActiveSession.value === session) mutableOverlay.value = frame
    }
}

/** Reflection keeps ordinary builds compatible with the stock AAR, which has no Yfuse registry. */
internal object NativeRemoteBluRayRegistry {
    private const val REGISTRY_CLASS = "dev.yfuse.mpv.YfuseBluRayRegistry"

    fun register(source: NativeRemoteBluRayBlockSource): Long? {
        if (!installedMpvNativeBuildCapabilities.remoteRawBluRay) return null
        return runCatching {
            val clazz = Class.forName(REGISTRY_CLASS, false, MpvVideoEngine::class.java.classLoader)
            val method = clazz.getMethod("register", Any::class.java)
            (method.invoke(null, source) as? Number)?.toLong()?.takeIf { it > 0L }
                ?.also(source::bindNativeId)
        }.getOrNull()
    }

    fun unregister(id: Long) {
        if (id <= 0L) return
        runCatching {
            val clazz = Class.forName(REGISTRY_CLASS, false, MpvVideoEngine::class.java.classLoader)
            clazz.getMethod("unregister", java.lang.Long.TYPE).invoke(null, id)
        }
    }

    fun selectAngle(
        id: Long,
        angle: Int,
    ): Boolean {
        if (id <= 0L || angle < 0 || !installedMpvNativeBuildCapabilities.remoteRawBluRay) return false
        return runCatching {
            val clazz = Class.forName(REGISTRY_CLASS, false, MpvVideoEngine::class.java.classLoader)
            clazz
                .getMethod("selectAngle", java.lang.Long.TYPE, Integer.TYPE)
                .invoke(null, id, angle) as? Boolean
        }.getOrNull() == true
    }

    fun sendMenuCommand(
        id: Long,
        command: Int,
    ): Boolean {
        if (id <= 0L || !installedMpvNativeBuildCapabilities.hdmvMenu) return false
        return runCatching {
            val clazz = Class.forName(REGISTRY_CLASS, false, MpvVideoEngine::class.java.classLoader)
            clazz
                .getMethod("sendMenuCommand", java.lang.Long.TYPE, Integer.TYPE)
                .invoke(null, id, command) as? Boolean
        }.getOrNull() == true
    }

    fun selectMenuPoint(
        id: Long,
        x: Int,
        y: Int,
        activate: Boolean,
    ): Boolean {
        if (id <= 0L || !installedMpvNativeBuildCapabilities.hdmvMenu) return false
        return runCatching {
            val clazz = Class.forName(REGISTRY_CLASS, false, MpvVideoEngine::class.java.classLoader)
            clazz
                .getMethod(
                    "selectMenuPoint",
                    java.lang.Long.TYPE,
                    Integer.TYPE,
                    Integer.TYPE,
                    java.lang.Boolean.TYPE,
                ).invoke(null, id, x, y, activate) as? Boolean
        }.getOrNull() == true
    }
}

private fun PlaybackDiscMenuCommand.nativeMenuCode(): Int =
    when (this) {
        PlaybackDiscMenuCommand.ShowMenu -> 0
        PlaybackDiscMenuCommand.Back -> 1
        PlaybackDiscMenuCommand.Up -> 2
        PlaybackDiscMenuCommand.Down -> 3
        PlaybackDiscMenuCommand.Left -> 4
        PlaybackDiscMenuCommand.Right -> 5
        PlaybackDiscMenuCommand.Select -> 6
    }
