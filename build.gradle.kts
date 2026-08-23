plugins {
    alias(libs.plugins.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.ktlint) apply false
}

/**
 * Formatting, checked by a machine rather than by whoever reviews the diff.
 *
 * Applied to every subproject from here so a new module is covered the day it is created —
 * the one thing a per-module `apply` reliably gets wrong.
 *
 * Existing debt is recorded in config/ktlint/<module>-baseline.xml. Checks therefore fail
 * only when a change introduces a new violation; `ktlintGenerateBaseline` is an explicit
 * debt-reset operation and must never run automatically in CI.
 */
val ktlintVersion = libs.versions.ktlint.asProvider()
val secureNettyVersion = "4.1.136.Final"
val secureProtobufVersion = "3.25.5"
val secureWireVersion = "6.3.0"
val secureBouncyCastleVersion = "1.80.2"

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configurations.configureEach {
        resolutionStrategy.eachDependency {
            when {
                requested.group == "io.netty" && requested.version.orEmpty().startsWith("4.1.") -> {
                    useVersion(secureNettyVersion)
                    because("Netty versions before 4.1.136.Final contain high-severity DoS vulnerabilities")
                }
                requested.group == "com.google.protobuf" &&
                    requested.version.orEmpty().startsWith("3.") &&
                    requested.name in
                    setOf(
                        "protobuf-java",
                        "protobuf-javalite",
                        "protobuf-kotlin",
                        "protobuf-kotlin-lite",
                    ) -> {
                    useVersion(secureProtobufVersion)
                    because("Protobuf versions before 3.25.5 allow unbounded recursion while parsing unknown fields")
                }
                requested.group == "com.squareup.wire" &&
                    requested.name in setOf("wire-runtime", "wire-runtime-jvm") -> {
                    useVersion(secureWireVersion)
                    because("Wire versions before 6.3.0 allow malformed groups to escape the documented decode failure path")
                }
                requested.group == "org.bouncycastle" && requested.name == "bcprov-jdk18on" -> {
                    useVersion(secureBouncyCastleVersion)
                    because("Bouncy Castle 1.76 is affected by GHSA-574f-3g2m-x479")
                }
            }
        }
    }

    dependencyLocking {
        // Refresh intentionally with `./gradlew dependencies --write-locks` whenever
        // dependency versions change.
        lockAllConfigurations()
    }

    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(ktlintVersion)
        ignoreFailures.set(false)
        baseline.set(rootProject.layout.projectDirectory.file("config/ktlint/$name-baseline.xml"))
        filter {
            // Generated sources are nobody's to format.
            exclude { it.file.path.contains("/build/") }
        }
    }
}
