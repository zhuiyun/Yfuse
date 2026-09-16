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
// App shells own packaging; KMP libraries own the existing shared source trees.
include(":phoneShared")
include(":tvShared")
include(":tvApp")
include(":macrobenchmark")
include(":mdkAndroid")
include(":watchTogetherProtocol")
include(":watchTogetherServer")
