package com.browns.cloudbuild

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: App
            private set
        val prefs: Prefs get() = Prefs.get(instance)
        val history: HistoryStore get() = HistoryStore.get(instance)
    }
}