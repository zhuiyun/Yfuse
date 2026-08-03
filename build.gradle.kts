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
 * The rule set is deliberately narrow. ktlint's defaults include opinions this codebase has
 * already decided against on purpose, and a linter that has to be argued with gets turned
 * off. What is kept is the mechanical half: indentation, import order, trailing commas,
 * blank lines — the things that produce diff noise when they drift and that nobody should
 * be spending review attention on.
 */
val ktlintVersion = libs.versions.ktlint.asProvider()

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(ktlintVersion)
        // Reports, don't fail the build. Formatting is worth knowing about and never worth
        // blocking a release for; `ktlintFormat` fixes what it finds.
        ignoreFailures.set(true)
        filter {
            // Generated sources are nobody's to format.
            exclude { it.file.path.contains("/build/") }
        }
    }
}
