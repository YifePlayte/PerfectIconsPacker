package io.github.perfecticons

import android.app.Application
import io.github.perfecticons.data.net.ProxyConfig
import io.github.perfecticons.data.settings.SettingsRepository

class PerfectIconsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val settings = SettingsRepository(this)
        ProxyConfig.apply(settings.proxy)
    }
}
