package com.adblocker.app

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object BlocklistManager {
    private const val PREFS = "adblocker"
    private const val KEY_URL = "blocklist_url"
    private const val DEFAULT_URL =
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private const val FILE_NAME = "blocklist.txt"

    // Volatile reference so the VPN worker thread always sees the latest list.
    @Volatile
    private var blocked: Set<String> = emptySet()

    fun url(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL

    fun setUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_URL, url).apply()
    }

    fun load(context: Context) {
        val set = ConcurrentHashMap.newKeySet<String>()
        try {
            context.assets.open("blocklist.txt").bufferedReader().use { parse(it.readText(), set) }
        } catch (_: Exception) { }
        val f = File(context.filesDir, FILE_NAME)
        if (f.exists()) {
            try { parse(f.readText(), set) } catch (_: Exception) { }
        }
        listOf("doubleclick.net", "googleadservices.com", "googlesyndication.com",
            "adservice.google.com", "pagead2.googlesyndication.com").forEach { set.add(it) }
        blocked = set
    }

    fun isLoaded(): Boolean = blocked.isNotEmpty()
    fun size(): Int = blocked.size

    fun isBlocked(host: String): Boolean {
        val h = host.trimEnd('.').lowercase()
        val list = blocked
        if (h in list) return true
        var idx = h.indexOf('.')
        while (idx >= 0 && idx < h.length - 1) {
            if (h.substring(idx + 1) in list) return true
            idx = h.indexOf('.', idx + 1)
        }
        return false
    }

    private fun parse(text: String, set: MutableSet<String>) {
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val parts = t.split(Regex("\\s+"))
            if (parts.size >= 2) {
                val ip = parts[0]
                if (ip == "0.0.0.0" || ip == "127.0.0.1") {
                    for (i in 1 until parts.size) {
                        val d = parts[i].lowercase()
                        if (d != "localhost" && d.contains('.')) set.add(d)
                    }
                    continue
                }
            }
            if (!t.contains(" ") && t.contains('.')) set.add(t.lowercase())
        }
    }

    fun update(context: Context): Boolean {
        return try {
            val conn = URL(url(context)).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            File(context.filesDir, FILE_NAME).writeText(text)
            load(context)
            true
        } catch (_: Exception) {
            false
        }
    }
}