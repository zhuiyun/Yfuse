package com.yfuse

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import com.russhwolf.settings.SharedPreferencesSettings
import com.yfuse.di.appModule
import org.koin.core.context.startKoin

class YfuseApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("yfuse", MODE_PRIVATE)
        val settings = SharedPreferencesSettings(prefs)
        startKoin {
            modules(appModule(settings))
        }
    }

    // Emby image endpoints are public, so a plain network-backed loader suffices.
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory()) }
            .crossfade(true)
            .build()
}
