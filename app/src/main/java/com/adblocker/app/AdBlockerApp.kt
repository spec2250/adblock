package com.adblocker.app

import android.app.Application
import kotlin.concurrent.thread

class AdBlockerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Parse on a background thread so the UI never freezes.
        thread(name = "blocklist-loader") { BlocklistManager.load(this) }
    }
}