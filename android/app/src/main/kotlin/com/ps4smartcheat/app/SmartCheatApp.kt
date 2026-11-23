package com.ps4smartcheat.app

import android.app.Application
import com.ps4smartcheat.app.data.AppContainer

class SmartCheatApp : Application() {
    lateinit var container: AppContainer
        private set
    lateinit var crashes: com.ps4smartcheat.app.data.CrashReports
        private set

    override fun onCreate() {
        super.onCreate()
        // First, so that even a failure while starting leaves a report for the next start.
        crashes = com.ps4smartcheat.app.data.CrashReports(filesDir, BuildConfig.VERSION_NAME).also { it.install() }
        container = AppContainer(this)
    }
}
