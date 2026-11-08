package io.github.shiokunn.trpg

import android.app.Application

class TrpgApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
