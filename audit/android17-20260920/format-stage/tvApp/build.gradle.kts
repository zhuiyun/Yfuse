import org.gradle.api.GradleException
import org.gradle.api.provider.ProviderFactory
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.serialization)
}

// The TV shell owns signing, manifest, and runtime dependencies; :tvShared owns Kotlin sources.

fun ProviderFactory.strictBooleanProperty(name: String): Boolean =
    gradleProperty(name).orNull?.let { raw ->
        when (raw.trim().lowercase()) {
            "", "true" -> true
            "false" -> false
            else -> error("$name must be omitted, true, or false")
        }
    } ?: false

apply(from = rootProject.file("gradle/diagnostic-build.gradle.kts"))
val diagnosticBuildRevision = extra["yfuseDiagnosticBuildRevision"] as String

val versionProperties =
    Properties().apply {
        val versionFile = rootProject.file("version.properties")
        require(versionFile.isFile) { "Missing release metadata: $versionFile" }
        versionFile.inputStream().use { load(it) }
    }
val storedTvVersionCode =
    versionProperties
        .getProperty("VERSION_CODE")
        ?.trim()
        ?.takeIf { it.matches(Regex("[1-9]\\d*")) }
        ?.toIntOrNull()
        ?: error("VERSION_CODE must be a positive integer")
val storedTvVersionName =
    versionProperties
        .getProperty("VERSION_NAME")
        ?.trim()
        ?.takeIf { it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) }
        ?: error("VERSION_NAME must use numeric major.minor.patch format")
val tvVersionCode =
    providers.gradleProperty("yfuseVersionCode").orNull?.let { raw ->
        require(raw.matches(Regex("[1-9]\\d*"))) {
            "yfuseVersionCode must be a positive integer"
        }
        raw.toIntOrNull() ?: error("yfuseVersionCode is outside the supported integer range")
    } ?: tvVersionCodeFor(storedTvVersionCode)

/**
 * The television build shares the phone build's application id, and Play refuses two APKs of
 * one app with the same versionCode. Derived rather than read, so the default build never
 * collides with the phone package cut from the same version.properties; an explicit
 * -PyfuseVersionCode still wins.
 */
fun tvVersionCodeFor(phoneVersionCode: Int): Int = phoneVersionCode * 10 + 1

val tvVersionName =
    providers.gradleProperty("yfuseVersionName").orNull?.trim()?.let { value ->
        require(value.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) {
            "yfuseVersionName must use numeric major.minor.patch format"
        }
        value
    } ?: storedTvVersionName

val tmdbToken =
    Properties()
        .apply {
            val file = rootProject.file("local.properties")
            if (file.isFile) file.inputStream().use { load(it) }
        }.getProperty("tmdb.token")
        .orEmpty()
val updateManifestPublicKey =
    providers
        .gradleProperty("yfuse.updateManifestPublicKey")
        .map(String::trim)
        .filter(String::isNotEmpty)
        .orElse(providers.environmentVariable("YFUSE_UPDATE_MANIFEST_PUBLIC_KEY"))
        .getOrElse("")
        .trim()
val castReceiverApplicationId =
    providers
        .gradleProperty("yfuseCastReceiverApplicationId")
        .orNull
        ?.trim()
        ?.also { value ->
            require(value.matches(Regex("[A-Fa-f0-9]{8}"))) {
                "yfuseCastReceiverApplicationId must be an 8-character Cast application id"
            }
        }?.uppercase()
        ?: "E9107559"
require(castReceiverApplicationId == "E9107559") {
    "Android TV is locked to Cast receiver application E9107559"
}

val releaseSigningPropertiesFile =
    providers
        .gradleProperty("releaseSigningPropertiesFile")
        .orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(rootProject::file)
        ?: rootProject.file("keystore.properties")
val releaseSigningProperties =
    Properties().apply {
        if (releaseSigningPropertiesFile.isFile) {
            releaseSigningPropertiesFile.inputStream().use { load(it) }
        }
    }
val releaseStoreFile =
    releaseSigningProperties
        .getProperty("storeFile")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(rootProject::file)
