package com.adblocker.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.adblocker.app.root.RootManager
import com.adblocker.app.ui.ShieldMotionView
import com.adblocker.app.vpn.AdBlockVpnService

class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var isOn = false
    private var rootMode = false
    private var lastCount = -1L
    private var animating = false
    private var pulse: ObjectAnimator? = null

    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var modeChip: com.google.android.material.chip.Chip
    private lateinit var toggleButton: com.google.android.material.button.MaterialButton
    private lateinit var glow: View
    private lateinit var motion: ShieldMotionView
    private lateinit var counterValue: TextView
    private lateinit var lastBlocked: TextView
    private lateinit var uptime: TextView
    private lateinit var listSize: TextView
    private lateinit var updateButton: com.google.android.material.button.MaterialButton
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("adblocker", MODE_PRIVATE)
        val light = prefs.getBoolean("light_theme", false)
        setTheme(if (light) R.style.Theme_AdBlocker_Light else R.style.Theme_AdBlocker)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<View>(R.id.rootLayout).setBackgroundResource(
            if (light) R.drawable.bg_gradient_light else R.drawable.bg_gradient_dark
        )

        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        modeChip = findViewById(R.id.modeChip)
        toggleButton = findViewById(R.id.toggleButton)
        glow = findViewById(R.id.glow)
        motion = findViewById(R.id.motion)
        motion.color = ContextCompat.getColor(this, if (light) R.color.light_accent else R.color.dark_accent)
        motion.onColor = ContextCompat.getColor(this, if (light) R.color.light_toggle_on else R.color.dark_toggle_on)
        motion.offColor = ContextCompat.getColor(this, if (light) R.color.light_toggle_off else R.color.dark_toggle_off)
        counterValue = findViewById(R.id.counterValue)
        lastBlocked = findViewById(R.id.lastBlocked)
        uptime = findViewById(R.id.uptime)
        listSize = findViewById(R.id.listSize)
        updateButton = findViewById(R.id.updateButton)
        progress = findViewById(R.id.progress)

        // Root detection off main thread
        Thread {
            val hasRoot = RootManager.isRootAvailable()
            runOnUiThread {
                rootMode = hasRoot
                modeChip.text = getString(if (rootMode) R.string.mode_root else R.string.mode_vpn)
                refreshUi()
            }
        }.start()

        isOn = prefs.getBoolean("enabled", false)
        setGlow(isOn)
        refreshUi()

        toggleButton.setOnClickListener { if (!animating) runMotionAndToggle() }
        updateButton.setOnClickListener { onUpdate() }
        findViewById<View>(R.id.themeButton).setOnClickListener {
            prefs.edit().putBoolean("light_theme", !light).apply()
            recreate()
        }
        startUiLoop()
        checkUpdate()
    }

    private fun checkUpdate() {
        Thread {
            val rel = UpdateManager.check()
            if (rel != null && rel.apkUrl != null && !isFinishing) {
                runOnUiThread { showUpdateDialog(rel) }
            }
        }.start()
    }

    private fun showUpdateDialog(rel: UpdateManager.Release) {
        val view = layoutInflater.inflate(R.layout.dialog_update, null)
        view.findViewById<TextView>(R.id.updateName).text = rel.name
        view.findViewById<TextView>(R.id.updateNotes).text =
            rel.notes.ifBlank { "تحسينات في الأداء والواجهة وإصلاحات." }
        MaterialAlertDialogBuilder(this)
            .setView(view)
            .setPositiveButton("تحديث الآن") { _, _ -> startDownload(rel.apkUrl!!) }
            .setNegativeButton("لاحقاً", null)
            .show()
    }

    private fun startDownload(url: String) {
        val id = UpdateManager.download(this, url)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) == id) {
                    unregisterReceiver(this)
                    UpdateManager.install(context)
                }
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun animateClick() {
        ObjectAnimator.ofFloat(toggleButton, "scaleX", 0.90f, 1f).apply {
            duration = 260; interpolator = OvershootInterpolator(2f); start()
        }
        ObjectAnimator.ofFloat(toggleButton, "scaleY", 0.90f, 1f).apply {
            duration = 260; interpolator = OvershootInterpolator(2f); start()
        }
    }

    private fun setGlow(active: Boolean) {
        pulse?.cancel()
        pulse = null
        if (active) {
            pulse = ObjectAnimator.ofFloat(glow, "alpha", 0.25f, 0.85f).apply {
                duration = 1400
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        } else {
            ObjectAnimator.ofFloat(glow, "alpha", 0f).apply { duration = 300; start() }
        }
    }

    private fun runMotionAndToggle() {
        animating = true
        animateClick()
        toggleButton.visibility = View.INVISIBLE
        setGlow(false)
        motion.visibility = View.VISIBLE
        val enabling = !isOn
        motion.play(enabling) {
            motion.visibility = View.GONE
            toggleButton.visibility = View.VISIBLE
            animating = false
            setGlow(isOn)
            refreshUi()
        }
        onToggle()
    }

    private fun onToggle() {
        if (!isOn) {
            if (rootMode) {
                showProgress(true)
                Thread {
                    val ok = RootManager.apply(this)
                    runOnUiThread {
                        showProgress(false)
                        if (ok) { setEnabled(true) } else {
                            Toast.makeText(this, getString(R.string.root_not_available), Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            } else {
                val intent = VpnService.prepare(this)
                if (intent != null) startActivityForResult(intent, 100) else startVpn()
            }
        } else {
            if (rootMode) {
                showProgress(true)
                Thread {
                    RootManager.remove(this)
                    runOnUiThread { showProgress(false); setEnabled(false) }
                }.start()
            } else {
                stopVpn()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == Activity.RESULT_OK) startVpn()
        else if (requestCode == 100) Toast.makeText(this, getString(R.string.vpn_permission_needed), Toast.LENGTH_SHORT).show()
    }

    private fun startVpn() {
        val i = Intent(this, AdBlockVpnService::class.java).apply { action = AdBlockVpnService.ACTION_START }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        setEnabled(true)
    }

    private fun stopVpn() {
        val i = Intent(this, AdBlockVpnService::class.java).apply { action = AdBlockVpnService.ACTION_STOP }
        startService(i)
        setEnabled(false)
    }

    private fun setEnabled(value: Boolean) {
        isOn = value
        getSharedPreferences("adblocker", MODE_PRIVATE).edit().putBoolean("enabled", value).apply()
        setGlow(value)
        statusText.alpha = 0f
        ObjectAnimator.ofFloat(statusText, "alpha", 1f).apply { duration = 300; start() }
        refreshUi()
    }

    private fun showProgress(show: Boolean) {
        progress.visibility = if (show) View.VISIBLE else View.GONE
        progress.alpha = if (show) 1f else 0f
    }

    private fun refreshUi() {
        statusText.text = getString(if (isOn) R.string.status_on else R.string.status_off)
        toggleButton.text = getString(if (isOn) R.string.toggle_off else R.string.toggle_on)
        val dot = statusDot.background as? GradientDrawable
        dot?.setColor(ContextCompat.getColor(this,
            if (isOn) android.R.color.holo_green_light else android.R.color.holo_red_light))

        val count = if (rootMode) BlocklistManager.size().toLong() else AdBlockVpnService.blockedCount
        if (count != lastCount) {
            counterValue.text = count.toString()
            ObjectAnimator.ofFloat(counterValue, "scaleX", 1f, 1.14f, 1f).apply { duration = 320; start() }
            ObjectAnimator.ofFloat(counterValue, "scaleY", 1f, 1.14f, 1f).apply { duration = 320; start() }
            lastCount = count
        }
        val domain = AdBlockVpnService.lastBlockedDomain
        lastBlocked.text = if (domain.isNotEmpty()) "${getString(R.string.last_blocked)}: $domain" else getString(R.string.waiting)

        val secs = if (isOn && !rootMode && AdBlockVpnService.startedAt > 0)
            (System.currentTimeMillis() - AdBlockVpnService.startedAt) / 1000 else 0
        uptime.text = "${getString(R.string.uptime)}: ${secs / 60}m ${secs % 60}s"
        listSize.text = "${getString(R.string.list_size)}: ${BlocklistManager.size()}"
    }

    private fun onUpdate() {
        updateButton.isEnabled = false
        showProgress(true)
        Thread {
            val ok = BlocklistManager.update(this)
            runOnUiThread {
                showProgress(false)
                updateButton.isEnabled = true
                Toast.makeText(this, getString(if (ok) R.string.update_ok else R.string.update_fail), Toast.LENGTH_SHORT).show()
                if (ok && rootMode && isOn) Thread { RootManager.apply(this) }.start()
                refreshUi()
            }
        }.start()
    }

    private fun startUiLoop() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isOn) refreshUi()
                handler.postDelayed(this, 800)
            }
        }, 800)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        pulse?.cancel()
    }
}