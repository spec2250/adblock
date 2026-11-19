package com.adblocker.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
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
 * Motion graphics in the style of the app icon:
 * rounded dark plate + cream-to-gold gradient shield + dotted orbit + sparkles.
 */
class ShieldMotionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var color: Int = Color.parseColor("#D4A574")
    var onColor: Int = Color.parseColor("#7FA87F")
    var offColor: Int = Color.parseColor("#A87070")

    private var progress = 0f
    private var enabling = true
    private var animator: ValueAnimator? = null
    private var endAction: (() -> Unit)? = null

    private var shieldShader: Shader? = null
    private var bgShader: Shader? = null
    private var bubbleShader: Shader? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shieldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokeLight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FFECCD")
        strokeWidth = 7f
    }
    private val strokeDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#784E2C")
        strokeWidth = 3f
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sparklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val checkGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 18f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#402A1E")
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#2A241E")
    }
    private val crackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 9f
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) * 0.30f
        bgShader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            Color.parseColor("#2E251C"), Color.parseColor("#0F0D0B"), Shader.TileMode.CLAMP)
        shieldShader = LinearGradient(cx, cy - r, cx, cy + r,
            Color.parseColor("#FFF4E9"), color, Shader.TileMode.CLAMP)
        bubbleShader = RadialGradient(cx, cy, 0f, cx, cy, r * 2.4f,
            Color.argb(110, 212, 165, 116), Color.argb(20, 212, 165, 116), Shader.TileMode.CLAMP)
    }

    fun play(enable: Boolean, onEnd: () -> Unit) {
        animator?.cancel()
        enabling = enable
        endAction = onEnd
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100L
            interpolator = DecelerateInterpolator(1.4f)
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
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) * 0.30f
        val p = progress

        // rounded dark plate (icon background style)
        bgPaint.shader = bgShader
        val pad = minOf(w, h) * 0.03f
        canvas.drawRoundRect(RectF(pad, pad, w - pad, h - pad), r * 0.42f, r * 0.42f, bgPaint)

        // dotted orbit (icon style)
        val orbitAlpha = (0.35f + 0.25f * sin(p * Math.PI.toFloat())).coerceIn(0f, 1f)
        for (i in 0 until 24) {
            val a = (i * 15) * Math.PI / 180.0
            val d = r * 1.62f
            dotPaint.color = color
            dotPaint.alpha = (orbitAlpha * 200).toInt()
            canvas.drawCircle((cx + cos(a) * d).toFloat(), (cy + sin(a) * d).toFloat(), 2.5f, dotPaint)
        }

        if (enabling) {
            // protection bubble (grows with the shield)
            bubblePaint.shader = bubbleShader
            bubblePaint.alpha = ((p / 0.5f).coerceIn(0f, 1f) * 255).toInt()
            canvas.drawCircle(cx, cy, r * (1.0f + p * 1.5f), bubblePaint)
            // expanding protection rings
            for (i in 0..2) {
                val t = (p - i * 0.12f).coerceIn(0f, 1f)
                if (t > 0f) {
                    ringPaint.color = color
                    ringPaint.alpha = ((1f - t) * 160).toInt()
                    ringPaint.strokeWidth = 7f - 4f * t
                    canvas.drawCircle(cx, cy, r * (0.7f + t * 1.9f), ringPaint)
                }
            }
            val shieldAlpha = (p / 0.38f).coerceIn(0f, 1f) *
                    (1f - ((p - 0.84f) / 0.16f).coerceIn(0f, 1f))
            val scale = 0.55f + 0.45f * (p / 0.38f).coerceIn(0f, 1f)
            drawShield(canvas, cx, cy, r * scale, shieldAlpha, check = true, crack = 0f)
            // 4 sparkles
            for (i in 0 until 4) {
                val a = (45 + i * 90) * Math.PI / 180.0
                val d = r * 1.75f
                drawSparkle(canvas, (cx + cos(a) * d).toFloat(), (cy + sin(a) * d).toFloat(),
                    12f, (1f - p * 0.7f).coerceIn(0f, 1f))
            }
        } else {
            // bubble collapses inward then pops
            bubblePaint.shader = bubbleShader
            bubblePaint.alpha = ((1f - p) * 220).toInt()
            canvas.drawCircle(cx, cy, r * (2.5f - p * 1.6f), bubblePaint)
            // collapsing rings
            for (i in 0..2) {
                val t = (p - i * 0.1f).coerceIn(0f, 1f)
                if (t > 0f) {
                    ringPaint.color = offColor
                    ringPaint.alpha = ((1f - t) * 180).toInt()
                    ringPaint.strokeWidth = 6f
                    canvas.drawCircle(cx, cy, r * (2.4f - t * 1.7f), ringPaint)
                }
            }
            val fade = 1f - ((p - 0.55f) / 0.45f).coerceIn(0f, 1f)
            drawShield(canvas, cx, cy, r, fade, check = false, crack = (p / 0.45f).coerceIn(0f, 1f))
            // explosion: sparkles + shield shards fly outward
            val burst = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
            for (i in 0 until 10) {
                val a = (i * 36) * Math.PI / 180.0
                val d = r * (1.0f + burst * 2.2f)
                drawSparkle(canvas, (cx + cos(a) * d).toFloat(), (cy + sin(a) * d).toFloat(),
                    10f, (1f - burst).coerceIn(0f, 1f), offColor)
            }
            for (i in 0 until 7) {
                val a = (i * 51 + 12) * Math.PI / 180.0
                val d = r * (0.5f + burst * 2.6f)
                drawShard(canvas, (cx + cos(a) * d).toFloat(), (cy + sin(a) * d).toFloat(),
                    14f, a.toFloat(), (1f - burst).coerceIn(0f, 1f))
            }
        }

        if (p >= 1f) {
            val action = endAction
            endAction = null
            if (action != null) post { action() }
        }
    }

    private fun drawShield(canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Float, check: Boolean, crack: Float) {
        if (alpha <= 0f) return
        val path = shieldPath(cx, cy, r)
        shieldPaint.shader = shieldShader
        shieldPaint.alpha = (alpha * 255).toInt()
        canvas.drawPath(path, shieldPaint)
        strokeLight.alpha = (alpha * 255).toInt()
        strokeDark.alpha = (alpha * 180).toInt()
        canvas.drawPath(path, strokeLight)
        canvas.drawPath(path, strokeDark)

        // inner highlight
        val hl = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.alpha = (alpha * 55).toInt()
            style = Paint.Style.FILL
        }
        canvas.drawOval(cx - r * 0.52f, cy - r * 0.72f, cx + r * 0.52f, cy - r * 0.18f, hl)

        if (check) {
            val mark = checkPath(cx, cy, r)
            checkGlow.alpha = (alpha * 110).toInt()
            checkPaint.alpha = (alpha * 255).toInt()
            canvas.drawPath(mark, checkGlow)
            canvas.drawPath(mark, checkPaint)
        } else if (crack > 0f) {
            val c = Path()
            c.moveTo(cx - r * 0.55f, cy - r * 0.55f)
            c.lineTo(cx - r * 0.05f, cy - r * 0.12f)
            c.lineTo(cx - r * 0.25f, cy + r * 0.18f)
            c.lineTo(cx + r * 0.30f, cy + r * 0.62f)
            crackPaint.color = Color.parseColor("#2A241E")
            crackPaint.strokeWidth = 14f
            crackPaint.alpha = (alpha * 255 * crack).toInt()
            canvas.drawPath(c, crackPaint)
            crackPaint.color = Color.WHITE
            crackPaint.strokeWidth = 5f
            crackPaint.alpha = (alpha * 255 * crack).toInt()
            canvas.drawPath(c, crackPaint)
        }
    }

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
        mark.moveTo(cx - r * 0.36f, cy + 8f)
        mark.lineTo(cx - r * 0.06f, cy + r * 0.32f)
        mark.lineTo(cx + r * 0.42f, cy - r * 0.34f)
        return mark
    }

    private fun drawShard(canvas: Canvas, x: Float, y: Float, size: Float, angle: Float, alpha: Float) {
        if (alpha <= 0f) return
        shardPaint.color = color
        shardPaint.alpha = (alpha * 230).toInt()
        val p = Path()
        p.moveTo(x + cos(angle) * size, y + sin(angle) * size)
        p.lineTo(x + cos(angle + 2.4f) * size, y + sin(angle + 2.4f) * size)
        p.lineTo(x + cos(angle - 2.4f) * size, y + sin(angle - 2.4f) * size)
        p.close()
        canvas.drawPath(p, shardPaint)
    }

    private fun drawSparkle(canvas: Canvas, x: Float, y: Float, size: Float, alpha: Float, tint: Int = color) {
        if (alpha <= 0f) return
        sparklePaint.color = tint
        sparklePaint.alpha = (alpha * 255).toInt()
        val p = Path()
        p.moveTo(x, y - size)
        p.lineTo(x + size * 0.22f, y - size * 0.22f)
        p.lineTo(x + size, y)
        p.lineTo(x + size * 0.22f, y + size * 0.22f)
        p.lineTo(x, y + size)
        p.lineTo(x - size * 0.22f, y + size * 0.22f)
        p.lineTo(x - size, y)
        p.lineTo(x - size * 0.22f, y - size * 0.22f)
        p.close()
        canvas.drawPath(p, sparklePaint)
    }
}