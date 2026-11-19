package com.adblocker.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * Custom motion-graphics view: shield + protection bubble.
 * play(true)  -> rings expand, shield rises, shield settles
 * play(false) -> bubble collapses, shield cracks, particles burst
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

    private val shieldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }
    private val crackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
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
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) * 0.30f
        val p = progress

        val baseColor = if (enabling) onColor else offColor

        if (enabling) {
            // expanding energy rings
            for (i in 0..2) {
                val t = (p - i * 0.12f).coerceIn(0f, 1f)
                if (t > 0f) {
                    ringPaint.color = color
                    ringPaint.alpha = ((1f - t) * 150).toInt()
                    ringPaint.strokeWidth = 6f - 4f * t
                    canvas.drawCircle(cx, cy, r * (0.7f + t * 1.9f), ringPaint)
                }
            }
            val shieldAlpha = (p / 0.38f).coerceIn(0f, 1f) * (1f - ((p - 0.82f) / 0.18f).coerceIn(0f, 1f))
            val scale = 0.55f + 0.45f * (p / 0.38f).coerceIn(0f, 1f)
            drawShield(canvas, cx, cy, r * scale, shieldAlpha, baseColor, cracked = false, crack = 0f)
            // sparkles
            val sparkAlpha = (1f - p) * 220
            for (i in 0 until 10) {
                val a = (i * 36 + p * 40) * Math.PI / 180.0
                val d = r * (1.1f + p * 1.6f)
                particlePaint.color = color
                particlePaint.alpha = sparkAlpha.toInt()
                canvas.drawCircle((cx + cos(a) * d).toFloat(), (cy + sin(a) * d).toFloat(), 4f, particlePaint)
            }
        } else {
            // collapse rings inward
            for (i in 0..2) {
                val t = (p - i * 0.1f).coerceIn(0f, 1f)
                if (t > 0f) {
                    ringPaint.color = offColor
                    ringPaint.alpha = ((1f - t) * 170).toInt()
                    ringPaint.strokeWidth = 5f
                    canvas.drawCircle(cx, cy, r * (2.4f - t * 1.7f), ringPaint)
                }
            }
            val fade = 1f - ((p - 0.55f) / 0.45f).coerceIn(0f, 1f)
            val crack = (p / 0.45f).coerceIn(0f, 1f)
            drawShield(canvas, cx, cy, r, fade, offColor, cracked = true, crack = crack)
            // burst particles
            val burst = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
            for (i in 0 until 12) {
                val a = i * 30 * Math.PI / 180.0
                val d = r * (0.9f + burst * 2.2f)
                particlePaint.color = offColor
                particlePaint.alpha = ((1f - burst) * 230).toInt()
                canvas.drawCircle((cx + cos(a) * d).toFloat(), (cy + sin(a) * d).toFloat(), 5f, particlePaint)
            }
        }

        if (p >= 1f) {
            val action = endAction
            endAction = null
            if (action != null) post { action() }
        }
    }

    private fun drawShield(canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Float, tint: Int, cracked: Boolean, crack: Float) {
        if (alpha <= 0f) return
        val path = Path()
        path.moveTo(cx, cy - r)
        path.lineTo(cx + r * 0.78f, cy - r * 0.62f)
        path.lineTo(cx + r * 0.66f, cy + r * 0.28f)
        path.quadTo(cx + r * 0.48f, cy + r * 0.76f, cx, cy + r)
        path.quadTo(cx - r * 0.48f, cy + r * 0.76f, cx - r * 0.66f, cy + r * 0.28f)
        path.lineTo(cx - r * 0.78f, cy - r * 0.62f)
        path.close()

        shieldPaint.color = tint
        shieldPaint.alpha = (alpha * 210).toInt()
        canvas.drawPath(path, shieldPaint)
        strokePaint.color = Color.WHITE
        strokePaint.alpha = (alpha * 230).toInt()
        strokePaint.strokeWidth = 5f
        canvas.drawPath(path, strokePaint)

        // inner check mark (enable) or crack (disable)
        if (!cracked) {
            checkPaint.alpha = (alpha * 255).toInt()
            val mark = Path()
            mark.moveTo(cx - r * 0.34f, cy)
            mark.lineTo(cx - r * 0.06f, cy + r * 0.28f)
            mark.lineTo(cx + r * 0.38f, cy - r * 0.30f)
            canvas.drawPath(mark, checkPaint)
        } else if (crack > 0f) {
            crackPaint.color = Color.WHITE
            crackPaint.alpha = (alpha * 255).toInt()
            val c = Path()
            c.moveTo(cx - r * 0.55f, cy - r * 0.55f)
            c.lineTo(cx - r * 0.05f, cy - r * 0.12f)
            c.lineTo(cx - r * 0.25f, cy + r * 0.18f)
            c.lineTo(cx + r * 0.30f, cy + r * 0.62f)
            canvas.drawPath(c, crackPaint)
        }
    }
}