import java.io.File
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
}

val includeRuntime = providers.gradleProperty("yfuseIncludeSenPlayer").orNull == "true"
val runtimeDir = layout.projectDirectory.dir("runtime/arm64-v8a")
val runtimeHashes =
    mapOf(
        "libFFmpegKit.so" to "e6f26a53f8cf793cc1733ae4fbfed30a71794a2ca932c585048021e865aad1a3",
        "libc++_shared.so" to "f4e1e97c1943e60311e47e8b024d78f5b3b7229b3ccc65feb33af83d6025a670",
    )
if (includeRuntime) {
    for ((name, expected) in runtimeHashes) {
        val binary = runtimeDir.file(name).asFile
        require(binary.isFile) { "Missing SenPlayer runtime: $name. Run scripts/import-senplayer-runtime.py first." }
        val digest = MessageDigest.getInstance("SHA-256")
        binary.inputStream().use { input ->
            val buffer = ByteArray(65536)
            var count = input.read(buffer)
            while (count >= 0) {
                if (count > 0) digest.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(actual == expected) { "SenPlayer runtime checksum mismatch: $name" }
    }
}

android {
    enableKotlin = false
    namespace = "com.yfuse.senplayer.bridge"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig {
        minSdk = 26
        buildConfigField("boolean", "RUNTIME_INCLUDED", includeRuntime.toString())
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures { buildConfig = true }
    sourceSets.getByName("main").jniLibs.setSrcDirs(
        if (includeRuntime) listOf(layout.projectDirectory.dir("runtime").asFile) else emptyList<File>(),
    )
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
