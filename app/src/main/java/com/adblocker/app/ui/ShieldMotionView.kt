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
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Gold shield motion graphic drawn entirely with explicit colors.
 *
 * The shield is NEVER hidden: when the enable/disable animation ends the view
 * switches to a resting state that keeps the full shield on screen (gold with
 * a check when protection is on, grey with a crack when it is off).
 */
class ShieldMotionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ==================== EXPLICIT PALETTE (NO SYSTEM COLORS) ====================
    var color: Int = 0xFFF6B400.toInt()      // gold
    var offColor: Int = 0xFF6E6A66.toInt()   // muted grey

    private val goldLight: Int = 0xFFFFF0C2.toInt()
    private val goldDeep: Int = 0xFFE39A00.toInt()
    private val auraInnerColor: Int = 0x40F6B400
    private val auraOffInnerColor: Int = 0x2E6E6A66
    private val auraOuterColor: Int = 0x00F6B400
    private val auraOffOuterColor: Int = 0x006E6A66
    private val rayColor: Int = 0xFFFFD666.toInt()
    private val checkColor: Int = 0xFF151515.toInt()
    private val checkGlowColor: Int = 0xFF3A2A00.toInt()
    private val crackColor: Int = 0xFF1B1B1B.toInt()
    private val shardColor: Int = 0xFFF6B400.toInt()
    private val sparkleColor: Int = 0xFFFFFFFF.toInt()

    // ==================== ANIMATION STATE ====================
    private var progress = 0f          // 0..1 master timeline
    private var enabling = true
    private var animator: ValueAnimator? = null
    private var endAction: (() -> Unit)? = null

    /** True when no animation is running and the shield is shown at rest. */
    private var resting = true
    private var restingEnabled = false

    // ==================== PAINTS ====================
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shieldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        pathEffect = CornerPathEffect(6f)
    }
    private val bevelLight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFFFE9B0.toInt(); strokeWidth = 3f
    }
    private val bevelDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF8A5E00.toInt(); strokeWidth = 2f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 11f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = checkColor
    }
    private val checkGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 20f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = checkGlowColor
    }
    private val crackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val sparklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    // ==================== GRADIENTS ====================
    private var shieldShaderOn: Shader? = null
    private var shieldShaderOff: Shader? = null
    private var auraShaderOn: Shader? = null
    private var auraShaderOff: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) * 0.34f

        shieldShaderOn = LinearGradient(
            cx - r * 0.7f, cy - r,
            cx + r * 0.7f, cy + r * 1.1f,
            intArrayOf(goldLight, color, goldDeep),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )

        shieldShaderOff = LinearGradient(
            cx - r * 0.7f, cy - r,
            cx + r * 0.7f, cy + r * 1.1f,
            intArrayOf(0xFFBDB9B5.toInt(), offColor),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )

        auraShaderOn = RadialGradient(
            cx, cy, r * 1.85f,
            intArrayOf(auraInnerColor, auraOuterColor),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )

        auraShaderOff = RadialGradient(
            cx, cy, r * 1.85f,
            intArrayOf(auraOffInnerColor, auraOffOuterColor),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
    }

    /** Show the shield at rest, permanently. Called on start and after every toggle. */
    fun setRestingState(enabled: Boolean) {
        animator?.cancel()
        animator = null
        enabling = enabled
        restingEnabled = enabled
        resting = true
        progress = 1f
        invalidate()
    }

    fun play(enable: Boolean, onEnd: () -> Unit) {
        animator?.cancel()
        enabling = enable
        resting = false
        progress = 0f
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
        val r = minOf(w, h) * 0.34f

        if (resting) {
            drawResting(canvas, cx, cy, r, restingEnabled)
            return
        }

        val p = progress
        val accent = if (enabling) color else offColor

        // ---- rotating dotted orbit ----
        val orbitScale = if (enabling) 1f else (1f - p * 0.5f)
        val spin = p * (if (enabling) 2.4f else -1.6f)
        val orbitAlpha = (0.45f + 0.25f * sin(p * PI.toFloat())).coerceIn(0f, 1f)
        for (i in 0 until 36) {
            val a = (i * 10) * PI / 180.0 + spin
            val d = r * 1.35f * orbitScale
            dotPaint.color = accent
            dotPaint.alpha = (orbitAlpha * (if (i % 3 == 0) 220 else 110)).toInt()
            val size = if (i % 3 == 0) 3.2f else 1.8f
            canvas.drawCircle((cx + cos(a) * d).toFloat(), (cy + sin(a) * d).toFloat(), size, dotPaint)
        }

        // ---- aura / pulsing glow ----
        val auraAlpha = if (enabling) (p / 0.5f).coerceIn(0f, 1f) else (1f - p).coerceIn(0f, 1f)
        (if (enabling) auraShaderOn else auraShaderOff)?.let {
            auraPaint.shader = it
            auraPaint.alpha = (auraAlpha * 255).toInt()
            canvas.drawCircle(cx, cy, r * 1.85f, auraPaint)
        }

        // ---- expanding halo rings (when enabling) ----
        if (enabling) {
            for (i in 0..2) {
                val t = (p - i * 0.13f).coerceIn(0f, 1f)
                if (t > 0f) {
                    rayPaint.color = accent
                    rayPaint.alpha = ((1f - t) * 150).toInt()
                    rayPaint.strokeWidth = 6f - 3f * t
                    canvas.drawCircle(cx, cy, r * (0.9f + t * 1.5f), rayPaint)
                }
            }
            // light rays sweep out
            val rayT = ((p - 0.15f) / 0.6f).coerceIn(0f, 1f)
            if (rayT > 0f) {
                for (i in 0 until 8) {
                    val a = (i * 45) * PI / 180.0 + p * 0.4f
                    val inner = r * (1.05f + rayT * 0.35f)
                    val outer = r * (1.15f + rayT * 0.9f)
                    rayPaint.color = accent
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
                scale += 0.08f * sin(t * PI.toFloat())
            }
            drawShield(canvas, cx, cy, r * scale, shieldAppear, enabled = true)
        }

        if (enabling) {
            // check mark draws in stroke by stroke after the shield settles
            val checkT = ((p - 0.45f) / 0.35f).coerceIn(0f, 1f)
            if (checkT > 0f) drawCheck(canvas, cx, cy, r, checkT)

            // sparkles twinkle around
            for (i in 0 until 6) {
                val a = (60 + i * 60) * PI / 180.0 + p * 0.6f
                val d = r * (1.3f + 0.15f * absSin(i * 1.7f + p * 6f))
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
                    val a = (i * 40 + 15) * PI / 180.0
                    val d = r * (0.4f + burst * 2.4f)
                    drawShard(canvas, (cx + cos(a) * d).toFloat(), (cy + sin(a) * d).toFloat(),
                        14f, (a + burst * 2f).toFloat(), (1f - burst).coerceIn(0f, 1f))
                }
                for (i in 0 until 8) {
                    val a = (i * 45) * PI / 180.0
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

    // ------------------------------------------------------------------
    // Resting state: the shield stays on screen after the animation
    // ------------------------------------------------------------------

    private fun drawResting(canvas: Canvas, cx: Float, cy: Float, r: Float, enabled: Boolean) {
        val accent = if (enabled) color else offColor

        // soft aura so the shield sits on the black banner without a plate
        (if (enabled) auraShaderOn else auraShaderOff)?.let {
            auraPaint.shader = it
            auraPaint.alpha = if (enabled) 230 else 120
            canvas.drawCircle(cx, cy, r * 1.85f, auraPaint)
        }

        // static dotted orbit
        for (i in 0 until 32) {
            val a = (i * 11.25) * PI / 180.0 - PI / 2.0
            val d = r * 1.32f
            dotPaint.color = accent
            dotPaint.alpha = if (i % 4 == 0) 140 else 50
            val size = if (i % 4 == 0) 2.9f else 1.6f
            canvas.drawCircle((cx + cos(a) * d).toFloat(), (cy + sin(a) * d).toFloat(), size, dotPaint)
        }

        drawShield(canvas, cx, cy, r, 1f, enabled)

        if (enabled) {
            drawCheck(canvas, cx, cy, r, 1f)
            // tiny sparkles at the sides for a premium touch
            drawSparkle(canvas, cx - r * 1.42f, cy - r * 0.35f, 8f, 0.6f, rayColor)
            drawSparkle(canvas, cx + r * 1.42f, cy + r * 0.30f, 7f, 0.45f, rayColor)
        } else {
            drawCrack(canvas, cx, cy, r, 1f, 1f)
        }
    }

    private fun absSin(v: Float): Float = abs(sin(v.toDouble())).toFloat()

    private fun drawShield(
        canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Float, enabled: Boolean
    ) {
        if (alpha <= 0f) return
        val path = shieldPath(cx, cy, r)
        shieldPaint.shader = if (enabled) shieldShaderOn else shieldShaderOff
        shieldPaint.alpha = (alpha * 255).toInt()
        canvas.drawPath(path, shieldPaint)

        bevelLight.alpha = (alpha * (if (enabled) 230 else 110)).toInt()
        bevelDark.alpha = (alpha * (if (enabled) 170 else 90)).toInt()
        canvas.drawPath(path, bevelLight)
        canvas.drawPath(path, bevelDark)

        // inner gloss
        glossPaint.alpha = (alpha * (if (enabled) 70 else 40)).toInt()
        canvas.drawOval(cx - r * 0.5f, cy - r * 0.68f, cx + r * 0.5f, cy - r * 0.2f, glossPaint)
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
        crackPaint.color = crackColor
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
        shardPaint.color = shardColor
        shardPaint.alpha = (alpha * 235).toInt()
        val p = Path()
        p.moveTo(x + cos(angle) * size, y + sin(angle) * size)
        p.lineTo(x + cos(angle + 2.5f) * size, y + sin(angle + 2.5f) * size)
        p.lineTo(x + cos(angle - 2.5f) * size, y + sin(angle - 2.5f) * size)
        p.close()
        canvas.drawPath(p, shardPaint)
    }

    private fun drawSparkle(canvas: Canvas, x: Float, y: Float, size: Float, alpha: Float, tint: Int = Color.WHITE) {
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
