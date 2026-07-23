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

            implementation(libs.serialization.json)
            implementation(libs.coroutines.core)
            implementation(libs.koin.core)
            implementation(libs.settings)

            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
        }

        androidMain.dependencies {
            implementation(libs.ktor.cio)
            implementation(libs.androidx.activity.compose)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.ui)
            implementation(libs.media3.hls)
            // Native engines fetched by scripts/fetch-engines.sh (gitignored).
            implementation(files("libs/libmpv-release.aar"))
            implementation(libs.androidx.palette)
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
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "TMDB_TOKEN", "\"$tmdbToken\"")

        // 64-bit only: drop the 32-bit armeabi-v7a / x86 ABIs.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
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
            // Debug-signed so `assembleRelease` produces an installable APK for
            // personal use. Replace with a real keystore before public release.
            signingConfig = signingConfigs.getByName("debug")
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
