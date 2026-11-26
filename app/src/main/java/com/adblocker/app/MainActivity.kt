package com.adblocker.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.adblocker.app.root.RootManager
import com.adblocker.app.ui.ShieldGifView
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

    private lateinit var statusText: TextView
    private lateinit var statusSub: TextView
    private lateinit var themeButton: ImageButton
    private lateinit var protectionSwitch: MaterialSwitch
    private lateinit var motion: ShieldMotionView
    private lateinit var shieldGif: ShieldGifView
    private lateinit var counterValue: TextView
    private lateinit var protectionLabel: TextView
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
        themeButton = findViewById(R.id.themeButton)
        protectionSwitch = findViewById(R.id.protectionSwitch)
        motion = findViewById(R.id.motion)
        shieldGif = findViewById(R.id.shieldGif)
        counterValue = findViewById(R.id.counterValue)
        protectionLabel = findViewById(R.id.protectionLabel)
        blockedTotal = findViewById(R.id.blockedTotal)
        lastBlocked = findViewById(R.id.lastBlocked)
        uptime = findViewById(R.id.uptime)
        listSize = findViewById(R.id.listSize)
        autoCount = findViewById(R.id.autoCount)
        updateButton = findViewById(R.id.updateButton)
        progress = findViewById(R.id.progress)

        isOn = getSharedPreferences("adblocker", MODE_PRIVATE).getBoolean("enabled", false)
        motion.setRestingState(isOn)
        refreshUi()
        animateIn()

        // The moon/sun button flips the whole app between the two explicit
        // palettes. AppCompat recreates this activity with values-night applied.
        updateThemeButton()
        themeButton.setOnClickListener {
            val goDark = !isDarkMode()
            getSharedPreferences("adblocker", MODE_PRIVATE).edit()
                .putBoolean("dark_mode", goDark)
                .apply()
            AppCompatDelegate.setDefaultNightMode(
                if (goDark) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        // Root detection off the main thread.
        Thread {
            val hasRoot = RootManager.isRootAvailable()
            runOnUiThread {
                rootMode = hasRoot
                refreshUi()
            }
        }.start()

        protectionSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch || animating || checked == isOn) return@setOnCheckedChangeListener
            runMotionAndToggle(checked)
        }
        updateButton.setOnClickListener { checkUpdate(manual = true) }

        requestNotificationPermission()
        startUiLoop()
        checkUpdate(manual = false)
    }

    // ---------------------------------------------------------------------
    // Smooth entrance / state animations
    // ---------------------------------------------------------------------

    /**
     * Keep system bar icons readable in both palettes. API 23/26 use dark
     * icons on the light palette and light icons on the dark palette; older
     * versions get a black bar because they cannot invert the icons.
     */
    private fun styleSystemBars() {
        val dark = isDarkMode()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var flags = window.decorView.systemUiVisibility
            flags = if (dark) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = if (dark) {
                    flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                } else {
                    flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
            }
            window.decorView.systemUiVisibility = flags
        } else {
            window.statusBarColor = ContextCompat.getColor(this, R.color.black_banner)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            window.navigationBarColor = ContextCompat.getColor(this, R.color.black_banner)
        }
    }

    private fun isDarkMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun updateThemeButton() {
        val dark = isDarkMode()
        themeButton.setImageResource(if (dark) R.drawable.ic_sun else R.drawable.ic_moon)
        themeButton.contentDescription =
            getString(if (dark) R.string.theme_light else R.string.theme_dark)
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

    private fun runMotionAndToggle(enable: Boolean) {
        animating = true
        protectionSwitch.isEnabled = false
        motion.play(enable) {
            protectionSwitch.isEnabled = true
            animating = false
            motion.setRestingState(isOn)
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
            setEnabled(true)
            if (rootMode) {
                showProgress(true)
                Thread {
                    val ok = RootManager.apply(this)
                    runOnUiThread {
                        showProgress(false)
                        if (!ok) {
                            setEnabled(false)
                            settleShieldMotion(false)
                            Toast.makeText(this, getString(R.string.root_not_available), Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            } else {
                val intent = VpnService.prepare(this)
                if (intent != null) startActivityForResult(intent, 100) else startVpn()
            }
        } else {
            setEnabled(false)
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
            settleShieldMotion(false)
            Toast.makeText(this, getString(R.string.vpn_permission_needed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun settleShieldMotion(enabled: Boolean) {
        if (animating) {
            animating = false
            protectionSwitch.isEnabled = true
        }
        motion.setRestingState(enabled)
    }

    private fun startVpn() {
        val i = Intent(this, AdBlockVpnService::class.java).apply { action = AdBlockVpnService.ACTION_START }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
    }

    private fun stopVpn() {
        val i = Intent(this, AdBlockVpnService::class.java).apply { action = AdBlockVpnService.ACTION_STOP }
        startService(i)
    }

    private fun setEnabled(value: Boolean) {
        if (value) enableRequestedAt = System.currentTimeMillis()
        isOn = value
        getSharedPreferences("adblocker", MODE_PRIVATE).edit()
            .putBoolean("enabled", value)
            .putBoolean("auto_start", value)
            .apply()
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
        val notes = rel.notes.ifBlank { getString(R.string.update_notes_default) }
        renderReleaseNotes(view.findViewById(R.id.updateNotesList), notes)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_available)
            .setView(view)
            .setPositiveButton(R.string.update_now) { _, _ -> startDownload(rel.apkUrl!!) }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private data class ReleaseNoteBlock(
        val text: String,
        val heading: Boolean = false,
        val bullet: Boolean = false
    )

    private fun renderReleaseNotes(container: LinearLayout, notes: String) {
        container.removeAllViews()
        val blocks = parseReleaseNotes(notes)
        for (block in blocks) {
            if (block.heading) {
                val heading = TextView(this).apply {
                    text = block.text
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(8)
                        bottomMargin = dp(6)
                    }
                }
                container.addView(heading)
                continue
            }

            val card = MaterialCardView(this).apply {
                radius = dp(14).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.surface))
                strokeWidth = dp(1)
                setStrokeColor(ContextCompat.getColor(this@MainActivity, R.color.line))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            if (block.bullet) {
                row.addView(View(this).apply {
                    setBackgroundResource(R.drawable.bg_circle_muted)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(this@MainActivity, R.color.gold)
                    )
                    layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                        marginEnd = dp(10)
                    }
                })
            }
            row.addView(TextView(this).apply {
                text = block.text
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink_secondary))
                setLineSpacing(dp(2).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            card.addView(row)
            container.addView(card)
        }
    }

    private fun parseReleaseNotes(notes: String): List<ReleaseNoteBlock> {
        val blocks = mutableListOf<ReleaseNoteBlock>()
        val paragraph = StringBuilder()

        fun flushParagraph() {
            val text = cleanReleaseNoteText(paragraph.toString().trim())
            if (text.isNotEmpty()) blocks += ReleaseNoteBlock(text)
            paragraph.setLength(0)
        }

        for (line in notes.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                flushParagraph()
                continue
            }
            val heading = Regex("^#{1,6}\\s+(.+)$").matchEntire(trimmed)
            if (heading != null) {
                flushParagraph()
                cleanReleaseNoteText(heading.groupValues[1])
                    .takeIf { it.isNotEmpty() }
                    ?.let { blocks += ReleaseNoteBlock(it, heading = true) }
                continue
            }
            val bullet = Regex("^(?:[-*+]\\s+|•\\s+)(.+)$").matchEntire(trimmed)
            val numbered = Regex("^\\d+[.)]\\s+(.+)$").matchEntire(trimmed)
            val listItem = bullet ?: numbered
            if (listItem != null) {
                flushParagraph()
                cleanReleaseNoteText(listItem.groupValues[1])
                    .takeIf { it.isNotEmpty() }
                    ?.let { blocks += ReleaseNoteBlock(it, bullet = true) }
                continue
            }
            if (paragraph.isNotEmpty()) paragraph.append(' ')
            paragraph.append(trimmed)
        }
        flushParagraph()
        return blocks
    }

    private fun cleanReleaseNoteText(text: String): String {
        var clean = text
        clean = Regex("""\[([^\]]+)\]\(([^)]+)\)""").replace(clean, "$1")
        for ((pattern, replacement) in listOf(
            Regex("\\*\\*(.+?)\\*\\*") to "$1",
            Regex("__(.+?)__") to "$1",
            Regex("\\*(.+?)\\*") to "$1",
            Regex("_(.+?)_") to "$1",
            Regex("~~(.+?)~~") to "$1",
            Regex("`([^`]+)`") to "$1"
        )) {
            clean = pattern.replace(clean, replacement)
        }

        val withoutEmoji = StringBuilder()
        var index = 0
        while (index < clean.length) {
            val codePoint = clean.codePointAt(index)
            index += Character.charCount(codePoint)
            val isEmojiSymbol = Character.getType(codePoint) == Character.OTHER_SYMBOL.toInt()
            val isEmojiModifier = codePoint in 0x1F3FB..0x1F3FF
            val isEmojiJoiner = codePoint == 0x200D || codePoint == 0xFE0E ||
                codePoint == 0xFE0F || codePoint == 0x20E3
            if (!isEmojiSymbol && !isEmojiModifier && !isEmojiJoiner) {
                withoutEmoji.appendCodePoint(codePoint)
            }
        }
        return withoutEmoji.toString().trim()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

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
        syncShieldViews()
        crossfade(statusText, getString(if (isOn) R.string.status_on else R.string.status_off))
        crossfade(statusSub, getString(if (isOn) R.string.status_on_sub else R.string.status_off_sub))
        crossfade(
            protectionLabel,
            getString(if (isOn) R.string.protection_running else R.string.protection_stopped)
        )

        suppressSwitch = true
        protectionSwitch.isChecked = isOn
        protectionSwitch.contentDescription =
            getString(if (isOn) R.string.toggle_off else R.string.toggle_on)
        suppressSwitch = false

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
        lastBlocked.setTextColor(
            ContextCompat.getColor(this, if (isOn) R.color.ink else R.color.ink_secondary)
        )

        val secs = if (isOn && !rootMode && AdBlockVpnService.startedAt > 0)
            (System.currentTimeMillis() - AdBlockVpnService.startedAt) / 1000 else 0
        uptime.text = if (secs > 0) "${secs / 60}m ${secs % 60}s" else "0m 0s"
        listSize.text = BlocklistManager.size().toString()
        autoCount.text = BlocklistManager.autoDetectedCount().toString()
    }

    private fun syncShieldViews() {
        val active = isOn
        shieldGif.visibility = if (active) View.VISIBLE else View.GONE
        shieldGif.setAnimating(active)
        // Keep the aura behind the active GIF; the motion view draws the crack only when off.
        motion.visibility = View.VISIBLE
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
    }
}
