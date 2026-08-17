package com.yfuse.feature.player

/**
 * Capabilities proven by the concrete AAR installed in composeApp/libs.
 *
 * The stock upstream AAR has no marker class and therefore resolves to all-false. The custom Yfuse
 * native build embeds the marker only after its build script has verified `HAVE_LIBBLURAY=1` and
 * the corresponding bridge sources were compiled into the same AAR.
 */
internal data class MpvNativeBuildCapabilities(
    val libbluray: Boolean = false,
    val bdj: Boolean = false,
    /** True only when the AAR contains the Yfuse `yfusebd://` + Java block-source bridge. */
    val remoteRawBluRay: Boolean = false,
    /** Native HDMV event/overlay menu runtime; title/chapter-only access does not set this. */
    val hdmvMenu: Boolean = false,
    val libmpvAndroidRevision: String? = null,
    val libblurayRevision: String? = null,
    val libudfreadRevision: String? = null,
) {
    val nativeBluRay: Boolean get() = libbluray

    val description: String
        get() =
            when {
                libbluray && bdj -> "libbluray + BD-J"
                libbluray && hdmvMenu && remoteRawBluRay -> "libbluray · HDMV · 远程原盘"
                libbluray && remoteRawBluRay -> "libbluray · 远程原盘（BD-J 未启用）"
                libbluray -> "libbluray（BD-J 未启用）"
                else -> "stock libmpv（无 libbluray）"
            }
}

internal val installedMpvNativeBuildCapabilities: MpvNativeBuildCapabilities by lazy {
    detectMpvNativeBuildCapabilities()
}

internal fun detectMpvNativeBuildCapabilities(
    className: String = YFUSE_MPV_CAPABILITY_CLASS,
    classLoader: ClassLoader = MpvVideoEngine::class.java.classLoader,
): MpvNativeBuildCapabilities =
    runCatching {
        val marker = Class.forName(className, false, classLoader)
        MpvNativeBuildCapabilities(
            libbluray = marker.booleanField("LIBBLURAY"),
            bdj = marker.booleanField("BDJ"),
            remoteRawBluRay = marker.booleanField("REMOTE_RAW_BLURAY"),
            hdmvMenu = marker.booleanField("HDMV_MENU"),
            libmpvAndroidRevision = marker.stringField("LIBMPV_ANDROID_REVISION"),
            libblurayRevision = marker.stringField("LIBBLURAY_REVISION"),
            libudfreadRevision = marker.stringField("LIBUDFREAD_REVISION"),
        )
    }.getOrElse { MpvNativeBuildCapabilities() }

/** Missing newer fields on an older custom marker are safely treated as unsupported. */
private fun Class<*>.booleanField(name: String): Boolean =
    runCatching { getField(name).getBoolean(null) }.getOrDefault(false)

private fun Class<*>.stringField(name: String): String? =
    runCatching { getField(name).get(null)?.toString()?.trim()?.takeIf(String::isNotEmpty) }.getOrNull()

internal const val YFUSE_MPV_CAPABILITY_CLASS = "dev.yfuse.mpv.YfuseMpvCapabilities"
