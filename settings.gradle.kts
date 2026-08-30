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
// Android TV is an independent application module on purpose. The existing Kotlin
// Multiplatform application is not a consumable Android library, and converting it in place
// would also make its local native AAR dependencies invalid inside an AAR. Keeping the TV
// shell separate preserves every existing mobile variant while the reusable domain/player
// surface is extracted behind stable APIs in later, reviewable changes.
include(":tvApp")
include(":macrobenchmark")
include(":mdkAndroid")
include(":watchTogetherProtocol")
include(":watchTogetherServer")
