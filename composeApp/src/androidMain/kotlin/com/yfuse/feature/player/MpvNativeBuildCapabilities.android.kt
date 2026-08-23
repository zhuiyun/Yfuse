package com.yfuse.feature.player

import com.yfuse.core.logging.AppLog
import java.lang.reflect.Method

/** Capabilities proven by the concrete AAR installed in composeApp/libs. */
internal data class MpvNativeBuildCapabilities(
    val libbluray: Boolean = false,
    val bdj: Boolean = false,
    val remoteRawBluRay: Boolean = false,
    val bdmvVfs: Boolean = false,
    val hdmvMenu: Boolean = false,
    val multiAngle: Boolean = false,
    /** Build-time support. Per-file output claims still require runtime evidence. */
    val dolbyVisionRpu: Boolean = false,
    val dolbyVisionFel: Boolean = false,
    val libmpvAndroidRevision: String? = null,
    val libblurayRevision: String? = null,
    val libudfreadRevision: String? = null,
    val mpvCoreRevision: String? = null,
    val ffmpegRevision: String? = null,
    val libplaceboRevision: String? = null,
) {
    val nativeBluRay: Boolean get() = libbluray

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

    /** Exact native stack whose source/build gates include mpv's P7 FEL path and runtime trace. */
    val pinnedYfuseDolbyVisionArtifact: Boolean
        get() =
            pinnedYfuseBluRayArtifact &&
                dolbyVisionRpu &&
                dolbyVisionFel &&
                mpvCoreRevision == EXPECTED_MPV_CORE_REVISION &&
                ffmpegRevision == EXPECTED_FFMPEG_REVISION &&
                libplaceboRevision == EXPECTED_LIBPLACEBO_REVISION

    val dolbyVisionDescription: String
        get() =
            when {
                pinnedYfuseDolbyVisionArtifact -> "DV RPU + P7 FEL（运行时验证）"
                dolbyVisionFel -> "DV FEL（未匹配发布栈）"
                dolbyVisionRpu -> "DV RPU（未匹配发布栈）"
                else -> "DV 基础层/色调映射"
            }

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
                    "artifact" to
                        when {
                            capabilities.pinnedYfuseDolbyVisionArtifact -> "yfuse-dolby-fel"
                            capabilities.pinnedYfuseBluRayArtifact -> "yfuse-bluray"
                            else -> "stock-or-unknown"
                        },
                    "libbluray" to capabilities.libbluray.toString(),
                    "remoteRawBluRay" to capabilities.remoteRawBluRay.toString(),
                    "bdmvVfs" to capabilities.bdmvVfs.toString(),
                    "hdmvMenu" to capabilities.hdmvMenu.toString(),
                    "multiAngle" to capabilities.multiAngle.toString(),
                    "bdj" to capabilities.bdj.toString(),
                    "dolbyVisionRpu" to capabilities.dolbyVisionRpu.toString(),
                    "dolbyVisionFel" to capabilities.dolbyVisionFel.toString(),
                    "dolbyVisionGrade" to capabilities.dolbyVisionDescription,
                    "libmpvAndroidRevision" to capabilities.libmpvAndroidRevision.orEmpty(),
                    "mpvCoreRevision" to capabilities.mpvCoreRevision.orEmpty(),
                    "ffmpegRevision" to capabilities.ffmpegRevision.orEmpty(),
                    "libplaceboRevision" to capabilities.libplaceboRevision.orEmpty(),
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
        // Android may expose the AAR marker through the app/context loader before the concrete
        // engine class loader sees it. Trying every relevant loader prevents a transient startup
        // miss from being cached for the whole process as "stock-or-unknown".
        val candidateLoaders =
            listOfNotNull(
                classLoader,
                Thread.currentThread().contextClassLoader,
                MpvVideoEngine::class.java.classLoader,
                YFUSE_MPV_CAPABILITY_CLASS::class.java.classLoader,
            ).distinct()
        val marker =
            candidateLoaders.firstNotNullOfOrNull { loader ->
                runCatching { Class.forName(className, true, loader) }.getOrNull()
            } ?: Class.forName(className)
        val capabilities =
            MpvNativeBuildCapabilities(
                libbluray = marker.booleanField("LIBBLURAY"),
                bdj = marker.booleanField("BDJ"),
                remoteRawBluRay = marker.booleanField("REMOTE_RAW_BLURAY"),
                bdmvVfs = marker.booleanField("BDMV_VFS"),
                hdmvMenu = marker.booleanField("HDMV_MENU"),
                multiAngle = marker.booleanField("MULTI_ANGLE"),
                dolbyVisionRpu = marker.booleanField("DOLBY_VISION_RPU"),
                dolbyVisionFel = marker.booleanField("DOLBY_VISION_FEL"),
                libmpvAndroidRevision = marker.stringField("LIBMPV_ANDROID_REVISION"),
                libblurayRevision = marker.stringField("LIBBLURAY_REVISION"),
                libudfreadRevision = marker.stringField("LIBUDFREAD_REVISION"),
                mpvCoreRevision = marker.stringField("MPV_CORE_REVISION"),
                ffmpegRevision = marker.stringField("FFMPEG_REVISION"),
                libplaceboRevision = marker.stringField("LIBPLACEBO_REVISION"),
            )
        marker.installDolbyRuntimeEvidenceProvider(capabilities)
        capabilities
    }.getOrElse { error ->
        MpvDolbyRuntimeEvidenceRegistry.clearProvider()
        AppLog.warning(
            category = "player.native",
            event = "mpv_capability_marker_unavailable",
            message = "Native MPV capability marker could not be resolved; using conservative capabilities",
            throwable = error,
            attributes = mapOf("marker" to className),
        )
        MpvNativeBuildCapabilities()
    }

