plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":watchTogetherProtocol"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.json)
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
}

application {
    mainClass.set("com.yfuse.watch.ApplicationKt")
}

// Stamps the commit into the jar so `/watch/version` can say what is actually deployed.
val gitSha: Provider<String> =
    providers
        .exec {
            workingDir = projectDir
            commandLine("git", "rev-parse", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText
        .map { it.trim().takeIf { sha -> sha.matches(Regex("[0-9a-f]{40}")) } ?: "unknown" }

tasks.processResources {
    inputs.property("gitSha", gitSha)
    filesMatching("build-info.properties") {
        expand("gitSha" to gitSha.get())
    }
}
