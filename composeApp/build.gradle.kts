import org.gradle.api.GradleException
import org.gradle.api.tasks.options.Option
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.serialization)
}

/**
 * Keeps feature code on the semantic design-system surface. These are deliberately simple
 * source checks: they catch the exact escape hatches that caused the audit drift while the
 * compiler and unit tests cover behaviour and token math.
 */
val verifyDesignSystemUsage by tasks.registering {
    group = "verification"
    description = "Rejects raw UI typography, radii, and fixed functional colours."
    val designSources =
        fileTree("src/commonMain/kotlin/com/yfuse") {
            include("app/App.kt", "core/designsystem/**/*.kt", "feature/**/*.kt")
            // These files define the low-level primitives or scale type from runtime geometry.
            exclude(
                "core/designsystem/ContinuousCorner.kt",
                "core/designsystem/SemanticTypography.kt",
                "core/designsystem/Tokens.kt",
                "core/designsystem/WatchAvatar.kt",
            )
        }
    inputs.files(designSources)

    doLast {
        val sourceRules =
            listOf(
                "literal raw typography" to Regex("""\b(?:sc|mr)\(\s*\d"""),
                "legacy Type typography" to Regex("""\bType\.\w+\("""),
                "direct continuous radius" to Regex("""continuousRounded\("""),
                "fixed danger colour" to Regex("""Brand\.Danger"""),
                "legacy fixed-blue shadow" to Regex("""Shadows\.primaryButton(?!\s*\()"""),
                "literal tween duration" to Regex("""\btween\(\s*\d"""),
                "uncontrolled content-size animation" to Regex("""animateContentSize\(\s*\)"""),
                // Scan the source occurrence itself, not only a same-line `color =` assignment:
                // otherwise `val tint = Brand.Primary` and multiline arguments bypass the guard.
                "fixed interactive brand colour" to Regex("""\bBrand\.Primary\b"""),
            )
        val violations =
            buildList {
                designSources.files.sortedBy { it.path }.forEach { source ->
                    val original = source.readText()
                    // Preserve newlines while masking comments/imports so multiline calls are
                    // checked and diagnostics still point at the original source line.
                    val scanned =
                        Regex("""(?s)/\*.*?\*/|//[^\r\n]*|(?m)^\s*import\b[^\r\n]*""")
                            .replace(original) { match ->
                                buildString(match.value.length) {
                                    match.value.forEach { char ->
                                        append(if (char == '\r' || char == '\n') char else ' ')
                                    }
                                }
                            }
                    val originalLines = original.lines()
                    sourceRules.forEach { (label, pattern) ->
                        pattern.findAll(scanned).forEach { match ->
                            val lineNumber =
                                scanned
                                    .take(match.range.first)
                                    .count { it == '\n' } + 1
                            val originalLine = originalLines.getOrElse(lineNumber - 1) { "" }
                            val explicitlyBrandIdentity =
                                "design-system: brand-identity" in originalLine
                            if (
                                !(label == "fixed interactive brand colour" && explicitlyBrandIdentity)
                            ) {
                                add("${source.relativeTo(projectDir)}:$lineNumber: $label")
                            }
                        }
                    }
                }
            }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Design-system contract violations:\n" + violations.joinToString("\n"),
            )
        }
    }
}

