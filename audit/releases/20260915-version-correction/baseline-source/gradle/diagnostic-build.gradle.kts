// A package version can be rebuilt from different commits. Keep the checked-out base revision
// in diagnostics; it does not claim that an artifact was built from an unmodified checkout.
val buildRevision =
    providers
        .gradleProperty("yfuseBuildRevision")
        .orElse(providers.environmentVariable("GITHUB_SHA"))
        .orNull
        ?: runCatching {
            providers
                .exec {
                    commandLine(
                        "git",
                        "-c",
                        "safe.directory=${rootProject.projectDir.invariantSeparatorsPath}",
                        "-C",
                        rootProject.projectDir.absolutePath,
                        "rev-parse",
                        "HEAD",
                    )
                    isIgnoreExitValue = true
                }.standardOutput.asText
                .get()
                .trim()
        }.getOrDefault("unknown")
val diagnosticBuildRevision =
    buildRevision.trim().takeIf { it.matches(Regex("[0-9a-fA-F]{7,64}")) }?.lowercase() ?: "unknown"

extra["yfuseDiagnosticBuildRevision"] = diagnosticBuildRevision
