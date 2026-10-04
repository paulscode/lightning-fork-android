package com.paulscode.lightningfork

import android.app.Application

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Units, times and estimates in the phone's language (util/Format).
        com.paulscode.lightningfork.util.Format.res = resources
        container = AppContainer(this)
    }
}