val verifyCustomMpvArtifact by tasks.registering {
    group = "verification"
    description = "Rejects stock or unverified libmpv AARs before Android compilation or packaging."
    val aar = layout.projectDirectory.file("libs/libmpv-release.aar")
    val checksum = layout.projectDirectory.file("libs/libmpv-release.aar.sha256")
    val sources = layout.projectDirectory.file("libs/libmpv-release.sources.txt")
    val pinnedChecksum = layout.projectDirectory.file("../scripts/engine-checksums.sha256")
    inputs.files(aar, checksum, sources, pinnedChecksum)

    doLast {
        val aarFile = aar.asFile
        val checksumFile = checksum.asFile
        val sourcesFile = sources.asFile
        require(aarFile.isFile) { "Missing native player artifact: ${aarFile.path}" }
        require(checksumFile.isFile && sourcesFile.isFile) {
            "libmpv must include Yfuse SHA-256 and native-source sidecars; run scripts/fetch-engines.sh"
        }
        val pinnedLines = pinnedChecksum.asFile.readLines().map(String::trim)
        val accepted =
            pinnedLines
                .filter { line ->
                    line.endsWith("  libmpv-release.aar") ||
                        line.endsWith("  libmpv-dolby-release.aar") ||
                        line.endsWith("  libmpv-stable-release.aar")
                }.map { it.substringBefore(' ').lowercase() }
                .toSet()
        require(accepted.isNotEmpty()) { "Pinned Yfuse libmpv checksums are missing" }
        val dolbyChecksum =
            pinnedLines
                .firstOrNull { it.endsWith("  libmpv-dolby-release.aar") }
                ?.substringBefore(' ')
                ?.lowercase()
        val sidecar =
            checksumFile
                .readText()
                .trim()
                .substringBefore(' ')
                .lowercase()
        val digest = MessageDigest.getInstance("SHA-256")
        aarFile.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(sidecar == actual && actual in accepted) {
            "Unverified libmpv AAR: accepted=${accepted.sorted()} sidecar=$sidecar actual=$actual"
        }
        val provenance = sourcesFile.readText()
        listOf(
            "remote-raw-bluray=true",
            "bdmv-vfs=true",
            "hdmv-menu=true",
            "multi-angle=true",
            "capability-class=dev/yfuse/mpv/YfuseMpvCapabilities.class",
            "ycore-demux=true",
            "ycore-demux-source=scripts/native/ycore_demux_jni.cpp",
        ).forEach { marker -> require(marker in provenance) { "libmpv provenance is missing $marker" } }
        if (actual == dolbyChecksum) {
            listOf(
                "ycore-demux-ffmpeg=b79d4c4c0a160fc46988e98505af6039a53ad53e",
                "dolby-vision-rpu=true",
                "dolby-vision-fel=true",
                "ffmpeg-dovi-split=true",
                "libplacebo-enhancement-layer=true",
                "dolby-renderer=gpu-next",
                "dolby-runtime-jni=true",
            ).forEach { marker ->
                require(marker in provenance) { "Dolby libmpv provenance is missing $marker" }
            }
        } else {
            require("ycore-demux-ffmpeg=n8.1" in provenance) {
                "Stable libmpv provenance is missing ycore-demux-ffmpeg=n8.1"
            }
        }
        ZipFile(aarFile).use { archive ->
            require(archive.getEntry("jni/arm64-v8a/libycore_demux.so") != null) {
                "libmpv AAR is missing arm64-v8a/libycore_demux.so; build and install the YCore native artifact"
            }
        }
    }
}

val verifyBehavioralTestBoundaries by tasks.registering {
    group = "verification"
    description = "Rejects tests that assert production source text instead of behavior."
    val testSources =
        fileTree("src") {
            include("**/*Test.kt")
        }
    inputs.files(testSources)

    doLast {
        val forbidden =
            Regex(
                """src/(?:commonMain|androidMain)|\bprojectFile\s*\(|\bsourceRoot\b""",
            )
        val violations =
            testSources.files
                .sortedBy { it.path }
                .flatMap { source ->
                    source.readLines().mapIndexedNotNull { index, line ->
                        if (forbidden.containsMatchIn(line)) {
                            "${source.relativeTo(projectDir)}:${index + 1}"
                        } else {
                            null
                        }
                    }
                }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Tests must exercise public/internal behavior instead of production source text:\n" +
                    violations.joinToString("\n"),
            )
        }
    }
}

tasks.matching { it.name == "check" }.configureEach {
    dependsOn(verifyDesignSystemUsage)
    dependsOn(verifyBehavioralTestBoundaries)
}

tasks.matching { it.name.startsWith("test", ignoreCase = true) }.configureEach {
    dependsOn(verifyBehavioralTestBoundaries)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
        }
        commonMain.dependencies {
            implementation(project(":watchTogetherProtocol"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)

            implementation(libs.decompose)
            implementation(libs.decompose.compose)
            implementation(libs.androidx.navigation3.runtime)
            implementation(libs.androidx.navigation3.ui)
            implementation(libs.mvikotlin)
            implementation(libs.mvikotlin.main)
            implementation(libs.mvikotlin.coroutines)

            implementation(libs.ktor.core)
            implementation(libs.ktor.content.negotiation)
            implementation(libs.ktor.json)
            implementation(libs.ktor.encoding)
            implementation(libs.ktor.client.websockets)

            implementation(libs.serialization.json)
            implementation(libs.coroutines.core)
            implementation(libs.koin.core)
            implementation(libs.settings)

            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
        }

        androidMain.dependencies {
            implementation(project(":mdkAndroid"))
            implementation(libs.ktor.okhttp)
            implementation(libs.okhttp)
            implementation(libs.jcifs.ng)
            implementation(libs.play.services.cronet)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.camera.core)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.ui)
            implementation(libs.media3.hls)
            implementation(libs.media3.dash)
            // Native engines fetched by scripts/fetch-engines.sh (gitignored).
            implementation(files("libs/libmpv-release.aar"))
            implementation(libs.androidx.palette)
            implementation(libs.androidx.work.runtime)
            implementation(libs.zxing.core)
            implementation(libs.google.cast.framework)
            implementation(libs.androidx.metrics.performance)
            implementation(libs.androidx.profileinstaller)
        }

        androidUnitTest.dependencies {
            implementation(libs.okhttp.mockwebserver)
            implementation(libs.okhttp.tls)
        }

        androidInstrumentedTest.dependencies {
            implementation(libs.androidx.test.junit)
            implementation(libs.androidx.test.runner)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.ktor.mock)
            implementation(libs.settings.test)
        }
    }
}

