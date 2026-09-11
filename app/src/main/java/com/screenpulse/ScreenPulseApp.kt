package com.screenpulse

import android.app.Application
import com.screenpulse.repository.SettingsRepository

class ScreenPulseApp : Application() {

    lateinit var settingsRepository: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
    }
}
