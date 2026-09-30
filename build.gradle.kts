plugins {
    alias(libs.plugins.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.android.kmp.library) apply false
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
 * There is no baseline: the recorded debt was cleared, and every violation fails the check.
 * The old per-module baselines matched on file, line, column and rule, so an entry left
 * behind by a fixed violation silently excused a new one that landed on the same spot.
 */
val ktlintVersion = libs.versions.ktlint.asProvider()

/**
 * Every forced dependency version lives in scripts/security-overrides.properties: the root
 * resolution strategy and the supply-chain scanner read the same file, so the SBOM reports
 * what the build resolves. Nothing is pinned inline here.
 */
val securityOverrides =
    java.util.Properties().apply {
        rootProject.file("scripts/security-overrides.properties").inputStream().use { load(it) }
    }
val nativeOnlyRuntimeRequested =
    providers
        .gradleProperty("yfuseNativeOnlyRuntime")
        .orNull
        ?.trim()
        ?.lowercase() in setOf("", "true")
val sharedComposeSourceRoot =
    rootProject
        .file("composeApp/src")
        .toPath()
        .toAbsolutePath()
        .normalize()

/**
 * Compose stability, configured once for every module that compiles composables.
 *
 * config/compose-stability.conf names the immutable library types composables may treat as
 * stable; the file itself says what may and may not go in it. Compiler reports and metrics
 * (build/compose-compiler/ in each module: `*-classes.txt` for class stability, `*-composables.txt`
 * for skippability) are opt-in with `-PyfuseComposeReports=true`: writing them changes every
 * Compose compile's arguments, so ordinary and CI builds keep their cache hits.
 */
val composeStabilityConfiguration = layout.projectDirectory.file("config/compose-stability.conf")
val composeReportsRequested =
    providers
        .gradleProperty("yfuseComposeReports")
        .orNull
        ?.trim()
        ?.lowercase() in setOf("", "true")

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    plugins.withId("org.jetbrains.kotlin.plugin.compose") {
        extensions.configure<org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension> {
            stabilityConfigurationFiles.add(composeStabilityConfiguration)
            if (composeReportsRequested) {
                reportsDestination.set(layout.buildDirectory.dir("compose-compiler/reports"))
                metricsDestination.set(layout.buildDirectory.dir("compose-compiler/metrics"))
            }
        }
    }

    configurations.configureEach {
        resolutionStrategy.eachDependency {
            val securityOverride = securityOverrides.getProperty("${requested.group}:${requested.name}")
            if (securityOverride != null) {
                useVersion(securityOverride)
                because("Pinned security override shared with the supply-chain scanner")
            }
        }
    }

    // AGP resolves lint tools through detached configurations, which are not members of
    // configurations and therefore do not receive the resolution strategy above.
    dependencies.components.all {
        allVariants {
            withDependencies {
                forEach { dependency ->
                    val securityOverride = securityOverrides.getProperty("${dependency.group}:${dependency.name}")
                    if (securityOverride != null) {
                        dependency.version { require(securityOverride) }
                    }
                }
            }
        }
    }

    dependencyLocking {
        // Security-overridden modules are reproducibly pinned by security-overrides.properties,
        // so an older committed lock entry must not veto the centrally forced patched version.
        securityOverrides.stringPropertyNames().forEach { ignoredDependencies.add(it) }
        if (nativeOnlyRuntimeRequested) {
            // The pure profile deliberately removes locked compatibility runtimes. LENIENT still
            // pins every dependency that remains resolved while allowing those stale entries out.
            lockMode.set(org.gradle.api.artifacts.dsl.LockMode.LENIENT)
        }
        lockAllConfigurations()
    }

    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(ktlintVersion)
        ignoreFailures.set(false)
        filter {
            // Generated sources are nobody's to format.
            exclude { it.file.path.contains("/build/") }
            // tvShared also compiles the phone KMP trees. phoneShared owns their ktlint
            // tasks; only TV-specific sources are checked here.
            if (project.name == "tvShared") {
                exclude { element ->
                    element.file
                        .toPath()
                        .toAbsolutePath()
                        .normalize()
                        .startsWith(sharedComposeSourceRoot)
                }
            }
        }
    }
}
