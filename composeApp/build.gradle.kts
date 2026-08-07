import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.serialization)
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
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)

            implementation(libs.decompose)
            implementation(libs.decompose.compose)
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
            implementation(libs.ktor.cio)
            implementation(libs.androidx.activity.compose)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.ui)
            implementation(libs.media3.hls)
            // Native engines fetched by scripts/fetch-engines.sh (gitignored).
            implementation(files("libs/libmpv-release.aar"))
            implementation(libs.androidx.palette)
            implementation(libs.zxing.core)
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
val tmdbToken: String = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}.getProperty("tmdb.token").orEmpty()

val releaseSigningPropertiesFile = providers.gradleProperty("releaseSigningPropertiesFile")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.let { rootProject.file(it) }
    ?: rootProject.file("keystore.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.exists()) {
        releaseSigningPropertiesFile.inputStream().use { load(it) }
    }
}
val releaseSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val releaseStoreFile = releaseSigningProperties.getProperty("storeFile")
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.let { rootProject.file(it) }
val releaseSigningReady = releaseStoreFile?.isFile == true && releaseSigningKeys
    .filterNot { it == "storeFile" }
    .all { !releaseSigningProperties.getProperty(it).isNullOrBlank() }
val allowDebugSigning = providers.gradleProperty("allowDebugSigning").orNull?.let { raw ->
    when (raw.trim().lowercase()) {
        "", "true" -> true
        "false" -> false
        else -> error("allowDebugSigning must be omitted, true, or false")
    }
} ?: false

val versionFile = rootProject.file("version.properties")
val versionProperties = Properties().apply {
    versionFile.inputStream().use { load(it) }
}
val storedVersionCode = versionProperties.getProperty("VERSION_CODE", "1").toInt()
val storedVersionName = versionProperties.getProperty("VERSION_NAME", "0.1.$storedVersionCode")
val requestedVersionCode = providers.gradleProperty("yfuseVersionCode").orNull?.let { rawValue ->
    require(rawValue.matches(Regex("[1-9]\\d*"))) {
        "yfuseVersionCode must be a positive integer"
    }
    rawValue.toInt()
}
val requestedVersionName = providers.gradleProperty("yfuseVersionName").orNull?.let { rawValue ->
    val normalized = rawValue.trim()
    require(normalized.matches(Regex("""[0-9]+\.[0-9]+\.[0-9]+"""))) {
        "yfuseVersionName must use numeric major.minor.patch format"
    }
    normalized
}
val isReleaseBuild = gradle.startParameter.taskNames.any {
    it.contains("Release", ignoreCase = true) &&
        (it.contains("assemble", true) || it.contains("bundle", true) || it.contains("package", true))
}
val buildVersionCode = requestedVersionCode
    ?: if (isReleaseBuild) storedVersionCode + 1 else storedVersionCode
val buildVersionName = requestedVersionName ?: storedVersionName

android {
    namespace = "com.yfuse"
    // libmpv's AAR requires minCompileSdk 36; targetSdk stays at 35.
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
        applicationId = "com.yfuse"
        minSdk = 26
        targetSdk = 35
        versionCode = buildVersionCode
        versionName = buildVersionName

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
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (releaseSigningReady) {
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
            excludes += setOf(
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

tasks.configureEach {
    val releasePackagingTask = name.contains("Release", ignoreCase = true) &&
        listOf("assemble", "bundle", "package").any { name.startsWith(it, ignoreCase = true) }
    if (releasePackagingTask) {
        dependsOn(verifyReleaseSigning)
    }
    if (name == "assembleRelease") {
        doLast {
            if (
                requestedVersionCode == null &&
                isReleaseBuild &&
                storedVersionCode < buildVersionCode
            ) {
                versionFile.writeText(
                    "VERSION_CODE=$buildVersionCode\nVERSION_NAME=$buildVersionName\n",
                )
            }
        }
    }
}
