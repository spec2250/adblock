package com.adblocker.app.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adblocker.app.vpn.AdBlockVpnService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("adblocker", Context.MODE_PRIVATE)
            if (prefs.getBoolean("auto_start", false)) {
                val i = Intent(context, AdBlockVpnService::class.java)
                    .apply { action = AdBlockVpnService.ACTION_START }
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                        context.startForegroundService(i) else context.startService(i)
                } catch (_: Exception) { }
            }
        }
    }
}