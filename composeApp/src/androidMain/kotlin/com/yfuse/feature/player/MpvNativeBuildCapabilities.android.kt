package com.yfuse.feature.player

import com.yfuse.core.logging.AppLog

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
    /** `bd_open_files()` bridge for extracted BDMV directories / persisted SAF trees. */
    val bdmvVfs: Boolean = false,
    /** Native HDMV event/overlay menu runtime; title/chapter-only access does not set this. */
    val hdmvMenu: Boolean = false,
    /** Runtime JNI path for authored Blu-ray seamless-angle selection. */
    val multiAngle: Boolean = false,
    val libmpvAndroidRevision: String? = null,
    val libblurayRevision: String? = null,
    val libudfreadRevision: String? = null,
) {
    val nativeBluRay: Boolean get() = libbluray

    /** Exact native identity accepted for the first release gate; device/corpus gates remain separate. */
    val pinnedYfuseBluRayArtifact: Boolean
        get() =
            libbluray &&
                remoteRawBluRay &&
                bdmvVfs &&
                hdmvMenu &&
                multiAngle &&
                !bdj &&
                libmpvAndroidRevision == EXPECTED_LIBMPV_ANDROID_REVISION &&
                libblurayRevision == EXPECTED_LIBBLURAY_REVISION &&
                libudfreadRevision == EXPECTED_LIBUDFREAD_REVISION

    val description: String
        get() =
            when {
                libbluray && bdj -> "libbluray + BD-J"
                libbluray && hdmvMenu && remoteRawBluRay && bdmvVfs && multiAngle ->
                    "libbluray · HDMV · ISO/BDMV · 多视角"
                libbluray && hdmvMenu && remoteRawBluRay && bdmvVfs ->
                    "libbluray · HDMV · ISO/BDMV 原盘"
                libbluray && hdmvMenu && remoteRawBluRay -> "libbluray · HDMV · 远程原盘"
                libbluray && remoteRawBluRay -> "libbluray · 远程原盘（BD-J 未启用）"
                libbluray -> "libbluray（BD-J 未启用）"
                else -> "stock libmpv（无 libbluray）"
            }
}

internal val installedMpvNativeBuildCapabilities: MpvNativeBuildCapabilities by lazy {
    detectMpvNativeBuildCapabilities().also { capabilities ->
        AppLog.info(
            category = "player.native",
            event = "mpv_build_capabilities",
            message = "Detected installed MPV native build capabilities",
            attributes =
                mapOf(
                    "artifact" to if (capabilities.pinnedYfuseBluRayArtifact) "yfuse-bluray" else "stock-or-unknown",
                    "libbluray" to capabilities.libbluray.toString(),
                    "remoteRawBluRay" to capabilities.remoteRawBluRay.toString(),
                    "bdmvVfs" to capabilities.bdmvVfs.toString(),
                    "hdmvMenu" to capabilities.hdmvMenu.toString(),
                    "multiAngle" to capabilities.multiAngle.toString(),
                    "bdj" to capabilities.bdj.toString(),
                    "libmpvAndroidRevision" to capabilities.libmpvAndroidRevision.orEmpty(),
                    "libblurayRevision" to capabilities.libblurayRevision.orEmpty(),
                    "libudfreadRevision" to capabilities.libudfreadRevision.orEmpty(),
                ),
        )
    }
}

internal fun detectMpvNativeBuildCapabilities(
    className: String = YFUSE_MPV_CAPABILITY_CLASS,
    classLoader: ClassLoader? = MpvVideoEngine::class.java.classLoader,
): MpvNativeBuildCapabilities =
    runCatching {
        val marker = Class.forName(className, false, classLoader)
        MpvNativeBuildCapabilities(
            libbluray = marker.booleanField("LIBBLURAY"),
            bdj = marker.booleanField("BDJ"),
            remoteRawBluRay = marker.booleanField("REMOTE_RAW_BLURAY"),
            bdmvVfs = marker.booleanField("BDMV_VFS"),
            hdmvMenu = marker.booleanField("HDMV_MENU"),
            multiAngle = marker.booleanField("MULTI_ANGLE"),
            libmpvAndroidRevision = marker.stringField("LIBMPV_ANDROID_REVISION"),
            libblurayRevision = marker.stringField("LIBBLURAY_REVISION"),
            libudfreadRevision = marker.stringField("LIBUDFREAD_REVISION"),
        )
    }.getOrElse { MpvNativeBuildCapabilities() }

/** Missing newer fields on an older custom marker are safely treated as unsupported. */
private fun Class<*>.booleanField(name: String): Boolean =
    runCatching { getField(name).getBoolean(null) }.getOrDefault(false)

private fun Class<*>.stringField(name: String): String? =
    runCatching {
        getField(name)
            .get(null)
            ?.toString()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }.getOrNull()

internal const val YFUSE_MPV_CAPABILITY_CLASS = "dev.yfuse.mpv.YfuseMpvCapabilities"
internal const val EXPECTED_LIBMPV_ANDROID_REVISION = "fcf6745703dc1265bca88f12fee8fc355ddf251e"
internal const val EXPECTED_LIBBLURAY_REVISION = "7d94f2660af5bfc16015291a03539329135c18f1"
internal const val EXPECTED_LIBUDFREAD_REVISION = "139a2194525f2745b98a98e4d8fa627d07440176"
