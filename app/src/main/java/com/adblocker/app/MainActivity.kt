package com.adblocker.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.adblocker.app.root.RootManager
import com.adblocker.app.ui.ShieldMotionView
import com.adblocker.app.vpn.AdBlockVpnService
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var isOn = false
    private var rootMode = false
    private var lastCount = -1L
    private var animating = false
    private var suppressSwitch = false
    private var enableRequestedAt = 0L
    private var pulse: ObjectAnimator? = null

    private lateinit var statusText: TextView
    private lateinit var statusSub: TextView
    private lateinit var statusDot: View
    private lateinit var modeChip: com.google.android.material.chip.Chip
    private lateinit var protectionSwitch: MaterialSwitch
    private lateinit var glow: View
    private lateinit var motion: ShieldMotionView
    private lateinit var counterValue: TextView
    private lateinit var lastBlocked: TextView
    private lateinit var uptime: TextView
    private lateinit var listSize: TextView
    private lateinit var autoCount: TextView
    private lateinit var updateButton: MaterialButton
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
        statusSub = findViewById(R.id.statusSub)
        statusDot = findViewById(R.id.statusDot)
        modeChip = findViewById(R.id.modeChip)
        protectionSwitch = findViewById(R.id.protectionSwitch)
        glow = findViewById(R.id.glow)
        motion = findViewById(R.id.motion)
        motion.color = ContextCompat.getColor(this, if (light) R.color.light_accent else R.color.dark_accent)
        motion.onColor = ContextCompat.getColor(this, if (light) R.color.light_toggle_on else R.color.dark_toggle_on)
        motion.offColor = ContextCompat.getColor(this, if (light) R.color.light_toggle_off else R.color.dark_toggle_off)
        counterValue = findViewById(R.id.counterValue)
        lastBlocked = findViewById(R.id.lastBlocked)
        uptime = findViewById(R.id.uptime)
        listSize = findViewById(R.id.listSize)
        autoCount = findViewById(R.id.autoCount)
        updateButton = findViewById(R.id.updateButton)
        progress = findViewById(R.id.progress)

        // Root detection off the main thread.
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
        animateIn()

        protectionSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch || animating || checked == isOn) return@setOnCheckedChangeListener
            runMotionAndToggle(checked)
        }
        updateButton.setOnClickListener { checkUpdate(manual = true) }
        findViewById<View>(R.id.whitelistButton).setOnClickListener { showWhitelistDialog() }
        findViewById<View>(R.id.themeButton).setOnClickListener {
            prefs.edit().putBoolean("light_theme", !light).apply()
            recreate()
        }

        requestNotificationPermission()
        startUiLoop()
        checkUpdate(manual = false)
    }

    // ---------------------------------------------------------------------
    // Smooth entrance / state animations
    // ---------------------------------------------------------------------

    private fun animateIn() {
        val density = resources.displayMetrics.density
        val items = listOf(
            R.id.header to 0L,
            R.id.statusCard to 70L,
            R.id.counterCard to 140L,
            R.id.infoCard to 210L
        )
        for ((id, delay) in items) {
            val v = findViewById<View>(id) ?: continue
            v.alpha = 0f
            v.translationY = 18f * density
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delay)
                .setDuration(440)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun crossfade(tv: TextView, text: CharSequence) {
        if (tv.text.toString() == text.toString()) return
        tv.animate().cancel()
        tv.animate().alpha(0f).setDuration(110).withEndAction {
            tv.text = text
            tv.animate().alpha(1f).setDuration(190).start()
        }.start()
    }

    private fun setGlow(active: Boolean) {
        pulse?.cancel()
        pulse = null
        if (active) {
            glow.animate().cancel()
            glow.alpha = 0.25f
            pulse = ObjectAnimator.ofFloat(glow, "alpha", 0.25f, 0.85f).apply {
                duration = 1400
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        } else {
            ObjectAnimator.ofFloat(glow, "alpha", glow.alpha, 0f).apply {
                duration = 280
                start()
            }
        }
    }

    private fun runMotionAndToggle(enable: Boolean) {
        animating = true
        protectionSwitch.isEnabled = false
        setGlow(false)
        motion.alpha = 0f
        motion.visibility = View.VISIBLE
        motion.animate().alpha(1f).setDuration(220).start()
        motion.play(enable) {
            motion.animate().alpha(0f).setDuration(200).withEndAction {
                motion.visibility = View.GONE
                protectionSwitch.isEnabled = true
                animating = false
                setGlow(isOn)
                refreshUi()
            }.start()
        }
        onToggle(enable)
    }

    // ---------------------------------------------------------------------
    // Protection state
    // ---------------------------------------------------------------------

    private fun onToggle(enable: Boolean) {
        if (enable) {
            // Optimistic UI: keep the switch on while the system dialog shows.
            setEnabled(true, animateGlow = false)
            if (rootMode) {
                showProgress(true)
                Thread {
                    val ok = RootManager.apply(this)
                    runOnUiThread {
                        showProgress(false)
                        if (!ok) {
                            setEnabled(false)
                            Toast.makeText(this, getString(R.string.root_not_available), Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            } else {
                val intent = VpnService.prepare(this)
                if (intent != null) startActivityForResult(intent, 100) else startVpn()
            }
        } else {
            setEnabled(false, animateGlow = false)
            if (rootMode) {
                showProgress(true)
                Thread {
                    RootManager.remove(this)
                    runOnUiThread { showProgress(false) }
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
            setEnabled(false)
            Toast.makeText(this, getString(R.string.vpn_permission_needed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun startVpn() {
        val i = Intent(this, AdBlockVpnService::class.java).apply { action = AdBlockVpnService.ACTION_START }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
    }

    private fun stopVpn() {
        val i = Intent(this, AdBlockVpnService::class.java).apply { action = AdBlockVpnService.ACTION_STOP }
        startService(i)
    }

    private fun setEnabled(value: Boolean, animateGlow: Boolean = true) {
        if (value) enableRequestedAt = System.currentTimeMillis()
        isOn = value
        getSharedPreferences("adblocker", MODE_PRIVATE).edit()
            .putBoolean("enabled", value)
            .putBoolean("auto_start", value)
            .apply()
        if (animateGlow) setGlow(value)
        suppressSwitch = true
        protectionSwitch.isChecked = value
        suppressSwitch = false
        protectionSwitch.animate()
            .scaleX(1.08f).scaleY(1.08f).setDuration(90)
            .withEndAction {
                protectionSwitch.animate().scaleX(1f).scaleY(1f)
                    .setDuration(220).setInterpolator(OvershootInterpolator(2f)).start()
            }.start()
        refreshUi()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 201
            )
        }
    }

    // ---------------------------------------------------------------------
    // Updates
    // ---------------------------------------------------------------------

    private fun checkUpdate(manual: Boolean) {
        if (manual) {
            updateButton.isEnabled = false
            updateButton.text = getString(R.string.checking_updates)
            showProgress(true)
        }
        Thread {
            val rel = UpdateManager.check()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (manual) {
                    showProgress(false)
                    updateButton.isEnabled = true
                    updateButton.text = getString(R.string.check_updates)
                }
                if (rel != null && rel.apkUrl != null) {
                    showUpdateDialog(rel)
                } else if (manual) {
                    if (UpdateManager.lastCheckFailed) {
                        Toast.makeText(this, getString(R.string.check_failed), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, getString(R.string.up_to_date), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun showUpdateDialog(rel: UpdateManager.Release) {
        val view = layoutInflater.inflate(R.layout.dialog_update, null)
        view.findViewById<TextView>(R.id.updateName).text = rel.name
        view.findViewById<TextView>(R.id.updateNotes).text =
            rel.notes.ifBlank { getString(R.string.update_notes_default) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_available)
            .setView(view)
            .setPositiveButton(R.string.update_now) { _, _ -> startDownload(rel.apkUrl!!) }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun startDownload(url: String) {
        Toast.makeText(this, getString(R.string.downloading), Toast.LENGTH_SHORT).show()
        UpdateManager.downloadAndInstall(this, url, object : UpdateManager.Listener {
            override fun post(block: () -> Unit) {
                runOnUiThread { block() }
            }

            override fun onProgress(percent: Int) {
                if (percent == 0 || percent == 100 || percent % 20 == 0) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.download_progress, percent),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onInstalled() {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.opening_installer),
                    Toast.LENGTH_LONG
                ).show()
            }

            override fun onError(message: String) {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        })
    }

    // ---------------------------------------------------------------------
    // Whitelist (kept so a broken app can be fixed in one tap)
    // ---------------------------------------------------------------------

    private fun showWhitelistDialog() {
        val items = BlocklistManager.userWhitelist().toSortedSet().toList()
        if (items.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.whitelist_title)
                .setMessage(R.string.whitelist_empty)
                .setNeutralButton(R.string.whitelist_add) { _, _ -> showAddWhitelistDialog() }
                .setPositiveButton(R.string.done, null)
                .show()
            return
        }
        val display = items.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.whitelist_title)
            .setMessage(R.string.whitelist_hint)
            .setItems(display) { _, which ->
                val domain = items[which]
                MaterialAlertDialogBuilder(this)
                    .setTitle(domain)
                    .setMessage(R.string.whitelist_remove_q)
                    .setPositiveButton(R.string.whitelist_remove) { _, _ ->
                        BlocklistManager.unwhitelist(this, domain)
                        Toast.makeText(this, R.string.whitelist_removed, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNeutralButton(R.string.whitelist_add) { _, _ -> showAddWhitelistDialog() }
            .setPositiveButton(R.string.done, null)
            .show()
    }

    private fun showAddWhitelistDialog() {
        val light = getSharedPreferences("adblocker", MODE_PRIVATE)
            .getBoolean("light_theme", false)
        val input = EditText(this).apply {
            hint = "example.com"
            isSingleLine = true
            setTextColor(ContextCompat.getColor(this@MainActivity,
                if (light) android.R.color.black else android.R.color.white))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.whitelist_add)
            .setMessage(R.string.whitelist_hint)
            .setView(input)
            .setPositiveButton(R.string.add) { _, _ ->
                val domain = input.text.toString().trim()
                if (domain.contains('.') && domain.length > 3) {
                    BlocklistManager.whitelist(this, domain)
                    Toast.makeText(this, R.string.whitelist_added, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.invalid_domain, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------------
    // Live UI
    // ---------------------------------------------------------------------

    private fun showProgress(show: Boolean) {
        if (show) {
            progress.visibility = View.VISIBLE
            progress.alpha = 0f
            progress.animate().alpha(1f).setDuration(180).start()
        } else {
            progress.animate().alpha(0f).setDuration(150).withEndAction {
                progress.visibility = View.GONE
            }.start()
        }
    }

    private fun refreshUi() {
        crossfade(statusText, getString(if (isOn) R.string.status_on else R.string.status_off))
        crossfade(statusSub, getString(if (isOn) R.string.status_on_sub else R.string.status_off_sub))

        suppressSwitch = true
        protectionSwitch.isChecked = isOn
        suppressSwitch = false

        val dot = statusDot.background as? GradientDrawable
        dot?.setColor(ContextCompat.getColor(this,
            if (isOn) android.R.color.holo_green_light else android.R.color.holo_red_light))

        modeChip.text = getString(if (rootMode) R.string.mode_root else R.string.mode_vpn)

        val count = if (rootMode) BlocklistManager.size().toLong() else AdBlockVpnService.blockedCount
        if (count != lastCount) {
            counterValue.text = count.toString()
            counterValue.animate().cancel()
            counterValue.scaleX = 1f
            counterValue.scaleY = 1f
            counterValue.animate()
                .scaleX(1.14f).scaleY(1.14f).setDuration(140)
                .withEndAction {
                    counterValue.animate().scaleX(1f).scaleY(1f)
                        .setDuration(240).setInterpolator(OvershootInterpolator(1.6f)).start()
                }.start()
            lastCount = count
        }

        val domain = AdBlockVpnService.lastBlockedDomain
        lastBlocked.text = if (domain.isNotEmpty())
            "${getString(R.string.last_blocked)}: $domain" else getString(R.string.waiting)

        val secs = if (isOn && !rootMode && AdBlockVpnService.startedAt > 0)
            (System.currentTimeMillis() - AdBlockVpnService.startedAt) / 1000 else 0
        uptime.text = "${getString(R.string.uptime)}: ${secs / 60}m ${secs % 60}s"
        listSize.text = "${getString(R.string.list_size)}: ${BlocklistManager.size()}"
        autoCount.text = "${getString(R.string.auto_count)}: ${BlocklistManager.autoDetectedCount()}"
    }

    private fun startUiLoop() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                syncServiceState()
                refreshUi()
                handler.postDelayed(this, 800)
            }
        }, 800)
    }

    /**
     * The user can stop protection from the system notification. Keep the
     * in-app switch in sync with what is actually stored / running.
     */
    private fun syncServiceState() {
        if (animating) return
        if (System.currentTimeMillis() - enableRequestedAt <= 5000L) return
        val prefOn = getSharedPreferences("adblocker", MODE_PRIVATE)
            .getBoolean("enabled", isOn)
        if (prefOn != isOn) {
            setEnabled(prefOn)
            return
        }
        if (isOn && !rootMode && !AdBlockVpnService.isRunning &&
            AdBlockVpnService.startedAt > 0L
        ) {
            setEnabled(false)
        }
    }

    override fun onResume() {
        super.onResume()
        syncServiceState()
        refreshUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        pulse?.cancel()
    }
}
