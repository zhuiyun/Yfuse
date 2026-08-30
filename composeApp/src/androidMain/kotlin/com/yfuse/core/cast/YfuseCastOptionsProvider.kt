package com.yfuse.core.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.LaunchOptions
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.yfuse.BuildConfig

internal fun configuredCastReceiverApplicationId(): String =
    BuildConfig.YFUSE_CAST_RECEIVER_APPLICATION_ID
        .trim()
        .takeIf(String::isNotEmpty)
        ?: CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID

internal fun hasYfuseCastReceiver(): Boolean = BuildConfig.YFUSE_CAST_RECEIVER_APPLICATION_ID.isNotBlank()

/** Defaults safely to Google's receiver; custom output evidence requires the configured Yfuse receiver. */
class YfuseCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions
            .Builder()
            .setReceiverApplicationId(configuredCastReceiverApplicationId())
            .setLaunchOptions(
                LaunchOptions
                    .Builder()
                    .setAndroidReceiverCompatible(true)
                    .build(),
            )
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
