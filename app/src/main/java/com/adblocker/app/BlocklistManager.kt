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

    // Domains that must never be blocked — breaking apps is worse than showing an ad.
    // These are essential infrastructure (push notifications, sign-in, app updates).
    private val builtinWhitelist = setOf(
        "googleapis.com", "gstatic.com", "googleusercontent.com",
        "android.clients.google.com", "play.googleapis.com", "gvt1.com",
        "android.googleapis.com", "fcm.googleapis.com", "gcm.googleapis.com",
        "firebaseio.com", "firebase.com", "crashlytics.com",
        "apple.com", "icloud.com", "microsoft.com", "windowsupdate.com",
        "mozilla.org", "mozilla.com", "samsung.com"
    )

    @Volatile
    private var userWhitelist: Set<String> = emptySet()
    private const val KEY_WHITELIST = "whitelist"

    fun url(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL

    fun setUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_URL, url).apply()
    }

    fun load(context: Context) {
        // Read whitelist FIRST so the filter below uses the latest user entries.
        userWhitelist = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()

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

        // Remove anything the user or we consider essential.
        set.removeIf { isWhitelisted(it) }
        blocked = set
    }

    fun isLoaded(): Boolean = blocked.isNotEmpty()
    fun size(): Int = blocked.size

    /** True if [host] itself or one of its parent domains is whitelisted. */
    private fun isWhitelisted(host: String): Boolean {
        val h = host.trimEnd('.').lowercase()
        val user = userWhitelist
        if (h in builtinWhitelist || h in user) return true
        var idx = h.indexOf('.')
        while (idx >= 0 && idx < h.length - 1) {
            val parent = h.substring(idx + 1)
            if (parent in builtinWhitelist || parent in user) return true
            idx = h.indexOf('.', idx + 1)
        }
        return false
    }

    /** Add a domain (and all its subdomains) to the user whitelist. */
    fun whitelist(context: Context, domain: String) {
        val d = domain.trim().trimEnd('.').lowercase()
        if (d.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = (prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()).toMutableSet()
        set.add(d)
        prefs.edit().putStringSet(KEY_WHITELIST, set).apply()
        userWhitelist = set
        load(context)
    }

    fun unwhitelist(context: Context, domain: String) {
        val d = domain.trim().trimEnd('.').lowercase()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = (prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()).toMutableSet()
        set.remove(d)
        prefs.edit().putStringSet(KEY_WHITELIST, set).apply()
        userWhitelist = set
        load(context)
    }

    fun userWhitelist(): Set<String> = userWhitelist

    fun isBlocked(host: String): Boolean {
        val h = host.trimEnd('.').lowercase()
        // Whitelisted domains are never blocked — protects app functionality.
        if (isWhitelisted(h)) return false
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