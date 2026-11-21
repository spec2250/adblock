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
    private lateinit var blockedTotal: TextView
    private lateinit var lastBlocked: TextView
    private lateinit var uptime: TextView
    private lateinit var listSize: TextView
    private lateinit var autoCount: TextView
    private lateinit var updateButton: MaterialButton
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        styleSystemBars()

        statusText = findViewById(R.id.statusText)
        statusSub = findViewById(R.id.statusSub)
        statusDot = findViewById(R.id.statusDot)
        modeChip = findViewById(R.id.modeChip)
        protectionSwitch = findViewById(R.id.protectionSwitch)
        glow = findViewById(R.id.glow)
        motion = findViewById(R.id.motion)
        counterValue = findViewById(R.id.counterValue)
        blockedTotal = findViewById(R.id.blockedTotal)
        lastBlocked = findViewById(R.id.lastBlocked)
        uptime = findViewById(R.id.uptime)
        listSize = findViewById(R.id.listSize)
        autoCount = findViewById(R.id.autoCount)
        updateButton = findViewById(R.id.updateButton)
        progress = findViewById(R.id.progress)

        // Explicit fixed palette: the shield never turns into a bare glow.
        motion.color = ContextCompat.getColor(this, R.color.gold)
        motion.offColor = ContextCompat.getColor(this, R.color.shield_off)

        isOn = getSharedPreferences("adblocker", MODE_PRIVATE).getBoolean("enabled", false)
        motion.setRestingState(isOn)
        setGlow(isOn)
        refreshUi()
        animateIn()

        // Root detection off the main thread.
        Thread {
            val hasRoot = RootManager.isRootAvailable()
            runOnUiThread {
                rootMode = hasRoot
                modeChip.text = getString(if (rootMode) R.string.mode_root else R.string.mode_vpn)
                refreshUi()
            }
        }.start()

        protectionSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch || animating || checked == isOn) return@setOnCheckedChangeListener
            runMotionAndToggle(checked)
        }
        updateButton.setOnClickListener { checkUpdate(manual = true) }
        findViewById<View>(R.id.whitelistButton).setOnClickListener { showWhitelistDialog() }
        findViewById<View>(R.id.whitelistIcon).setOnClickListener { showWhitelistDialog() }
        findViewById<View>(R.id.avatarButton).setOnClickListener { showDetailsDialog() }
        findViewById<View>(R.id.detailsButton).setOnClickListener { showDetailsDialog() }

        requestNotificationPermission()
        startUiLoop()
        checkUpdate(manual = false)
    }

    // ---------------------------------------------------------------------
    // Smooth entrance / state animations
    // ---------------------------------------------------------------------

    /**
     * Keep system bar icons readable on the cream background. API 23/26 use
     * dark icons; older versions get a black bar because they cannot invert.
     */
    private fun styleSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } else {
            window.statusBarColor = ContextCompat.getColor(this, R.color.ink)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else {
            window.navigationBarColor = ContextCompat.getColor(this, R.color.ink)
        }
    }

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
            glow.alpha = 0.18f
            pulse = ObjectAnimator.ofFloat(glow, "alpha", 0.18f, 0.60f).apply {
                duration = 1600
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
        motion.visibility = View.VISIBLE
        motion.play(enable) {
            protectionSwitch.isEnabled = true
            animating = false
            // Keep the full shield visible at rest - only the glow used to remain.
            // Use the real state: the user may have denied the VPN permission.
            motion.setRestingState(isOn)
            setGlow(isOn)
            refreshUi()
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
            if (!animating) motion.setRestingState(false)
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
    // Details + whitelist (a broken app can still be fixed in one tap)
    // ---------------------------------------------------------------------

    private fun showDetailsDialog() {
        val status = getString(if (isOn) R.string.status_on else R.string.status_off)
        val mode = getString(if (rootMode) R.string.mode_root else R.string.mode_vpn)
        val count = if (rootMode) BlocklistManager.size() else AdBlockVpnService.blockedCount
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.details_title)
            .setMessage(
                getString(
                    R.string.details_body,
                    status,
                    mode,
                    count,
                    BlocklistManager.size(),
                    BlocklistManager.autoDetectedCount()
                )
            )
            .setPositiveButton(R.string.done, null)
            .show()
    }

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
        val input = EditText(this).apply {
            hint = "example.com"
            isSingleLine = true
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink_tertiary))
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
        dot?.setColor(
            ContextCompat.getColor(
                this,
                if (isOn) R.color.online_green else R.color.offline_red
            )
        )

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
        blockedTotal.text = count.toString()

        val domain = AdBlockVpnService.lastBlockedDomain
        lastBlocked.text = if (domain.isNotEmpty()) domain else getString(R.string.waiting)

        val secs = if (isOn && !rootMode && AdBlockVpnService.startedAt > 0)
            (System.currentTimeMillis() - AdBlockVpnService.startedAt) / 1000 else 0
        uptime.text = if (secs > 0) "${secs / 60}m ${secs % 60}s" else "0m 0s"
        listSize.text = BlocklistManager.size().toString()
        autoCount.text = BlocklistManager.autoDetectedCount().toString()
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
            motion.setRestingState(prefOn)
            return
        }
        if (isOn && !rootMode && !AdBlockVpnService.isRunning &&
            AdBlockVpnService.startedAt > 0L
        ) {
            setEnabled(false)
            motion.setRestingState(false)
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
