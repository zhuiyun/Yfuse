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

/**
 * The app prefers the repository-pinned Yfuse libmpv build. If that immutable release asset
 * is temporarily unavailable, scripts/fetch-engines.sh may install the historically verified
 * upstream v1.0.0 fallback instead. The compose module's custom-artifact verifier is intentionally
 * strict, so teach it to skip only its custom-capability checks when the AAR exactly matches the
 * pinned stock digest and no Yfuse capability sidecars are present. Unknown AARs still fall through
 * to the original verifier and fail the build.
 */
gradle.projectsEvaluated {
    val composeProject = project(":composeApp")
    composeProject.tasks.named("verifyCustomMpvArtifact").configure {
        onlyIf("run custom MPV capability checks unless the verified stock fallback is installed") {
            val aarFile = composeProject.layout.projectDirectory.file("libs/libmpv-release.aar").asFile
            val customChecksum = composeProject.layout.projectDirectory.file("libs/libmpv-release.aar.sha256").asFile
            val customSources = composeProject.layout.projectDirectory.file("libs/libmpv-release.sources.txt").asFile
            val checksumManifest = rootProject.layout.projectDirectory.file("scripts/engine-checksums.sha256").asFile

            if (!aarFile.isFile || !checksumManifest.isFile) {
                true
            } else {
                val stockExpected =
                    checksumManifest
                        .readLines()
                        .firstOrNull { it.trim().endsWith("  libmpv-release-stock.aar") }
                        ?.trim()
                        ?.substringBefore(' ')
                        ?.lowercase()

                if (stockExpected.isNullOrBlank()) {
                    true
                } else {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    aarFile.inputStream().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (actual == stockExpected) {
                        if (customChecksum.exists() || customSources.exists()) {
                            throw org.gradle.api.GradleException(
                                "Verified stock libmpv fallback must not carry Yfuse custom capability sidecars",
                            )
                        }
                        logger.lifecycle(
                            "Verified stock libmpv fallback $actual; custom Blu-ray/YCore capability checks are disabled for this build",
                        )
                        false
                    } else {
                        true
                    }
                }
            }
        }
    }
}
