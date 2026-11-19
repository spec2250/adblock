package com.adblocker.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.adblocker.app.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object UpdateManager {
    private const val REPO = "spec2250/adblock"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    const val APK_NAME = "adblocker-update.apk"
    const val ZIP_NAME = "adblocker-update.zip"

    data class Release(val code: Int, val name: String, val notes: String, val apkUrl: String?)

    fun check(): Release? {
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
            null
        }
    }

    fun download(context: Context, url: String): Long {
        val fileName = if (url.substringBefore("?").endsWith(".zip")) ZIP_NAME else APK_NAME
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("AdBlocker update")
            setDescription("Downloading new version…")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(request)
    }

    /** Extracts APK from the downloaded ZIP if needed, then opens installer. */
    fun install(context: Context) {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val apkFile = File(dir, APK_NAME)
        val zipFile = File(dir, ZIP_NAME)
        if (!apkFile.exists() && zipFile.exists()) {
            try { extractApk(zipFile, apkFile); zipFile.delete() }
            catch (_: Exception) { return }
        }
        if (!apkFile.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
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
        throw IllegalStateException("No APK inside ZIP")
    }
}