plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.mediadevkit.sdk"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                cppFlags += "-std=c++17"
            }
        }

        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Both Full applications obtain the shared C++ runtime from the verified native
            // carrier. Do not let this bridge's NDK copy win the application's pickFirst rule:
            // that silently replaced the current, hash-verified carrier's runtime at packaging.
            // Keep linking dynamically; exclude only this library module's redundant copy.
            excludes += "**/libc++_shared.so"
        }
    }
}
