plugins {
    alias(libs.plugins.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
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

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

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
