package com.yfuse.feature.player

/**
 * ABI adapter for the native BDMV stream.
 *
 * `stream_yfuse_bdmv.c` deliberately reuses the battle-tested source/session registry from the ISO
 * bridge. That registry historically verifies a `readBlocksNative()` method at registration time.
 * BDMV trees do not have logical ISO blocks, so this proxy supplies an inert block method while
 * forwarding the real `bd_open_files()` filesystem callbacks to [NativeLocalBdmvSource].
 */
internal class NativeLocalBdmvRegistryProxy(
    private val source: NativeLocalBdmvSource,
) {
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
        menuSupported: Boolean,
        menuActive: Boolean,
    ) = source.onNativeSessionState(
        titleCount,
        selectedTitleIndex,
        chapterCount,
        selectedChapterIndex,
        menuSupported,
        menuActive,
    )

    @Suppress("unused")
    fun onNativeOverlayFrame(
        width: Int,
        height: Int,
        argb: IntArray,
    ) = source.onNativeOverlayFrame(width, height, argb)

    @Suppress("unused")
    fun onNativeOverlayCleared() = source.onNativeOverlayCleared()

    @Suppress("unused")
    fun onNativeSessionClosed() = source.onNativeSessionClosed()

    @Suppress("unused")
    fun closeNativeSource() = source.closeNativeSource()
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
                ?.also(source::bindNativeId)
        }.getOrNull()
    }
}
