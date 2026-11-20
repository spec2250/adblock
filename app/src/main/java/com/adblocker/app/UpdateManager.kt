package com.adblocker.app

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Self-update flow.
 *
 * The file is downloaded into the app's OWN private storage with a plain
 * HttpURLConnection, then un-zipped (Release assets are ZIPs) and handed to
 * the system installer through the FileProvider.
 *
 * The previous version used DownloadManager + setDestinationInExternalPublicDir,
 * which under scoped storage cannot be read back by the app, so install()
 * found nothing and silently returned — the "pressing update does nothing" bug.
 */
object UpdateManager {
    private const val REPO = "spec2250/adblock"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    const val APK_NAME = "adblocker-update.apk"
    const val ZIP_NAME = "adblocker-update.zip"

    data class Release(val code: Int, val name: String, val notes: String, val apkUrl: String?)

    /** Human readable progress/status for the UI (all callbacks run on the main thread). */
    interface Listener {
        /** Runs [block] on the main thread. */
        fun post(block: () -> Unit)
        fun onProgress(percent: Int)
        fun onInstalled()
        fun onError(message: String)
    }

    @Volatile
    private var busy = false

    /** True when the last [check] could not reach GitHub (network error). */
    @Volatile
    var lastCheckFailed = false
        private set

    fun check(): Release? {
        lastCheckFailed = false
        return try {
            val conn = URL(API).openConnection() as HttpURLConnection
            conn.connectTimeout = 12000
            conn.readTimeout = 12000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "AdBlocker")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()
            val tag = json.optString("tag_name", "v0")
            val code = Regex("\\d+").find(tag)?.value?.toIntOrNull() ?: 0
            if (code <= BuildConfig.VERSION_CODE) return null

            var fileUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name")
                    if (name.endsWith(".apk") || name.endsWith(".zip")) {
                        fileUrl = a.optString("browser_download_url")
                        break
                    }
                }
            }
            val body = json.optString("body", "")
            if (fileUrl == null) {
                fileUrl = Regex("https?://[^\\s)]+\\.(apk|zip)").find(body)?.value
            }
            Release(code, json.optString("name", tag), body, fileUrl)
        } catch (_: Exception) {
            lastCheckFailed = true
            null
        }
    }

    /**
     * Downloads the release into private storage, extracts the APK if needed
     * and launches the installer. Always calls back on the main thread.
     */
    fun downloadAndInstall(context: Context, url: String, listener: Listener) {
        if (busy) {
            listener.onError("يوجد تحديث قيد التحميل بالفعل")
            return
        }
        busy = true
        val appContext = context.applicationContext
        Thread {
            try {
                val isZip = url.substringBefore("?").endsWith(".zip", ignoreCase = true)
                val dir = File(appContext.filesDir, "update").apply { mkdirs() }
                val raw = File(dir, if (isZip) ZIP_NAME else APK_NAME)

                listener.post { listener.onProgress(0) }
                downloadFile(url, raw) { pct -> listener.post { listener.onProgress(pct) } }

                val apk = if (isZip) {
                    val out = File(dir, APK_NAME)
                    if (out.exists()) out.delete()
                    extractApk(raw, out)
                    raw.delete()
                    out
                } else {
                    raw
                }

                if (!apk.exists() || apk.length() < 1024) {
                    listener.post { listener.onError("فشل التنزيل: الملف غير صالح") }
                    return@Thread
                }

                listener.post { listener.onInstalled() }
                // startActivity must run on the main thread.
                listener.post { install(context, apk) }
            } catch (e: Exception) {
                listener.post {
                    listener.onError("فشل التحديث: ${e.message ?: "خطأ غير معروف"}")
                }
            } finally {
                busy = false
            }
        }.start()
    }

    private fun downloadFile(url: String, dest: File, onProgress: (Int) -> Unit) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "AdBlocker")
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val total = conn.contentLength.toLong()
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var sum = 0L
                    var lastPct = -1
                    while (input.read(buf).also { read = it } > 0) {
                        out.write(buf, 0, read)
                        sum += read
                        if (total > 0) {
                            val pct = ((sum * 100) / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct.coerceIn(0, 100))
                            }
                        }
                    }
                }
            }
            onProgress(100)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) { }
        }
    }

    private fun extractApk(zip: File, out: File) {
        ZipInputStream(FileInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".apk")) {
                    FileOutputStream(out).use { fos ->
                        val buf = ByteArray(16384)
                        var n = zis.read(buf)
                        while (n > 0) { fos.write(buf, 0, n); n = zis.read(buf) }
                    }
                    zis.closeEntry()
                    return
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        throw IllegalStateException("لا يوجد ملف APK داخل الأرشيف")
    }

    private fun install(context: Context, apk: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "تعذّر فتح المثبّت: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
