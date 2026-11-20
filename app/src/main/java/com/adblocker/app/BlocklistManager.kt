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
    private var blocked: MutableSet<String> = ConcurrentHashMap.newKeySet()

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

    /**
     * Extra ad/tracking providers added on top of the downloaded StevenBlack
     * list. These cover mobile in-app ad networks (Meta Audience Network,
     * AdMob, Unity, AppLovin, Vungle, etc.) and trackers that hosts lists
     * often miss. All entries are ad/telemetry only — safe to block.
     */
    private val extraAdProviders = listOf(
        // ---- Google / AdMob / DoubleClick ----
        "doubleclick.net", "googleadservices.com", "googlesyndication.com",
        "adservice.google.com", "pagead2.googlesyndication.com", "admob.com",

        // ---- Meta (Facebook Audience Network) — ad-specific hosts only ----
        "an.facebook.com", "ads.facebook.com", "pixel.facebook.com",
        "analytics.facebook.com", "atdmt.com",
        "audiencenetwork.com", "facebookads.com",

        // ---- Mobile in-app ad networks ----
        "adcolony.com", "applovin.com", "vungle.com", "chartboost.com",
        "unityads.unity3d.com", "ironsource.com", "ironsource.mobi",
        "supersonic.com", "leadbolt.com", "tapjoy.com", "startapp.com",
        "mobvista.com", "mintegral.com", "admost.com", "fyber.com",
        "inner-active.com", "tappx.com", "smaato.com", "smaato.net",
        "smartadserver.com", "saambaa.com", "inmobi.com", "inmobicdn.net",
        "mopub.com", "pubnative.net", "moloco.com", "liftoff.io",
        "persona.ly", "digitalturbine.com", "verve.com", "loopme.me",
        "pangle.io", "pangolin-sdk-toutiao.com", "pangolin-sdk-toutiao-b.com",

        // ---- Amazon advertising ----
        "amazon-adsystem.com", "aax.amazon-adsystem.com", "advertising.amazon.com",

        // ---- Microsoft / Bing ads ----
        "bat.bing.com", "msads.net", "clarity.ms",

        // ---- Twitter / X ads ----
        "ads-twitter.com", "analytics.twitter.com",

        // ---- Programmatic / display networks ----
        "criteo.com", "taboola.com", "outbrain.com", "openx.net",
        "rubiconproject.com", "adnexus.net", "adnxs.com",
        "xandr.com", "pubmatic.com", "indexww.com", "spotx.tv", "telaria.com",
        "brightroll.com", "districtm.com", "sonobi.com", "triplelift.com",
        "sovrn.com", "lijit.com", "gumgum.com", "yieldmo.com", "mediaplex.com",
        "adtech.de", "adform.net", "bidswitch.net", "moatads.com",
        "doubleverify.com", "advertising.com", "yieldmanager.com", "2mdn.net",
        "casalemedia.com", "fastclick.net", "zedo.com", "burstnet.com",
        "adsrvr.org", "33across.com", "sharethrough.com", "teads.tv",

        // ---- Analytics / trackers ----
        "google-analytics.com", "googletagmanager.com", "googletagservices.com",
        "mixpanel.com", "amplitude.com", "segment.com",
        "adjust.com", "appsflyer.com", "kochava.com", "singular.net",
        "flurry.com", "countly.com", "umeng.com"
    ).map { it.trim().lowercase() }

    // ---------------------------------------------------------------------
    // Automatic ad-domain detection
    // ---------------------------------------------------------------------

    /**
     * Whole-label keywords that reliably mean "advertising / telemetry".
     * Matching is done on complete DNS labels only, so innocent names such as
     * "download", "adapter", "road" or "shadow" are never touched.
     */
    private val adLabels = setOf(
        "ad", "ads", "adv", "advert", "adverts", "advertising", "adserver",
        "adservice", "adservices", "adzone", "adclick", "adunit", "adunits",
        "adslot", "adslots", "adrequest", "adrequests", "banner", "banners",
        "interstitial", "interstitials", "rewarded", "preroll", "midroll",
        "promo", "promos", "promoted", "sponsor", "sponsored", "sponsors",
        "analytics", "analytic", "telemetry", "advertising-id",
        "pixel", "pixels", "beacon", "beacons",
        "bid", "bids", "bidder", "bidders", "rtb", "vast", "vpaid", "prebid",
        "mediation", "mopub", "applovin", "vungle", "chartboost", "ironsource",
        "tapjoy", "mintegral", "startapp", "leadbolt", "mobvista", "fyber",
        "smaato", "pubmatic", "rubicon", "criteo", "taboola", "outbrain",
        "openx", "xandr", "appnexus", "moat", "doubleverify"
    )

    /**
     * Substrings that are distinctive enough to identify an ad/tracking host
     * even when they appear inside a longer label (e.g. "googleads",
     * "adserver-3", "app-measurement").
     */
    private val strongMarkers = arrayOf(
        "doubleclick", "googlesyndication", "googleadservice", "amazon-adsystem",
        "adsystem", "adserver", "adservice", "advertising", "advertise",
        "adcolony", "applovin", "vungle", "chartboost", "ironsource",
        "supersonicads", "tapjoy", "mintegral", "startapp", "leadbolt",
        "mobvista", "fyber", "smaato", "pubmatic", "rubiconproject", "criteo",
        "taboola", "outbrain", "openx", "xandr", "appnexus", "adnxs",
        "moatads", "doubleverify", "adsafeprotected", "scorecardresearch",
        "comscore", "kochava", "appsflyer", "app-measurement", "mixpanel",
        "amplitude", "segment.io", "flurry", "countly", "google-analytics",
        "googletagmanager", "googletagservices", "telemetry",
        "adfox", "adriver", "adform", "adroll", "adtech", "adition",
        "smartadserver", "casalemedia", "fastclick", "zedo", "yieldmo",
        "gumgum", "triplelift", "sovrn", "sonobi", "indexww", "spotx",
        "telaria", "brightroll", "districtm", "mediaplex", "bidswitch",
        "yieldmanager", "prebid", "imasdk", "vpaid", "unityads", "mopub",
        "admost", "tappx", "inner-active", "ads-twitter", "adnami",
        "inmobi", "pubnative", "moloco", "liftoff", "pangolin", "pangle",
        "adsrvr", "teads", "loopme", "sharethrough", "33across"
    )

    @Volatile
    private var userWhitelist: Set<String> = emptySet()
    private const val KEY_WHITELIST = "whitelist"

    /**
     * Changes whenever the block list is reloaded (e.g. the user whitelists
     * a domain). The VPN uses it to drop its DNS cache immediately instead of
     * waiting up to 60 seconds for entries to expire.
     */
    @Volatile
    var whitelistVersion: Long = 0L
        private set

    // Domains discovered automatically while DNS traffic passes through.
    // Persisted so protection keeps learning across app restarts.
    @Volatile
    private var autoBlocked: Set<String> = emptySet()
    private const val KEY_AUTO_BLOCK = "auto_blocked"
    private const val AUTO_BLOCK_MAX = 6000
    private val autoLock = Any()

    fun url(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL

    fun setUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_URL, url).apply()
    }

    @Synchronized
    fun load(context: Context) {
        // Read user lists FIRST so the filter below uses the latest entries.
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        userWhitelist = prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()
        autoBlocked = prefs.getStringSet(KEY_AUTO_BLOCK, emptySet()) ?: emptySet()

        val set = ConcurrentHashMap.newKeySet<String>()
        try {
            context.assets.open("blocklist.txt").bufferedReader().use { parse(it.readText(), set) }
        } catch (_: Exception) { }
        val f = File(context.filesDir, FILE_NAME)
        if (f.exists()) {
            try { parse(f.readText(), set) } catch (_: Exception) { }
        }

        // Add our curated ad-provider list + everything detected automatically.
        set.addAll(extraAdProviders)
        set.addAll(autoBlocked)

        // Remove anything the user or we consider essential.
        // (Manual loop instead of removeIf: that Java 8 API needs Android 7+.)
        val iter = set.iterator()
        while (iter.hasNext()) {
            if (isWhitelisted(iter.next())) iter.remove()
        }
        blocked = set
        whitelistVersion = System.currentTimeMillis()
    }

    fun isLoaded(): Boolean = blocked.isNotEmpty()
    fun size(): Int = blocked.size

    /** Number of domains the automatic engine discovered by itself. */
    fun autoDetectedCount(): Int = autoBlocked.size

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

    /**
     * Decides whether a hostname looks like an advertising / tracking endpoint.
     * Purely local heuristics — no network call, no user interaction.
     */
    fun isAdLike(host: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase()
        if (h.isEmpty() || !h.contains('.')) return false
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".arpa")) return false
        if (isWhitelisted(h)) return false

        for (marker in strongMarkers) {
            if (h.contains(marker)) return true
        }

        for (label in h.split('.')) {
            if (label in adLabels) return true
        }
        return false
    }

    /**
     * Permanently learns [host] as an ad domain: it is added to the live block
     * set immediately (so the current DNS query is blocked) and persisted.
     * Returns true only the first time the domain is discovered.
     */
    @Synchronized
    fun autoBlock(context: Context, host: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase()
        if (h.isEmpty() || !h.contains('.') || isWhitelisted(h)) return false

        blocked.add(h)

        synchronized(autoLock) {
            if (autoBlocked.contains(h)) return false
            val next = autoBlocked.toMutableSet()
            next.add(h)
            // Keep the learned list bounded so it never grows without limit.
            if (next.size > AUTO_BLOCK_MAX) {
                val iter = next.iterator()
                while (next.size > AUTO_BLOCK_MAX && iter.hasNext()) {
                    iter.next()
                    iter.remove()
                }
            }
            autoBlocked = next
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_AUTO_BLOCK, next).apply()
        }
        return true
    }

    /** Add a domain (and all its subdomains) to the user whitelist. */
    @Synchronized
    fun whitelist(context: Context, domain: String) {
        val d = domain.trim().trimEnd('.').lowercase()
        if (d.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = (prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()).toMutableSet()
        set.add(d)
        prefs.edit().putStringSet(KEY_WHITELIST, set).apply()
        userWhitelist = set

        // If the automatic engine had learned this domain, forget it now.
        synchronized(autoLock) {
            if (autoBlocked.contains(d)) {
                val learned = autoBlocked.toMutableSet()
                learned.remove(d)
                autoBlocked = learned
                prefs.edit().putStringSet(KEY_AUTO_BLOCK, learned).apply()
            }
        }
        // No full reload: isBlocked()/autoBlock() consult the whitelist first,
        // so the change applies instantly and the UI never freezes.
        whitelistVersion = System.currentTimeMillis()
    }

    @Synchronized
    fun unwhitelist(context: Context, domain: String) {
        val d = domain.trim().trimEnd('.').lowercase()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = (prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()).toMutableSet()
        set.remove(d)
        prefs.edit().putStringSet(KEY_WHITELIST, set).apply()
        userWhitelist = set
        whitelistVersion = System.currentTimeMillis()
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
