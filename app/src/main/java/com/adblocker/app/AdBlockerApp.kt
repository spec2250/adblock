package com.adblocker.app

import android.app.Application
import java.io.File
import kotlin.concurrent.thread

class AdBlockerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Parse the bundled list off the main thread so the UI never freezes.
        // Then refresh from the internet once a week (or on first launch).
        thread(name = "blocklist-loader") {
            BlocklistManager.load(this)
            try {
                val cached = File(filesDir, "blocklist.txt")
                val stale = !cached.exists() ||
                    System.currentTimeMillis() - cached.lastModified() > 7L * 24 * 60 * 60 * 1000
                if (stale && BlocklistManager.update(this)) BlocklistManager.load(this)
            } catch (_: Exception) {
            }
        }
    }
}
