package com.adblocker.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import java.io.File
import kotlin.concurrent.thread

class AdBlockerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Restore the user's chosen palette before the first activity inflates.
        val darkMode = getSharedPreferences("adblocker", MODE_PRIVATE)
            .getBoolean("dark_mode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (darkMode) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
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
