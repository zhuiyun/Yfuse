package com.yfuse.core.platform

/** Variant values are supplied by the application before providers and startup code run. */
data class AppBuildValues(
    val DEBUG: Boolean,
    val APPLICATION_ID: String,
    val VERSION_NAME: String,
    val VERSION_CODE: Int,
    val BUILD_REVISION: String,
    val TMDB_TOKEN: String,
    val UPDATE_MANIFEST_PUBLIC_KEY: String,
    val YFUSE_MDK_INCLUDED: Boolean,
    val YFUSE_NATIVE_ONLY_RUNTIME: Boolean,
    val YFUSE_YCORE_GPU_INCLUDED: Boolean,
    val YFUSE_CAST_RECEIVER_APPLICATION_ID: String,
    val YFUSE_PACKAGE_PROFILE: String,
)

object AppBuildConfig {
    @Volatile
    private var installed: AppBuildValues? = null

    fun install(values: AppBuildValues) {
        check(installed == null || installed == values) { "Application build configuration cannot change in a process" }
        installed = values
    }

    private val values: AppBuildValues
        get() = checkNotNull(installed) { "Application must install build configuration in attachBaseContext" }

    val DEBUG: Boolean get() = values.DEBUG
    val APPLICATION_ID: String get() = values.APPLICATION_ID
    val VERSION_NAME: String get() = values.VERSION_NAME
    val VERSION_CODE: Int get() = values.VERSION_CODE
    val BUILD_REVISION: String get() = values.BUILD_REVISION
    val TMDB_TOKEN: String get() = values.TMDB_TOKEN
    val UPDATE_MANIFEST_PUBLIC_KEY: String get() = values.UPDATE_MANIFEST_PUBLIC_KEY

    // Preference defaults can query capabilities before Android startup (including host tests).
    // Never advertise an optional runtime until the application confirms it is packaged.
    val YFUSE_MDK_INCLUDED: Boolean get() = installed?.YFUSE_MDK_INCLUDED == true

    val YFUSE_NATIVE_ONLY_RUNTIME: Boolean get() = values.YFUSE_NATIVE_ONLY_RUNTIME
    val YFUSE_YCORE_GPU_INCLUDED: Boolean get() = values.YFUSE_YCORE_GPU_INCLUDED
    val YFUSE_CAST_RECEIVER_APPLICATION_ID: String get() = values.YFUSE_CAST_RECEIVER_APPLICATION_ID
    val YFUSE_PACKAGE_PROFILE: String get() = values.YFUSE_PACKAGE_PROFILE
}
