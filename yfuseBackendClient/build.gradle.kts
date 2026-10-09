plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val backendEnabled =
    providers
        .gradleProperty("yfuseBackendEnabled")
        .map {
            it.toBooleanStrictOrNull() ?: error("yfuseBackendEnabled must be true or false")
        }.orElse(true)
val generatedBackendConfig = layout.buildDirectory.dir("generated/backendConfig")
val generateBackendConfig =
    tasks.register("generateBackendConfig") {
        inputs.property("enabled", backendEnabled)
        outputs.dir(generatedBackendConfig)
        doLast {
            val output = generatedBackendConfig.get().file("com/yfuse/backend/BackendBuildConfig.kt").asFile
            output.parentFile.mkdirs()
            output.writeText(
                "package com.yfuse.backend\n\nobject BackendBuildConfig {\n    const val ENABLED: Boolean = ${backendEnabled.get()}\n}\n",
            )
        }
    }

kotlin {
    jvmToolchain(17)
    jvm()
    sourceSets {
        commonMain {
            kotlin.srcDir(generateBackendConfig)
            dependencies {
                api(project(":watchTogetherProtocol"))
                api(libs.ktor.core)
                implementation(libs.ktor.content.negotiation)
                implementation(libs.ktor.json)
                implementation(libs.ktor.client.websockets)
                implementation(libs.serialization.json)
                implementation(libs.coroutines.core)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.mock)
        }
    }
}
