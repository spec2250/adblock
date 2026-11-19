package com.adblocker.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * Professional "activation / deactivation" animation in the style of the app icon.
 *
 * The shield is the hero: it is the actual icon shape (cream→gold gradient,
 * layered bevel), it draws itself in with an animated trim, and a check mark
 * is revealed stroke by stroke. Around it a dotted orbit rotates continuously,
 * a soft aura pulses, light rays sweep, and sparkles twinkle.
 *
 * Deactivation reverses the sequence: the check un-draws, a jagged crack
 * travels across the shield, then the shield breaks into shards that fly
 * outward and fade, while the orbit collapses.
 */
class ShieldMotionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var color: Int = Color.parseColor("#D4A574")
    var onColor: Int = Color.parseColor("#7FA87F")
    var offColor: Int = Color.parseColor("#A87070")

    private var progress = 0f          // 0..1 master timeline
    private var enabling = true
    private var animator: ValueAnimator? = null
    private var endAction: (() -> Unit)? = null

    private var shieldShader: Shader? = null
    private var auraShader: Shader? = null

    // --- paints ---
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shieldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        pathEffect = CornerPathEffect(6f)
    }
    private val bevelLight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#FFE9CC"); strokeWidth = 3f
    }
    private val bevelDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#6E4526"); strokeWidth = 2f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 11f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#2E241C")
    }
    private val checkGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 20f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#3A2A1E")
    }
    private val crackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val sparklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) * 0.28f
        bgPaint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            Color.parseColor("#2E251C"), Color.parseColor("#0E0C0A"), Shader.TileMode.CLAMP)
        shieldShader = LinearGradient(cx - r * 0.6f, cy - r, cx + r * 0.6f, cy + r,
            Color.parseColor("#FFF6EA"), color, Shader.TileMode.CLAMP)
        auraShader = RadialGradient(cx, cy, r * 1.9f,
            intArrayOf(Color.argb(60, 212, 165, 116), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
    }

    fun play(enable: Boolean, onEnd: () -> Unit) {
        animator?.cancel()
        enabling = enable
        endAction = onEnd
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (enable) 1250L else 980L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) * 0.28f
        val p = progress
        val accent = if (enabling) color else offColor

        // ---- dark rounded plate ----
        bgPaint.shader?.let { bgPaint.shader = it }
        val pad = minOf(w, h) * 0.02f
        canvas.drawRoundRect(RectF(pad, pad, w - pad, h - pad), r * 0.5f, r * 0.5f, bgPaint)

        // ---- rotating dotted orbit ----
        val orbitScale = if (enabling) 1f else (1f - p * 0.5f)
        val spin = p * (if (enabling) 2.4f else -1.6f)
        val orbitAlpha = (0.45f + 0.25f * sin(p * Math.PI.toFloat())).coerceIn(0f, 1f)
        for (i in 0 until 36) {
            val a = (i * 10) * Math.PI / 180.0 + spin
            val d = r * 1.55f * orbitScale
            dotPaint.color = accent
            dotPaint.alpha = (orbitAlpha * (if (i % 3 == 0) 220 else 110)).toInt()
            val size = if (i % 3 == 0) 3.2f else 1.8f
            canvas.drawCircle((cx + cos(a) * d).toFloat(), (cy + sin(a) * d).toFloat(), size, dotPaint)
        }

        // ---- aura / pulsing glow ----
        val auraAlpha = if (enabling) (p / 0.5f).coerceIn(0f, 1f) else (1f - p).coerceIn(0f, 1f)
        auraShader?.let {
            auraPaint.shader = it
            auraPaint.alpha = (auraAlpha * 255).toInt()
            canvas.drawCircle(cx, cy, r * 1.9f, auraPaint)
        }

        if (enabling) {
            // expanding halo rings
            for (i in 0..2) {
                val t = (p - i * 0.13f).coerceIn(0f, 1f)
                if (t > 0f) {
                    rayPaint.color = color
                    rayPaint.alpha = ((1f - t) * 150).toInt()
                    rayPaint.strokeWidth = 6f - 3f * t
                    canvas.drawCircle(cx, cy, r * (0.9f + t * 1.5f), rayPaint)
                }
            }
            // light rays sweep out
            val rayT = ((p - 0.15f) / 0.6f).coerceIn(0f, 1f)
            if (rayT > 0f) {
                for (i in 0 until 8) {
                    val a = (i * 45) * Math.PI / 180.0 + p * 0.4f
                    val inner = r * (1.05f + rayT * 0.35f)
                    val outer = r * (1.15f + rayT * 0.9f)
                    rayPaint.color = color
                    rayPaint.alpha = ((1f - rayT) * 170).toInt()
                    rayPaint.strokeWidth = 4f
                    canvas.drawLine((cx + cos(a) * inner).toFloat(), (cy + sin(a) * inner).toFloat(),
                        (cx + cos(a) * outer).toFloat(), (cy + sin(a) * outer).toFloat(), rayPaint)
                }
            }
        }

        // ---- shield ----
        val shieldAppear = if (enabling) {
            (p / 0.42f).coerceIn(0f, 1f)
        } else {
            (1f - ((p - 0.45f) / 0.45f).coerceIn(0f, 1f))
        }
        if (shieldAppear > 0f) {
            var scale = 0.55f + 0.45f * shieldAppear
            if (enabling && p > 0.42f) {
                // subtle overshoot "pop"
                val t = ((p - 0.42f) / 0.2f).coerceIn(0f, 1f)
                scale += 0.08f * sin(t * Math.PI.toFloat())
            }
            drawShield(canvas, cx, cy, r * scale, shieldAppear, enabling, crack = 0f)
        }

        if (enabling) {
            // check mark draws in stroke by stroke after the shield settles
            val checkT = ((p - 0.45f) / 0.35f).coerceIn(0f, 1f)
            if (checkT > 0f) drawCheck(canvas, cx, cy, r, checkT)

            // sparkles twinkle around
            for (i in 0 until 6) {
                val a = (60 + i * 60) * Math.PI / 180.0 + p * 0.6f
                val d = r * (1.35f + 0.15f * sin(i * 1.7f + p * 6f))
                val tw = (0.4f + 0.6f * absSin(p * 5f + i)).coerceIn(0f, 1f)
                drawSparkle(canvas, (cx + cos(a) * d).toFloat(), (cy + sin(a) * d).toFloat(),
                    9f + 3f * tw, tw * (1f - p * 0.3f))
            }
        } else {
            // crack travels across the shield, then shards fly outward
            val crackT = ((p - 0.1f) / 0.35f).coerceIn(0f, 1f)
            if (crackT > 0f && shieldAppear > 0f) drawCrack(canvas, cx, cy, r, crackT)

            val burst = ((p - 0.4f) / 0.6f).coerceIn(0f, 1f)
            if (burst > 0f) {
                for (i in 0 until 9) {
                    val a = (i * 40 + 15) * Math.PI / 180.0
                    val d = r * (0.4f + burst * 2.4f)
                    drawShard(canvas, (cx + cos(a) * d).toFloat(), (cy + sin(a) * d).toFloat(),
                        14f, (a + burst * 2f).toFloat(), (1f - burst).coerceIn(0f, 1f))
                }
                for (i in 0 until 8) {
                    val a = (i * 45) * Math.PI / 180.0
                    val d = r * (1f + burst * 2f)
                    drawSparkle(canvas, (cx + cos(a) * d).toFloat(), (cy + sin(a) * d).toFloat(),
                        8f, (1f - burst).coerceIn(0f, 1f), offColor)
                }
            }
        }

        if (p >= 1f) {
            val action = endAction
            endAction = null
            if (action != null) post { action() }
        }
    }

    private fun absSin(v: Float): Float = kotlin.math.abs(sin(v.toDouble())).toFloat()

    private fun drawShield(
        canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Float, enabled: Boolean, crack: Float
    ) {
        if (alpha <= 0f) return
        val path = shieldPath(cx, cy, r)
        shieldPaint.shader = shieldShader
        shieldPaint.alpha = (alpha * 255).toInt()
        canvas.drawPath(path, shieldPaint)

        bevelLight.alpha = (alpha * 230).toInt()
        bevelDark.alpha = (alpha * 170).toInt()
        canvas.drawPath(path, bevelLight)
        canvas.drawPath(path, bevelDark)

        // inner gloss
        val hl = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; this.alpha = (alpha * 60).toInt(); style = Paint.Style.FILL
        }
        canvas.drawOval(cx - r * 0.5f, cy - r * 0.68f, cx + r * 0.5f, cy - r * 0.2f, hl)

        if (!enabled && crack > 0f) drawCrack(canvas, cx, cy, r, crack, alpha)
    }

    private fun drawCheck(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        val eased = easeOut(t)
        val path = checkPath(cx, cy, r)
        val measure = android.graphics.PathMeasure(path, false)
        val total = measure.length
        if (total <= 0f) return
        val dash = total * eased
        val gap = total - dash + 10f
        checkPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dash, gap), 0f)
        checkGlow.pathEffect = checkPaint.pathEffect
        checkGlow.alpha = 90
        checkPaint.alpha = 255
        canvas.drawPath(path, checkGlow)
        canvas.drawPath(path, checkPaint)
        checkPaint.pathEffect = null
        checkGlow.pathEffect = null
    }

    private fun drawCrack(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Float, alpha: Float = 1f) {
        val eased = easeOut(t.coerceIn(0f, 1f))
        val c = Path()
        c.moveTo(cx - r * 0.55f, cy - r * 0.62f)
        c.lineTo(cx - r * 0.05f, cy - r * 0.12f)
        c.lineTo(cx - r * 0.28f, cy + r * 0.16f)
        c.lineTo(cx + r * 0.34f, cy + r * 0.66f)
        val measure = android.graphics.PathMeasure(c, false)
        val total = measure.length
        if (total <= 0f) return
        crackPaint.pathEffect =
            android.graphics.DashPathEffect(floatArrayOf(total * eased, total * (1f - eased) + 10f), 0f)
        crackPaint.color = Color.parseColor("#2A1E16")
        crackPaint.strokeWidth = 12f
        crackPaint.alpha = (alpha * 255 * eased).toInt()
        canvas.drawPath(c, crackPaint)
        crackPaint.color = Color.WHITE
        crackPaint.strokeWidth = 4f
        crackPaint.alpha = (alpha * 200 * eased).toInt()
        canvas.drawPath(c, crackPaint)
        crackPaint.pathEffect = null
    }

    private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t)

    private fun shieldPath(cx: Float, cy: Float, r: Float): Path {
        val path = Path()
        path.moveTo(cx, cy - r)
        path.lineTo(cx + r * 0.86f, cy - r * 0.60f)
        path.lineTo(cx + r * 0.72f, cy + r * 0.28f)
        path.quadTo(cx + r * 0.60f, cy + r * 0.74f, cx, cy + r * 1.04f)
        path.quadTo(cx - r * 0.60f, cy + r * 0.74f, cx - r * 0.72f, cy + r * 0.28f)
        path.lineTo(cx - r * 0.86f, cy - r * 0.60f)
        path.close()
        return path
    }

    private fun checkPath(cx: Float, cy: Float, r: Float): Path {
        val mark = Path()
        mark.moveTo(cx - r * 0.40f, cy + 6f)
        mark.lineTo(cx - r * 0.08f, cy + r * 0.36f)
        mark.lineTo(cx + r * 0.46f, cy - r * 0.38f)
        return mark
    }

    private fun drawShard(canvas: Canvas, x: Float, y: Float, size: Float, angle: Float, alpha: Float) {
        if (alpha <= 0f) return
        shardPaint.color = color
        shardPaint.alpha = (alpha * 235).toInt()
        val p = Path()
        p.moveTo(x + cos(angle) * size, y + sin(angle) * size)
        p.lineTo(x + cos(angle + 2.5f) * size, y + sin(angle + 2.5f) * size)
        p.lineTo(x + cos(angle - 2.5f) * size, y + sin(angle - 2.5f) * size)
        p.close()
        canvas.drawPath(p, shardPaint)
    }

    private fun drawSparkle(canvas: Canvas, x: Float, y: Float, size: Float, alpha: Float, tint: Int = color) {
        if (alpha <= 0f) return
        sparklePaint.color = tint
        sparklePaint.alpha = (alpha * 255).toInt()
        val p = Path()
        p.moveTo(x, y - size)
        p.lineTo(x + size * 0.20f, y - size * 0.20f)
        p.lineTo(x + size, y)
        p.lineTo(x + size * 0.20f, y + size * 0.20f)
        p.lineTo(x, y + size)
        p.lineTo(x - size * 0.20f, y + size * 0.20f)
        p.lineTo(x - size, y)
        p.lineTo(x - size * 0.20f, y - size * 0.20f)
        p.close()
        canvas.drawPath(p, sparklePaint)
    }
}