private fun Class<*>.installDolbyRuntimeEvidenceProvider(capabilities: MpvNativeBuildCapabilities) {
    if (!capabilities.pinnedYfuseDolbyVisionArtifact) {
        MpvDolbyRuntimeEvidenceRegistry.clearProvider()
        return
    }
    val generation = staticMethodOrNull("dolbyVisionRuntimeGeneration")
    val evidence = staticMethodOrNull("dolbyVisionRuntimeEvidence")
    if (generation == null || evidence == null) {
        MpvDolbyRuntimeEvidenceRegistry.clearProvider()
        return
    }
    MpvDolbyRuntimeEvidenceRegistry.installProvider {
        val generationValue = (generation.invoke(null) as? Number)?.toLong() ?: 0L
        val mask = (evidence.invoke(null) as? Number)?.toInt() ?: 0
        MpvDolbyRuntimeEvidence(
            generation = generationValue,
            rpuRendered = mask and DOLBY_EVIDENCE_RPU != 0,
            felComposed = mask and DOLBY_EVIDENCE_FEL != 0,
        )
    }
}

private fun Class<*>.staticMethodOrNull(name: String): Method? =
    runCatching { getMethod(name) }.getOrNull()

/** Missing fields/methods on older custom AARs are safely treated as unsupported. */
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

private const val DOLBY_EVIDENCE_RPU = 1
private const val DOLBY_EVIDENCE_FEL = 2
internal const val YFUSE_MPV_CAPABILITY_CLASS = "dev.yfuse.mpv.YfuseMpvCapabilities"
internal const val EXPECTED_LIBMPV_ANDROID_REVISION = "fcf6745703dc1265bca88f12fee8fc355ddf251e"
internal const val EXPECTED_LIBBLURAY_REVISION = "7d94f2660af5bfc16015291a03539329135c18f1"
internal const val EXPECTED_LIBUDFREAD_REVISION = "139a2194525f2745b98a98e4d8fa627d07440176"
internal const val EXPECTED_MPV_CORE_REVISION = "b955aa28f3dc93dc6b21485a0d5b7feb8e6dc10f"
internal const val EXPECTED_FFMPEG_REVISION = "b79d4c4c0a160fc46988e98505af6039a53ad53e"
internal const val EXPECTED_LIBPLACEBO_REVISION = "22ee762e8e0890fc54068beb670310f0edce7263"
