package com.ps4smartcheat.app

import android.app.Application
import com.ps4smartcheat.app.data.AppContainer

class SmartCheatApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
