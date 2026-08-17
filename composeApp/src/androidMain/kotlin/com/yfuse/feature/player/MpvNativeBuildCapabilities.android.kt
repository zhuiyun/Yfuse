package com.yfuse.feature.player

/**
 * Capabilities proven by the concrete AAR installed in composeApp/libs.
 *
 * The stock upstream AAR has no marker class and therefore resolves to all-false. The custom Yfuse
 * native build embeds the marker only after its build script has verified `HAVE_LIBBLURAY=1`.
 */
internal data class MpvNativeBuildCapabilities(
    val libbluray: Boolean = false,
    val bdj: Boolean = false,
    val libmpvAndroidRevision: String? = null,
    val libblurayRevision: String? = null,
    val libudfreadRevision: String? = null,
) {
    val nativeBluRay: Boolean get() = libbluray

    val description: String
        get() =
            when {
                libbluray && bdj -> "libbluray + BD-J"
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
            libbluray = marker.getField("LIBBLURAY").getBoolean(null),
            bdj = marker.getField("BDJ").getBoolean(null),
            libmpvAndroidRevision = marker.stringField("LIBMPV_ANDROID_REVISION"),
            libblurayRevision = marker.stringField("LIBBLURAY_REVISION"),
            libudfreadRevision = marker.stringField("LIBUDFREAD_REVISION"),
        )
    }.getOrElse { MpvNativeBuildCapabilities() }

private fun Class<*>.stringField(name: String): String? =
    getField(name).get(null)?.toString()?.trim()?.takeIf(String::isNotEmpty)

internal const val YFUSE_MPV_CAPABILITY_CLASS = "dev.yfuse.mpv.YfuseMpvCapabilities"
