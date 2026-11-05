plugins {
    alias(libs.plugins.android.library)
}

android {
    enableKotlin = false
    namespace = "com.yfuse.senplayer"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