// TMDB token comes from local.properties (gitignored) so it never lands in git.
val tmdbToken: String =
    Properties()
        .apply {
            val file = rootProject.file("local.properties")
            if (file.exists()) file.inputStream().use { load(it) }
        }.getProperty("tmdb.token")
        .orEmpty()

val releaseSigningPropertiesFile =
    providers
        .gradleProperty("releaseSigningPropertiesFile")
        .orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { rootProject.file(it) }
        ?: rootProject.file("keystore.properties")
val releaseSigningProperties =
    Properties().apply {
        if (releaseSigningPropertiesFile.exists()) {
            releaseSigningPropertiesFile.inputStream().use { load(it) }
        }
    }
val releaseSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val releaseStoreFile =
    releaseSigningProperties
        .getProperty("storeFile")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { rootProject.file(it) }
val releaseSigningReady =
    releaseStoreFile?.isFile == true &&
        releaseSigningKeys
            .filterNot { it == "storeFile" }
            .all { !releaseSigningProperties.getProperty(it).isNullOrBlank() }
val allowDebugSigning =
    providers.gradleProperty("allowDebugSigning").orNull?.let { raw ->
        when (raw.trim().lowercase()) {
            "", "true" -> true
            "false" -> false
            else -> error("allowDebugSigning must be omitted, true, or false")
        }
    } ?: false
val signDeviceTestsWithReleaseKey =
    providers.gradleProperty("signDeviceTestsWithReleaseKey").orNull?.let { raw ->
        when (raw.trim().lowercase()) {
            "", "true" -> true
            "false" -> false
            else -> error("signDeviceTestsWithReleaseKey must be omitted, true, or false")
        }
    } ?: false
if (signDeviceTestsWithReleaseKey) {
    require(releaseSigningReady) {
        "signDeviceTestsWithReleaseKey requires a complete release signing configuration"
    }
}

val versionFile = rootProject.file("version.properties")
val versionProperties =
    Properties().apply {
        require(versionFile.isFile) { "Missing release metadata: $versionFile" }
        versionFile.inputStream().use { load(it) }
    }
val versionCodePattern = Regex("[1-9]\\d*")
val versionNamePattern = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
val applicationIdPattern = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")
val storedVersionCodeRaw =
    versionProperties
        .getProperty("VERSION_CODE")
        ?.trim()
        ?: error("VERSION_CODE is required in version.properties")
require(storedVersionCodeRaw.matches(versionCodePattern)) {
    "VERSION_CODE in version.properties must be a positive integer"
}
val storedVersionCode =
    storedVersionCodeRaw.toIntOrNull()
        ?: error("VERSION_CODE in version.properties is outside the supported integer range")
val storedVersionName =
    versionProperties
        .getProperty("VERSION_NAME")
        ?.trim()
        ?: error("VERSION_NAME is required in version.properties")
require(storedVersionName.matches(versionNamePattern)) {
    "VERSION_NAME in version.properties must use numeric major.minor.patch format"
}
val requestedVersionCode =
    providers.gradleProperty("yfuseVersionCode").orNull?.let { rawValue ->
        require(rawValue.matches(versionCodePattern)) {
            "yfuseVersionCode must be a positive integer"
        }
        rawValue.toIntOrNull() ?: error("yfuseVersionCode is outside the supported integer range")
    }
val requestedVersionName =
    providers.gradleProperty("yfuseVersionName").orNull?.let { rawValue ->
        val normalized = rawValue.trim()
        require(normalized.matches(versionNamePattern)) {
            "yfuseVersionName must use numeric major.minor.patch format"
        }
        normalized
    }
val buildVersionCode = requestedVersionCode ?: storedVersionCode
val buildVersionName = requestedVersionName ?: storedVersionName
val buildApplicationId =
    providers.gradleProperty("yfuseApplicationId").orNull?.let { rawValue ->
        val normalized = rawValue.trim()
        require(normalized.matches(applicationIdPattern)) {
            "yfuseApplicationId must be a lowercase dotted Android application id; received '$normalized'"
        }
        normalized
    } ?: "com.yfuse"

