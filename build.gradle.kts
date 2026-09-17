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
 * Existing debt is recorded in config/ktlint/<module>-baseline.xml. Checks therefore fail
 * only when a change introduces a new violation; `ktlintGenerateBaseline` is an explicit
 * debt-reset operation and must never run automatically in CI.
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

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configurations.configureEach {
        resolutionStrategy.eachDependency {
            val securityOverride = securityOverrides.getProperty("${requested.group}:${requested.name}")
            if (securityOverride != null) {
                useVersion(securityOverride)
                because("Pinned security override shared with the supply-chain scanner")
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
        baseline.set(rootProject.layout.projectDirectory.file("config/ktlint/$name-baseline.xml"))
        filter {
            // Generated sources are nobody's to format.
            exclude { it.file.path.contains("/build/") }
            // tvShared also compiles the phone KMP trees. phoneShared owns their ktlint
            // tasks and relocated baseline; only TV-specific sources are checked here.
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
