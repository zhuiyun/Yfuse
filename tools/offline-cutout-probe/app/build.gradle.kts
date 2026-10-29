import java.util.Properties
plugins { id("com.android.application") }
val probeVersion = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
check(rootProject.file("release-notes.txt").readLines().first() == probeVersion.getProperty("VERSION_NAME"))
android {
    namespace = "com.yfuse.offlineprobe"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig {
        applicationId = probeVersion.getProperty("APPLICATION_ID")
        minSdk = 24
        targetSdk = 37
        versionCode = probeVersion.getProperty("VERSION_CODE").toInt()
        versionName = probeVersion.getProperty("VERSION_NAME")
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    splits { abi { isEnable = true; reset(); include("arm64-v8a", "x86"); isUniversalApk = false } }
    androidResources { noCompress += listOf("tflite") }
}
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
}
