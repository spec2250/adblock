package com.adblocker.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.adblocker.app.root.RootManager
import com.adblocker.app.vpn.AdBlockVpnService

class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var isOn = false
    private var rootMode = false

    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var counterText: TextView
    private lateinit var modeText: TextView
    private lateinit var updateButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        // theme
        val prefs = getSharedPreferences("adblocker", MODE_PRIVATE)
        if (prefs.getBoolean("light_theme", false)) setTheme(R.style.Theme_AdBlocker_Light)
        else setTheme(R.style.Theme_AdBlocker)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)
        counterText = findViewById(R.id.counterText)
        modeText = findViewById(R.id.modeText)
        updateButton = findViewById(R.id.updateButton)
        val themeButton: Button = findViewById(R.id.themeButton)

        rootMode = RootManager.isRootAvailable()
        modeText.text = if (rootMode) getString(R.string.mode_root) else getString(R.string.mode_vpn)

        isOn = prefs.getBoolean("enabled", false)
        refreshUi()

        toggleButton.setOnClickListener { onToggle() }
        updateButton.setOnClickListener { onUpdate() }
        themeButton.setOnClickListener {
            prefs.edit().putBoolean("light_theme", !prefs.getBoolean("light_theme", false)).apply()
            recreate()
        }

        // restore state
        if (isOn) {
            if (rootMode) {
                if (!RootManager.isRootAvailable()) { isOn = false; saveEnabled(false) }
            } else {
                // vpn may have stopped; leave as is
            }
        }
        refreshUi()
        startCounterLoop()
    }

    private fun onToggle() {
        if (!isOn) {
            // turn on
            if (rootMode) {
                Thread {
                    val ok = RootManager.apply(this)
                    runOnUiThread {
                        if (ok) { isOn = true; saveEnabled(true); refreshUi() }
                        else Toast.makeText(this, getString(R.string.root_not_available), Toast.LENGTH_SHORT).show()
                    }
                }.start()
            } else {
                val intent = VpnService.prepare(this)
                if (intent != null) {
                    startActivityForResult(intent, 100)
                } else {
                    startVpn()
                }
            }
        } else {
            // turn off
            if (rootMode) {
                Thread {
                    RootManager.remove(this)
                    runOnUiThread { isOn = false; saveEnabled(false); refreshUi() }
                }.start()
            } else {
                stopVpn()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == Activity.RESULT_OK) {
            startVpn()
        } else if (requestCode == 100) {
            Toast.makeText(this, getString(R.string.vpn_permission_needed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun startVpn() {
        val i = Intent(this, AdBlockVpnService::class.java).apply { action = AdBlockVpnService.ACTION_START }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        isOn = true
        saveEnabled(true)
        refreshUi()
    }

    private fun stopVpn() {
        val i = Intent(this, AdBlockVpnService::class.java).apply { action = AdBlockVpnService.ACTION_STOP }
        startService(i)
        isOn = false
        saveEnabled(false)
        refreshUi()
    }

    private fun saveEnabled(v: Boolean) {
        getSharedPreferences("adblocker", MODE_PRIVATE).edit().putBoolean("enabled", v).apply()
    }

    private fun refreshUi() {
        statusText.text = if (isOn) getString(R.string.status_on) else getString(R.string.status_off)
        toggleButton.text = if (isOn) getString(R.string.toggle_off) else getString(R.string.toggle_on)
        if (!rootMode) {
            counterText.text = getString(R.string.blocked_count, AdBlockVpnService.blockedCount.toInt())
        } else {
            counterText.text = getString(R.string.blocked_count, BlocklistManager.size())
        }
    }

    private fun onUpdate() {
        updateButton.isEnabled = false
        updateButton.text = getString(R.string.updating)
        Thread {
            val ok = BlocklistManager.update(this)
            runOnUiThread {
                updateButton.isEnabled = true
                updateButton.text = getString(R.string.update_list)
                Toast.makeText(this, if (ok) getString(R.string.update_ok) else getString(R.string.update_fail), Toast.LENGTH_SHORT).show()
                if (ok && rootMode && isOn) {
                    Thread { RootManager.apply(this) }.start()
                }
                refreshUi()
            }
        }.start()
    }

    private fun startCounterLoop() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!rootMode && isOn) refreshUi()
                handler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}