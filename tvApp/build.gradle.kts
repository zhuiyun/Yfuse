import org.gradle.api.GradleException
import org.gradle.api.provider.ProviderFactory
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.serialization)
}

/*
 * P0 sharing boundary
 * -------------------
 * composeApp is an application module and cannot be an implementation dependency of a second
 * application. Turning it into an AAR here would also break its direct local AAR dependencies and
 * every existing mobile release task. tvApp instead compiles the already-separated commonMain and
 * androidMain source trees as one Android Kotlin compilation. -Xmulti-platform pairs expect/actual
 * declarations, while namespace=com.yfuse preserves their R and BuildConfig ABI. The TV manifest
 * remains wholly independent, so camera/updater permissions and components cannot leak from source.
 * Stable domain and player packages can move to ordinary library modules incrementally later.
 */

fun ProviderFactory.strictBooleanProperty(name: String): Boolean =
    gradleProperty(name).orNull?.let { raw ->
        when (raw.trim().lowercase()) {
            "", "true" -> true
            "false" -> false
            else -> error("$name must be omitted, true, or false")
        }
    } ?: false

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
    } ?: storedTvVersionCode
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
    // Shared source imports com.yfuse.BuildConfig/R directly; this namespace is an ABI contract.
    namespace = "com.yfuse"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yfuse"
        minSdk = 26
        targetSdk = 36
        versionCode = tvVersionCode
        versionName = tvVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "TMDB_TOKEN", "\"$tmdbToken\"")
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
            res.srcDirs(
                "src/main/res",
                rootProject.file("composeApp/src/androidMain/res"),
            )
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

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xmulti-platform")
    }
    sourceSets.named("main") {
        kotlin.srcDirs(
            rootProject.file("composeApp/src/commonMain/kotlin"),
            rootProject.file("composeApp/src/androidMain/kotlin"),
            layout.projectDirectory.dir("src/main/kotlin"),
        )
    }
    sourceSets.named("test") {
        kotlin.srcDirs(
            rootProject.file("composeApp/src/commonTest/kotlin/com/yfuse/tv"),
            rootProject.file("composeApp/src/androidUnitTest/kotlin/com/yfuse/tv"),
            layout.projectDirectory.dir("src/test/kotlin"),
        )
    }
}

dependencies {
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
    implementation(libs.ktor.okhttp)
    implementation(libs.ktor.content.negotiation)
    implementation(libs.ktor.json)
    implementation(libs.ktor.encoding)
    implementation(libs.ktor.client.websockets)
    implementation(libs.okhttp)
    implementation(libs.jcifs.ng)
    implementation(libs.play.services.cronet)

    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.koin.core)
    implementation(libs.settings)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor)
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

    // TV-only surfaces and system rows; phone Compose Material remains unchanged in :composeApp.
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.tvprovider)

    // Phone-only implementation source is compiled for a single source of truth but never exposed
    // by the TV manifest. compileOnly prevents camera/QR/MDK runtimes entering the TV artifact.
    compileOnly(project(":mdkAndroid"))
    compileOnly(libs.androidx.camera.core)
    compileOnly(libs.androidx.camera.camera2)
    compileOnly(libs.androidx.camera.lifecycle)
    compileOnly(libs.androidx.camera.view)
    compileOnly(libs.zxing.core)
    compileOnly(files(mpvCompileApi))

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)

    if (fullNativeRuntime) {
        implementation(files(ycoreAar))
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
        val expected = checksumFile.readText().trim().substringBefore(' ').lowercase()
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
