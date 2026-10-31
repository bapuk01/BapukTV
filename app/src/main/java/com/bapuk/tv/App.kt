package com.bapuk.tv

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        LogoLoader.init(this)
        Presets.init(this)
    }
}
