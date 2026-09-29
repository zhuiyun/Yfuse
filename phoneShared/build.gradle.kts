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

                api(libs.bundles.shared.common)
            }
        }
        androidMain {
            kotlin.srcDirs("../composeApp/src/androidMain/kotlin")
            dependencies {
                api(project.dependencies.platform(libs.okhttp.bom))
                compileOnly(project(":mdkAndroid"))
                compileOnly(files("../composeApp/libs/libmpv-release.aar"))
                compileOnly(libs.bundles.shared.android)
                compileOnly(libs.androidx.window)
                compileOnly(libs.media3.common)
                compileOnly(libs.media3.database)
                compileOnly(libs.media3.datasource)
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
                implementation(libs.bundles.shared.android)
                implementation(libs.media3.common)
                implementation(libs.media3.database)
                implementation(libs.media3.datasource)
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
