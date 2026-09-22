package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core.playback.PlaybackDiscNavigationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ABI adapter for the native BDMV stream.
 *
 * `stream_yfuse_bdmv.c` deliberately reuses the battle-tested source/session registry from the ISO
 * bridge. That registry historically verifies a `readBlocksNative()` method at registration time.
 * BDMV trees do not have logical ISO blocks, so this proxy supplies an inert block method while
 * forwarding the real `bd_open_files()` filesystem callbacks to [NativeLocalBdmvSource].
 *
 * The proxy also adapts the newer native session-state ABI (title/chapter/angle/menu) to the older
 * BDMV source callback. Angle state is kept separately and composited onto the active HDMV session so
 * a large filesystem class does not need to duplicate native ABI glue.
 */
internal class NativeLocalBdmvRegistryProxy(
    private val source: NativeLocalBdmvSource,
) {
    @Volatile
    private var nativeId: Long = 0L

    fun bindNativeId(id: Long) {
        nativeId = id.takeIf { it > 0L } ?: 0L
    }

    @Suppress("unused")
    fun readBlocksNative(
        lba: Int,
        blockCount: Int,
        target: ByteArray,
        targetOffset: Int,
    ): Int = -1

    @Suppress("unused")
    fun openFileNative(relativePath: String): Long = source.openFileNative(relativePath)

    @Suppress("unused")
    fun readFileNative(
        handle: Long,
        target: ByteArray,
        targetOffset: Int,
        length: Int,
    ): Int = source.readFileNative(handle, target, targetOffset, length)

    @Suppress("unused")
    fun seekFileNative(
        handle: Long,
        offset: Long,
        origin: Int,
    ): Long = source.seekFileNative(handle, offset, origin)

    @Suppress("unused")
    fun tellFileNative(handle: Long): Long = source.tellFileNative(handle)

    @Suppress("unused")
    fun closeFileNative(handle: Long) = source.closeFileNative(handle)

    @Suppress("unused")
    fun openDirNative(relativePath: String): Long = source.openDirNative(relativePath)

    @Suppress("unused")
    fun readDirNative(handle: Long): String? = source.readDirNative(handle)

    @Suppress("unused")
    fun closeDirNative(handle: Long) = source.closeDirNative(handle)

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
        source.onNativeSessionState(
            titleCount,
            selectedTitleIndex,
            chapterCount,
            selectedChapterIndex,
            menuSupported,
            menuActive,
        )
        NativeLocalBdmvAngleRegistry.update(
            nativeId = nativeId,
            angleCount = angleCount,
            selectedAngleIndex = selectedAngleIndex,
        )
    }

    @Suppress("unused")
    fun onNativeOverlayFrame(
        width: Int,
        height: Int,
        argb: IntArray,
    ) = source.onNativeOverlayFrame(width, height, argb)

    @Suppress("unused")
    fun onNativeOverlayCleared() = source.onNativeOverlayCleared()

    @Suppress("unused")
    fun onNativeSessionClosed() {
        NativeLocalBdmvAngleRegistry.clear(nativeId)
        source.onNativeSessionClosed()
    }

    @Suppress("unused")
    fun closeNativeSource() {
        NativeLocalBdmvAngleRegistry.clear(nativeId)
        source.closeNativeSource()
    }
}

internal object NativeLocalBdmvProxyRegistry {
    private const val REGISTRY_CLASS = "dev.yfuse.mpv.YfuseBdmvRegistry"

    fun register(source: NativeLocalBdmvSource): Long? {
        if (!installedMpvNativeBuildCapabilities.bdmvVfs) return null
        val proxy = NativeLocalBdmvRegistryProxy(source)
        return runCatching {
            val clazz = Class.forName(REGISTRY_CLASS, false, MpvVideoEngine::class.java.classLoader)
            (clazz.getMethod("register", Any::class.java).invoke(null, proxy) as? Number)
                ?.toLong()
                ?.takeIf { it > 0L }
                ?.also { id ->
                    proxy.bindNativeId(id)
                    source.bindNativeId(id)
                }
        }.getOrNull()
    }
}

internal data class NativeLocalBdmvAngleBinding(
    val nativeId: Long,
    val angleCount: Int,
    val selectedAngleIndex: Int,
)

/** Process-local angle state published only by the active `yfusebdmv://` native session. */
internal object NativeLocalBdmvAngleRegistry {
    private const val REGISTRY_CLASS = "dev.yfuse.mpv.YfuseBdmvRegistry"
    private val mutableBinding = MutableStateFlow<NativeLocalBdmvAngleBinding?>(null)
    val binding: StateFlow<NativeLocalBdmvAngleBinding?> = mutableBinding.asStateFlow()

    fun update(
        nativeId: Long,
        angleCount: Int,
        selectedAngleIndex: Int,
    ) {
        if (nativeId <= 0L) return
        val count = angleCount.coerceAtLeast(0)
        val selected = selectedAngleIndex.coerceAtLeast(0).coerceAtMost((count - 1).coerceAtLeast(0))
        mutableBinding.value =
            NativeLocalBdmvAngleBinding(
                nativeId = nativeId,
                angleCount = count,
                selectedAngleIndex = selected,
            )
    }

    fun clear(nativeId: Long) {
        if (mutableBinding.value?.nativeId == nativeId) mutableBinding.value = null
    }

    fun selectAngle(
        nativeId: Long,
        angle: Int,
    ): Boolean {
        val current = mutableBinding.value ?: return false
        if (
            nativeId <= 0L ||
            current.nativeId != nativeId ||
            angle !in 0 until current.angleCount
        ) {
            return false
        }
        return runCatching {
            val clazz = Class.forName(REGISTRY_CLASS, false, MpvVideoEngine::class.java.classLoader)
            clazz
                .getMethod("selectAngle", java.lang.Long.TYPE, Integer.TYPE)
                .invoke(null, nativeId, angle) as? Boolean
        }.getOrNull() == true
    }
}

/** Adds authored angle state to the BDMV menu session without changing title/chapter ownership. */
internal class BdmvAngleHdmvSession(
    private val delegate: HdmvDiscSession,
    private val bindingProvider: () -> NativeLocalBdmvAngleBinding?,
) : HdmvDiscSession {
    override fun navigation(): PlaybackDiscNavigationState {
        val base = delegate.navigation()
        val angle = bindingProvider()
        return if (angle == null) {
            base
        } else {
            base.copy(
                angleCount = angle.angleCount,
                selectedAngleIndex = angle.selectedAngleIndex,
            )
        }
    }

    override fun selectTitle(index: Int): Boolean = delegate.selectTitle(index)

    override fun selectChapter(index: Int): Boolean = delegate.selectChapter(index)

    override fun selectAngle(index: Int): Boolean {
        val binding = bindingProvider() ?: return false
        return NativeLocalBdmvAngleRegistry.selectAngle(binding.nativeId, index)
    }

    override fun sendMenuCommand(command: PlaybackDiscMenuCommand): Boolean = delegate.sendMenuCommand(command)

    override fun selectMenuPoint(
        x: Int,
        y: Int,
        activate: Boolean,
    ): Boolean = delegate.selectMenuPoint(x, y, activate)

    override fun setNavigationChangedListener(listener: (() -> Unit)?) {
        delegate.setNavigationChangedListener(listener)
    }

    override fun close() {
        delegate.close()
    }
}
