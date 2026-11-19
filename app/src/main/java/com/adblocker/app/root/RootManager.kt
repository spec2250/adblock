package com.adblocker.app.root

import android.content.Context
import com.adblocker.app.BlocklistManager
import java.io.File

object RootManager {
    private const val MARKER_START = "# >>> ADBLOCKER START <<<"
    private const val MARKER_END = "# >>> ADBLOCKER END <<<"

    fun isRootAvailable(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            p.waitFor()
            p.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    fun runSu(cmd: String): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            p.waitFor()
            p.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    /** Apply blocklist to /system/etc/hosts. Returns true on success. */
    fun apply(context: Context): Boolean {
        if (!isRootAvailable()) return false
        val hosts = StringBuilder()
        hosts.append("\n").append(MARKER_START).append("\n")
        // build entries from BlocklistManager's stored file + bundled
        val domains = mutableSetOf<String>()
        try {
            context.assets.open("blocklist.txt").bufferedReader().use { parseHosts(it.readText(), domains) }
        } catch (_: Exception) { }
        val f = File(context.filesDir, "blocklist.txt")
        if (f.exists()) try { parseHosts(f.readText(), domains) } catch (_: Exception) { }
        domains.addAll(listOf("doubleclick.net", "googleadservices.com", "googlesyndication.com"))
        for (d in domains) hosts.append("0.0.0.0 ").append(d).append("\n")
        hosts.append(MARKER_END).append("\n")

        val tmp = File(context.cacheDir, "hosts_adblocker")
        tmp.writeText(hosts.toString())

        // remount system rw
        runSu("mount -o remount,rw /system")
        // remove previous block
        remove(context)
        // append
        val cmd = "cat ${tmp.absolutePath} >> /system/etc/hosts"
        val ok = runSu(cmd)
        runSu("mount -o remount,ro /system")
        return ok
    }

    fun remove(context: Context): Boolean {
        if (!isRootAvailable()) return false
        runSu("mount -o remount,rw /system")
        // delete lines between markers
        val cmd = "sed -i '/$MARKER_START/,/$MARKER_END/d' /system/etc/hosts"
        val ok = runSu(cmd)
        runSu("mount -o remount,ro /system")
        return ok
    }

    private fun parseHosts(text: String, out: MutableSet<String>) {
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val parts = t.split(Regex("\\s+"))
            if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) {
                for (i in 1 until parts.size) {
                    val d = parts[i].lowercase()
                    if (d != "localhost" && d.contains('.')) out.add(d)
                }
            }
        }
    }
}