val releaseSigningReady =
    releaseStoreFile?.isFile == true &&
        listOf("storePassword", "keyAlias", "keyPassword").all {
            !releaseSigningProperties.getProperty(it).isNullOrBlank()
        }
val allowDebugSigning = providers.strictBooleanProperty("allowDebugSigning")

// Default TV is the actual YCore system-native path: MediaExtractor + MediaCodec + AudioTrack.
// It contains no .so and therefore installs on arm64, armeabi-v7a and x86_64 UI-test devices.
// The optional full-native carrier is fail-closed and must contain both production TV ABIs.
val fullNativeRuntime = providers.strictBooleanProperty("yfuseTvFullNativeRuntime")
val mpvCompileApi = rootProject.layout.projectDirectory.file("composeApp/libs/libmpv-release.aar")
val ycoreAar = rootProject.layout.projectDirectory.file("composeApp/libs/ycore-native.aar")
val ycoreChecksum = rootProject.layout.projectDirectory.file("composeApp/libs/ycore-native.aar.sha256")
val ycoreSources = rootProject.layout.projectDirectory.file("composeApp/libs/ycore-native.sources.txt")

android {
    // The application shell owns variant-specific BuildConfig values.
    namespace = "com.yfuse"
    compileSdk { version = release(37) { minorApiLevel = 0 } }

    defaultConfig {
        applicationId = "com.yfuse"
        minSdk = 26
        targetSdk = 37
        versionCode = tvVersionCode
        versionName = tvVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BUILD_REVISION", "\"$diagnosticBuildRevision\"")
        buildConfigField("String", "TMDB_TOKEN", "\"$tmdbToken\"")
        buildConfigField("String", "UPDATE_MANIFEST_PUBLIC_KEY", "\"$updateManifestPublicKey\"")
        buildConfigField("boolean", "YFUSE_MDK_INCLUDED", "false")
        buildConfigField("boolean", "YFUSE_NATIVE_ONLY_RUNTIME", "true")
        buildConfigField("boolean", "YFUSE_YCORE_GPU_INCLUDED", fullNativeRuntime.toString())
        buildConfigField(
            "String",
            "YFUSE_CAST_RECEIVER_APPLICATION_ID",
            "\"$castReceiverApplicationId\"",
        )
        buildConfigField(
            "String",
            "YFUSE_PACKAGE_PROFILE",
            "\"${if (fullNativeRuntime) "tv-ycore-full-native" else "tv-ycore-system-native"}\"",
        )

        if (fullNativeRuntime) {
            ndk {
                abiFilters += setOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // Compile the established product implementation, but merge only tvApp's manifest. Keeping
    // the TV resources first lets a later TV-specific resource override remain explicit.
    sourceSets {
        getByName("main") {
            assets.directories += "../composeApp/src/androidMain/assets"
            manifest.srcFile("src/androidMain/AndroidManifest.xml")
            res.directories += "src/main/res"
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
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig =
                when {
                    releaseSigningReady -> signingConfigs.getByName("release")
                    allowDebugSigning -> signingConfigs.getByName("debug")
                    else -> null
                }
        }
    }

    androidResources {
        localeFilters += listOf("zh", "zh-rCN", "zh-rTW", "zh-rHK", "en")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
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

val verifyTvCompileApi by tasks.registering {
    group = "verification"
    description = "Requires the pinned compatibility API used to compile isolated legacy adapters."
    doLast {
        require(mpvCompileApi.asFile.isFile) {
            "Missing MPV compile API; run scripts/fetch-engines.sh. It remains compile-only in TV."
        }
    }
}

val verifyTvFullNativeRuntime by tasks.registering {
    group = "verification"
    description = "Verifies the optional full-native TV carrier and both production TV ABIs."
    inputs.files(ycoreAar, ycoreChecksum, ycoreSources)
    onlyIf { fullNativeRuntime }

    doLast {
        val aarFile = ycoreAar.asFile
        val checksumFile = ycoreChecksum.asFile
        val sourcesFile = ycoreSources.asFile
        require(aarFile.isFile && checksumFile.isFile && sourcesFile.isFile) {
            "Missing verified full-native YCore TV runtime"
        }
        val expected =
            checksumFile
                .readText()
                .trim()
                .substringBefore(' ')
                .lowercase()
        require(expected.matches(Regex("[0-9a-f]{64}"))) { "Invalid YCore SHA-256 sidecar" }
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
        require(actual == expected) { "YCore TV runtime SHA-256 mismatch" }
        ZipFile(aarFile).use { archive ->
            setOf("arm64-v8a", "armeabi-v7a").forEach { abi ->
                require(archive.getEntry("jni/$abi/libycore_demux.so") != null) {
                    "YCore TV runtime is missing $abi/libycore_demux.so"
                }
                require(archive.getEntry("jni/$abi/libycore_gpu.so") != null) {
                    "YCore TV runtime is missing $abi/libycore_gpu.so"
                }
            }
            val nativeNames =
                archive
                    .entries()
                    .asSequence()
                    .map { it.name.substringAfterLast('/') }
                    .filter { it.endsWith(".so") }
                    .toSet()
            require(nativeNames.intersect(setOf("libmpv.so", "libplayer.so", "libmdk.so")).isEmpty()) {
                "YCore TV runtime contains a forbidden compatibility player"
            }
        }
    }
}

val verifyTvReleaseProfile by tasks.registering {
    group = "verification"
    description = "Rejects unsigned Android TV release artifacts."
    doLast {
        if (!allowDebugSigning && updateManifestPublicKey.isNotBlank()) {
            val validUpdateKey =
                runCatching {
                    KeyFactory.getInstance("Ed25519").generatePublic(
                        X509EncodedKeySpec(
                            Base64
                                .getDecoder()
                                .decode(updateManifestPublicKey),
                        ),
                    )
                }.isSuccess
            check(validUpdateKey) { "Configured update-manifest public key must be valid Ed25519." }
        }
        if (!releaseSigningReady && !allowDebugSigning) {
            throw GradleException(
                "TV release signing is not configured. Provide keystore.properties or use " +
                    "-PallowDebugSigning only for a non-distributable local build.",
            )
        }
    }
}

tasks.configureEach {
    if (name == "preBuild") {
        dependsOn(verifyTvCompileApi)
        dependsOn(verifyTvFullNativeRuntime)
    }
    val releasePackagingTask =
        name.contains("Release", ignoreCase = true) &&
            listOf("assemble", "bundle", "package").any { name.startsWith(it, ignoreCase = true) }
    if (releasePackagingTask) {
        dependsOn(verifyTvCompileApi)
        dependsOn(verifyTvFullNativeRuntime)
        dependsOn(verifyTvReleaseProfile)
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(platform(libs.okhttp.bom))
    implementation(project(":tvShared"))

    implementation(libs.ktor.okhttp)
    implementation(libs.okhttp)
    implementation(libs.jcifs.ng)
    implementation(libs.play.services.cronet)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.hls)
    implementation(libs.media3.dash)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.work.runtime)
    implementation(libs.google.cast.framework)
    implementation(libs.google.cast.base)
    implementation(libs.google.cast.tv)
    implementation(libs.androidx.media)
    implementation(libs.androidx.metrics.performance)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.bouncycastle.provider)

    // TV system rows; the TV surfaces are hand-built on foundation, so tv-material
    // is not pulled in.
    implementation(libs.androidx.tvprovider)

    // Phone-only implementation source is compiled for a single source of truth but
    // never exposed by the TV manifest. compileOnly prevents camera/QR/MDK runtimes
    // entering the TV artifact.
    compileOnly(project(":mdkAndroid"))
    compileOnly(libs.androidx.camera.core)
    compileOnly(libs.androidx.camera.camera2)
    compileOnly(libs.androidx.camera.lifecycle)
    compileOnly(libs.androidx.camera.view)
    compileOnly(libs.zxing.core)
    compileOnly(files(mpvCompileApi))

    if (fullNativeRuntime) {
        implementation(files(ycoreAar))
    }
}
