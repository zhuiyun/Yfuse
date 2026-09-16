import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.serialization)
}

kotlin {
    android {
        namespace = "com.yfuse.shared"
        compileSdk { version = release(37) { minorApiLevel = 0 } }
        minSdk = 26
        androidResources { enable = true }
        withHostTest { isReturnDefaultValues = true }
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        localDependencySelection { selectBuildTypeFrom.set(listOf("debug", "release")) }
    }
    sourceSets {
        all { languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi") }
        commonMain {
            kotlin.srcDir("../composeApp/src/commonMain/kotlin")
            dependencies {
                api(project(":watchTogetherProtocol"))
                api(compose.runtime)
                api(compose.foundation)
                api(compose.material3)

                api(libs.decompose)
                api(libs.decompose.compose)
                api(libs.androidx.navigation3.runtime)
                api(libs.androidx.navigation3.ui)
                api(libs.mvikotlin)
                api(libs.mvikotlin.main)
                api(libs.mvikotlin.coroutines)

                api(libs.ktor.core)
                api(libs.ktor.content.negotiation)
                api(libs.ktor.json)
                api(libs.ktor.encoding)
                api(libs.ktor.client.websockets)

                api(libs.serialization.json)
                api(libs.coroutines.core)
                api(libs.koin.core)
                api(libs.settings)

                api(libs.coil.compose)
                api(libs.coil.network.ktor)
            }
        }
        androidMain {
            kotlin.srcDirs("../composeApp/src/androidMain/kotlin")
            dependencies {
                api(project.dependencies.platform(libs.okhttp.bom))
                compileOnly(project(":mdkAndroid"))
                compileOnly(files("../composeApp/libs/libmpv-release.aar"))
                compileOnly(libs.ktor.okhttp)
                compileOnly(libs.okhttp)
                compileOnly(libs.jcifs.ng)
                compileOnly(libs.play.services.cronet)
                compileOnly(libs.androidx.activity.compose)
                compileOnly(libs.androidx.camera.core)
                compileOnly(libs.androidx.camera.camera2)
                compileOnly(libs.androidx.camera.lifecycle)
                compileOnly(libs.androidx.camera.view)
                compileOnly(libs.media3.common)
                compileOnly(libs.media3.database)
                compileOnly(libs.media3.datasource)
                compileOnly(libs.media3.exoplayer)
                compileOnly(libs.media3.ui)
                compileOnly(libs.media3.hls)
                compileOnly(libs.media3.dash)
                compileOnly(libs.androidx.palette)
                compileOnly(libs.androidx.work.runtime)
                compileOnly(libs.zxing.core)
                compileOnly(libs.google.cast.framework)
                compileOnly(libs.google.cast.tv)
                compileOnly(libs.androidx.media)
                compileOnly(libs.androidx.metrics.performance)
                compileOnly(libs.androidx.profileinstaller)
                compileOnly(libs.bouncycastle.provider)
            }
        }
        commonTest {
            kotlin.srcDir("../composeApp/src/commonTest/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.coroutines.test)
                implementation(libs.turbine)
                implementation(libs.ktor.mock)
                implementation(libs.settings.test)
            }
        }
        getByName("androidHostTest") {
            kotlin.srcDirs("../composeApp/src/androidUnitTest/kotlin")
            dependencies {
                runtimeOnly(
                    files(
                        providers.provider {
                            tasks
                                .named("generateAndroidMainRFile")
                                .get()
                                .outputs.files
                        },
                    ).builtBy("generateAndroidMainRFile"),
                )
                implementation(libs.ktor.okhttp)
                implementation(libs.okhttp)
                implementation(libs.jcifs.ng)
                implementation(libs.play.services.cronet)
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.camera.core)
                implementation(libs.androidx.camera.camera2)
                implementation(libs.androidx.camera.lifecycle)
                implementation(libs.androidx.camera.view)
                implementation(libs.media3.common)
                implementation(libs.media3.database)
                implementation(libs.media3.datasource)
                implementation(libs.media3.exoplayer)
                implementation(libs.media3.ui)
                implementation(libs.media3.hls)
                implementation(libs.media3.dash)
                implementation(libs.androidx.palette)
                implementation(libs.androidx.work.runtime)
                implementation(libs.zxing.core)
                implementation(libs.google.cast.framework)
                implementation(libs.google.cast.tv)
                implementation(libs.androidx.media)
                implementation(libs.androidx.metrics.performance)
                implementation(libs.androidx.profileinstaller)
                implementation(libs.bouncycastle.provider)
                implementation(project(":mdkAndroid"))
                implementation(files("../composeApp/libs/libmpv-release.aar"))

                implementation(kotlin("test"))
                implementation(libs.coroutines.test)
                implementation(libs.turbine)
                implementation(libs.ktor.mock)
                implementation(libs.settings.test)

                implementation(libs.okhttp.mockwebserver)
                implementation(libs.okhttp.tls)
            }
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addStaticSourceDirectory("../composeApp/src/androidMain/res")
    }
}

tasks.withType<Test>().configureEach {
    workingDir(rootProject.file("composeApp"))
}

tasks.matching { it.name.startsWith("test") }.configureEach {
    dependsOn(":composeApp:verifyBehavioralTestBoundaries")
}