android {
    namespace = "com.yfuse"
    // API 36 is the release baseline. Predictive back remains explicitly opted out in the
    // manifest by product decision while the rest of the Android 16 behavior is supported.
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        // Every androidx and Material artifact ships its own strings in ~80 locales. This
        // app's own copy is written in Chinese and its only translated strings come from
        // those libraries, so the rest is weight no device here can reach. The unqualified
        // (English) resources are always kept, which is what anything outside this list
        // falls back to.
        localeFilters += listOf("zh", "zh-rCN", "zh-rTW", "zh-rHK", "en")
    }

    defaultConfig {
        applicationId = buildApplicationId
        minSdk = 26
        targetSdk = 36
        versionCode = buildVersionCode
        versionName = buildVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "TMDB_TOKEN", "\"$tmdbToken\"")

        // Physical ARM64 devices only. Emulator and all 32-bit ABIs are excluded.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = requireNotNull(releaseStoreFile)
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (signDeviceTestsWithReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig =
                if (releaseSigningReady) {
                    signingConfigs.getByName("release")
                } else if (allowDebugSigning) {
                    logger.warn(
                        "Release keystore is missing; explicit -PallowDebugSigning is using the debug key.",
                    )
                    signingConfigs.getByName("debug")
                } else {
                    // The verification task below fails every release packaging path. Leaving the
                    // config unset here keeps ordinary debug/test configuration usable.
                    null
                }
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        // The bundled MPV/FFmpeg libraries account for most of the APK. Store
        // them deflated so sideload and update packages stay compact; Android
        // extracts them on install on our minSdk 26 devices.
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "**/libc++_shared.so"
        }
        resources {
            excludes +=
                setOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "/META-INF/*.version",
                    "/META-INF/*.kotlin_module",
                    "/META-INF/versions/**",
                    "DebugProbesKt.bin",
                    "kotlin-tooling-metadata.json",
                )
        }
    }
}

val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "Rejects release packaging without production signing or explicit local opt-in."
    doLast {
        if (!releaseSigningReady && !allowDebugSigning) {
            throw GradleException(
                "Release signing is not fully configured. Provide keystore.properties or " +
                    "use -PallowDebugSigning only for a non-distributable local build.",
            )
        }
    }
}

abstract class BumpVersionTask : DefaultTask() {
    @get:Internal
    abstract val versionFile: RegularFileProperty

    @get:Input
    @get:Option(
        option = "version-name",
        description = "New numeric major.minor.patch version name (required).",
    )
    abstract val versionName: Property<String>

    @get:Input
    @get:Optional
    @get:Option(
        option = "version-code",
        description = "New positive version code; defaults to the current code plus one.",
    )
    abstract val versionCode: Property<String>

    @TaskAction
    fun bump() {
        val target = versionFile.asFile.get()
        val current =
            Properties().apply {
                require(target.isFile) { "Missing release metadata: $target" }
                target.inputStream().use { load(it) }
            }
        val currentCode =
            current
                .getProperty("VERSION_CODE")
                ?.trim()
                ?.takeIf { it.matches(Regex("[1-9]\\d*")) }
                ?.toIntOrNull()
                ?: throw GradleException("VERSION_CODE must be a positive integer")
        val nextCode =
            if (versionCode.isPresent) {
                val raw = versionCode.get().trim()
                if (!raw.matches(Regex("[1-9]\\d*"))) {
                    throw GradleException("--version-code must be a positive integer")
                }
                raw.toIntOrNull()
                    ?: throw GradleException("--version-code is outside the supported integer range")
            } else {
                try {
                    Math.addExact(currentCode, 1)
                } catch (_: ArithmeticException) {
                    throw GradleException("VERSION_CODE cannot be incremented beyond Int.MAX_VALUE")
                }
            }
        if (nextCode <= currentCode) {
            throw GradleException(
                "--version-code must be greater than the current VERSION_CODE ($currentCode)",
            )
        }
        val nextName =
            versionName.orNull?.trim()
                ?: throw GradleException("--version-name is required")
        if (!nextName.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) {
            throw GradleException("--version-name must use numeric major.minor.patch format")
        }

        val temporary = target.resolveSibling("${target.name}.tmp")
        temporary.writeText(
            "# Release metadata; changing this file triggers the production publish workflow.\n" +
                "VERSION_CODE=$nextCode\n" +
                "VERSION_NAME=$nextName\n",
        )
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        logger.lifecycle("Updated version.properties to $nextName ($nextCode)")
    }
}

tasks.register<BumpVersionTask>("bumpVersion") {
    group = "release"
    description = "Explicitly validates and updates version.properties; never runs during assembly."
    versionFile.set(layout.projectDirectory.file("../version.properties"))
}

tasks.configureEach {
    if (name == "preBuild") {
        dependsOn(verifyCustomMpvArtifact)
    }
    val releasePackagingTask =
        name.contains("Release", ignoreCase = true) &&
            listOf("assemble", "bundle", "package").any { name.startsWith(it, ignoreCase = true) }
    if (releasePackagingTask) {
        dependsOn(verifyReleaseSigning)
    }
}
