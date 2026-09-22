package com.yfuse.update

/** Archive metadata is checked against both the installed app and the accepted update manifest. */
internal data class UpdatePackageIdentity(
    val packageName: String,
    val versionCode: Long,
    val signerDigests: Set<String>,
)

internal fun isExpectedUpdatePackage(
    installed: UpdatePackageIdentity,
    candidate: UpdatePackageIdentity?,
    expectedVersionCode: Int,
): Boolean =
    candidate != null &&
        installed.packageName.isNotBlank() &&
        candidate.packageName == installed.packageName &&
        expectedVersionCode > 0 &&
        candidate.versionCode == expectedVersionCode.toLong() &&
        candidate.versionCode > installed.versionCode &&
        installed.signerDigests.isNotEmpty() &&
        candidate.signerDigests.isNotEmpty() &&
        candidate.signerDigests.all { it in installed.signerDigests }
