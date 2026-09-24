import java.security.MessageDigest

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

// A build started from an uncommitted working tree must not claim to be the clean commit above:
// `git status --porcelain` says whether anything (tracked or untracked) differs from that commit,
// and `git diff HEAD` is hashed so two dirty builds from different local edits are distinguishable
// without embedding the (potentially large, potentially sensitive) diff text itself. Both run
// through providers.exec, the same configuration-cache-compatible pattern as buildRevision above.
val gitStatusPorcelain =
    runCatching {
        providers
            .exec {
                commandLine(
                    "git",
                    "-c",
                    "safe.directory=${rootProject.projectDir.invariantSeparatorsPath}",
                    "-C",
                    rootProject.projectDir.absolutePath,
                    "status",
                    "--porcelain",
                )
                isIgnoreExitValue = true
            }.standardOutput.asText
            .get()
    }.getOrDefault("")
val isTreeDirty = gitStatusPorcelain.isNotBlank()

// Only computed when dirty: an unmodified tree has nothing to diff and no need to pay for it.
val diagnosticDiffHash =
    if (isTreeDirty) {
        val diffText =
            runCatching {
                providers
                    .exec {
                        commandLine(
                            "git",
                            "-c",
                            "safe.directory=${rootProject.projectDir.invariantSeparatorsPath}",
                            "-C",
                            rootProject.projectDir.absolutePath,
                            "diff",
                            "HEAD",
                        )
                        isIgnoreExitValue = true
                    }.standardOutput.asText
                    .get()
            }.getOrDefault("")
        MessageDigest
            .getInstance("SHA-256")
            .digest(diffText.toByteArray(Charsets.UTF_8))
            // Byte is signed, so formatting it directly with %02x sign-extends bytes >= 0x80
            // (0xFF would print as "ffffffff"); mask to the low 8 bits first.
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            .take(12)
    } else {
        null
    }

// Exposed next to yfuseDiagnosticBuildRevision for any consumer that wants the raw fields instead
// of parsing the combined string below (none currently do; both are Boolean/String, config-cache
// safe like the rest of this file).
extra["yfuseDiagnosticBuildTreeDirty"] = isTreeDirty
extra["yfuseDiagnosticBuildDiffHash"] = diagnosticDiffHash ?: ""

// The consumed value: composeApp/build.gradle.kts and tvApp/build.gradle.kts both read this same
// key into BuildConfig.BUILD_REVISION (a String) unchanged, so a dirty build now visibly differs
// from the clean commit it would otherwise claim to be, with no Kotlin-side change required.
extra["yfuseDiagnosticBuildRevision"] =
    if (isTreeDirty) "$diagnosticBuildRevision-dirty-$diagnosticDiffHash" else diagnosticBuildRevision
