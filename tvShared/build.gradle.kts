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
                api(project(":yfuseBackendClient"))

                api(compose.runtime)
                api(compose.foundation)
                api(compose.material3)

                api(libs.bundles.shared.common)
            }
        }
        androidMain {
            kotlin.srcDirs("../composeApp/src/androidMain/kotlin", "../tvApp/src/androidMain/kotlin")
            dependencies {
                api(project.dependencies.platform(libs.okhttp.bom))
                compileOnly(project(":mdkAndroid"))
                compileOnly(files("../composeApp/libs/libmpv-release.aar"))
                compileOnly(libs.bundles.shared.android)
                compileOnly(libs.androidx.window)
                compileOnly(libs.androidx.lifecycle.process)
                compileOnly(libs.google.cast.base)
                compileOnly(libs.androidx.tvprovider)
            }
        }

        getByName("androidHostTest") {
            kotlin.srcDirs(
                "../composeApp/src/androidUnitTest/kotlin/com/yfuse/tv",
                "../tvApp/src/androidUnitTest/kotlin",
            )
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
                implementation(libs.androidx.lifecycle.process)
                implementation(libs.google.cast.base)
                implementation(libs.androidx.tvprovider)
                implementation(project(":mdkAndroid"))
                implementation(files("../composeApp/libs/libmpv-release.aar"))

                implementation(kotlin("test"))
                implementation(libs.coroutines.test)
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
    workingDir(rootProject.file("tvApp"))
}
