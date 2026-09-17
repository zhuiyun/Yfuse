rootProject.name = "Yfuse"

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
                includeGroup("org.chromium.net")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Every module declares `jvmToolchain(17)`. Without a toolchain resolver a machine (or CI
    // image) that lacks a local JDK 17 fails at configuration time instead of provisioning one.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
                includeGroup("org.chromium.net")
            }
        }
        mavenCentral()
    }
}

include(":composeApp")
// App shells own packaging; KMP libraries own the existing shared source trees.
include(":phoneShared")
include(":tvShared")
include(":tvApp")
include(":macrobenchmark")
include(":mdkAndroid")
include(":watchTogetherProtocol")
include(":watchTogetherServer")
