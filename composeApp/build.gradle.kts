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
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)

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

compose.resources {
    packageOfResClass = "com.yfuse.resources"
}

// TMDB token comes from local.properties (gitignored) so it never lands in git.
val tmdbToken: String = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}.getProperty("tmdb.token").orEmpty()

val releaseSigningProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val releaseSigningReady = releaseSigningProperties.getProperty("storeFile")
    ?.let(rootProject::file)
    ?.exists() == true

val versionFile = rootProject.file("version.properties")
val storedVersionCode = Properties().apply {
    versionFile.inputStream().use { load(it) }
}.getProperty("VERSION_CODE", "1").toInt()
val isReleaseBuild = gradle.startParameter.taskNames.any {
    it.contains("Release", ignoreCase = true) &&
        (it.contains("assemble", true) || it.contains("bundle", true) || it.contains("package", true))
}
val buildVersionCode = if (isReleaseBuild) storedVersionCode + 1 else storedVersionCode

android {
    namespace = "com.yfuse"
    // libmpv's AAR requires minCompileSdk 36; targetSdk stays at 35.
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.yfuse"
        minSdk = 26
        targetSdk = 35
        versionCode = buildVersionCode
        versionName = "0.1.$buildVersionCode"

        buildConfigField("String", "TMDB_TOKEN", "\"$tmdbToken\"")

        // Physical ARM64 devices only. Emulator and all 32-bit ABIs are excluded.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = rootProject.file(releaseSigningProperties.getProperty("storeFile"))
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
            } else {
                logger.warn("Release keystore is missing; the APK will use the debug signing key.")
                signingConfigs.getByName("debug")
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

tasks.configureEach {
    if (name == "assembleRelease") {
        doLast {
            if (isReleaseBuild && storedVersionCode < buildVersionCode) {
                versionFile.writeText("VERSION_CODE=$buildVersionCode\n")
            }
        }
    }
}
