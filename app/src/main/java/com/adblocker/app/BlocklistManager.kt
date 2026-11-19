package com.adblocker.app

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object BlocklistManager {
    private const val PREFS = "adblocker"
    private const val KEY_URL = "blocklist_url"
    private const val DEFAULT_URL =
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private const val FILE_NAME = "blocklist.txt"

    private val blocked = HashSet<String>()

    fun url(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL

    fun setUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_URL, url).apply()
    }

    fun load(context: Context) {
        blocked.clear()
        // bundled asset
        try {
            context.assets.open("blocklist.txt").bufferedReader().use { parse(it.readText()) }
        } catch (_: Exception) {
        }
        // downloaded
        val f = File(context.filesDir, FILE_NAME)
        if (f.exists()) {
            try { parse(f.readText()) } catch (_: Exception) { }
        }
        // always block a small built-in set
        listOf("doubleclick.net", "googleadservices.com", "googlesyndication.com",
            "adservice.google.com", "pagead2.googlesyndication.com").forEach { blocked.add(it) }
    }

    fun size(): Int = blocked.size

    fun isBlocked(host: String): Boolean {
        val h = host.trimEnd('.').lowercase()
        if (h in blocked) return true
        // subdomain check
        var idx = h.indexOf('.')
        while (idx >= 0 && idx < h.length - 1) {
            if (h.substring(idx + 1) in blocked) return true
            idx = h.indexOf('.', idx + 1)
        }
        return false
    }

    private fun parse(text: String) {
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            // hosts format: "0.0.0.0 domain" or "127.0.0.1 domain"
            val parts = t.split(Regex("\\s+"))
            if (parts.size >= 2) {
                val ip = parts[0]
                if (ip == "0.0.0.0" || ip == "127.0.0.1") {
                    for (i in 1 until parts.size) {
                        val d = parts[i].lowercase()
                        if (d != "localhost" && d.contains('.')) blocked.add(d)
                    }
                    continue
                }
            }
            // domain-only lines
            if (!t.contains(" ") && t.contains('.')) {
                blocked.add(t.lowercase())
            }
        }
    }

    /** Returns true on success. */
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