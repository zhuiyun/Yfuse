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
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
}

application {
    mainClass.set("com.yfuse.watch.ApplicationKt")
}
