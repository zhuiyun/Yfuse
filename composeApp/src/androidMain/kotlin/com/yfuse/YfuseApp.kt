package com.yfuse

import android.app.Application
import com.russhwolf.settings.SharedPreferencesSettings
import com.yfuse.di.appModule
import org.koin.core.context.startKoin

class YfuseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("yfuse", MODE_PRIVATE)
        val settings = SharedPreferencesSettings(prefs)
        startKoin {
            modules(appModule(settings))
        }
    }
}
