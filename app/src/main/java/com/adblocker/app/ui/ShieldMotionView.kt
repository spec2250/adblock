package com.adblocker.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

class ShieldMotionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var color: Int = GOLD

    private var enabled = false
    private var running = false
    private var progress = 0f
    private var restingProgress = 0f
    private var animator: ValueAnimator? = null
    private var restingAnimator: ValueAnimator? = null
    private var endAction: (() -> Unit)? = null

    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            160f, 160f, 160f,
            intArrayOf(0x66F6B400, 0x1FF6B400, 0x00F6B400),
            floatArrayOf(0f, 0.42f, 0.68f),
            Shader.TileMode.CLAMP
        )
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF151515.toInt()
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val crackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF1B1300.toInt()
        strokeWidth = 8f
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.BEVEL
    }

    private val outerPath = Path().apply {
        moveTo(160f, 38f)
        cubicTo(122f, 38f, 88f, 49f, 63f, 65f)
        lineTo(63f, 159f)
        cubicTo(63f, 192f, 76f, 215f, 99f, 234f)
        lineTo(160f, 280f)
        lineTo(221f, 234f)
        cubicTo(244f, 215f, 257f, 192f, 257f, 159f)
        lineTo(257f, 65f)
        cubicTo(232f, 49f, 198f, 38f, 160f, 38f)
        close()
    }
    private val innerPath = Path().apply {
        moveTo(160f, 66f)
        cubicTo(126f, 66f, 98f, 75f, 82f, 86f)
        lineTo(82f, 158f)
        cubicTo(82f, 185f, 92f, 204f, 112f, 219f)
        lineTo(160f, 255f)
        lineTo(208f, 219f)
        cubicTo(228f, 204f, 238f, 185f, 238f, 158f)
        lineTo(238f, 86f)
        cubicTo(222f, 75f, 194f, 66f, 160f, 66f)
        close()
    }
    private val activeOuterPath = shieldContour(38f, 63f, 257f, 280f)
    private val activeMiddlePath = shieldContour(63f, 84f, 236f, 258f)
    private val activeInnerPath = shieldContour(87f, 103f, 217f, 236f)
    private val checkPath = Path().apply {
        moveTo(93f, 147f)
        lineTo(158f, 211f)
        lineTo(266f, 91f)
    }
    private val crackPath = Path().apply {
        moveTo(160f, 67f)
        lineTo(149f, 91f)
        lineTo(169f, 111f)
        lineTo(150f, 134f)
        lineTo(169f, 155f)
        lineTo(150f, 177f)
        lineTo(169f, 198f)
        lineTo(150f, 220f)
        lineTo(160f, 252f)
    }
    private val leftClip = Path().apply {
        moveTo(160f, 0f)
        lineTo(160f, 67f)
        lineTo(149f, 91f)
        lineTo(169f, 111f)
        lineTo(150f, 134f)
        lineTo(169f, 155f)
        lineTo(150f, 177f)
        lineTo(169f, 198f)
        lineTo(150f, 220f)
        lineTo(160f, 252f)
        lineTo(160f, 320f)
        lineTo(0f, 320f)
        lineTo(0f, 0f)
        close()
    }
    private val rightClip = Path().apply {
        moveTo(160f, 0f)
        lineTo(320f, 0f)
        lineTo(320f, 320f)
        lineTo(160f, 320f)
        lineTo(160f, 252f)
        lineTo(150f, 220f)
        lineTo(169f, 198f)
        lineTo(150f, 177f)
        lineTo(169f, 155f)
        lineTo(150f, 134f)
        lineTo(169f, 111f)
        lineTo(149f, 91f)
        lineTo(160f, 67f)
        close()
    }
    private val crackLength = PathMeasure(crackPath, false).length

    fun setRestingState(isEnabled: Boolean) {
        animator?.cancel()
        animator = null
        endAction = null
        enabled = isEnabled
        running = false
        progress = 0f
        restingProgress = 0f
        startRestingAnimation()
        invalidate()
    }

    fun play(enable: Boolean, onEnd: () -> Unit) {
        restingAnimator?.cancel()
        restingAnimator = null
        animator?.cancel()
        enabled = enable
        running = true
        progress = 0f
        endAction = onEnd
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (enable) 1250L else 3800L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled || animator !== animation) return
                    animator = null
                    running = false
                    restingProgress = 0f
                    startRestingAnimation()
                    invalidate()
                    endAction?.also {
                        endAction = null
                        it()
                    }
                }
            })
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startRestingAnimation()
    }

    override fun onDetachedFromWindow() {
        restingAnimator?.cancel()
        restingAnimator = null
        animator?.cancel()
        animator = null
        running = false
        progress = 0f
        endAction = null
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            startRestingAnimation()
        } else {
            restingAnimator?.cancel()
            restingAnimator = null
        }
    }

    private fun startRestingAnimation() {
        if (running || !isAttachedToWindow || restingAnimator != null) return
        restingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3800L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                restingProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val cycle = if (running) progress else restingProgress
        val breathe = (0.5f + 0.5f * sin(cycle * 2f * PI.toFloat()))
        val scale = min(width, height) / 320f

        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(scale, scale)
        canvas.translate(-160f, -160f)
        canvas.save()
        canvas.scale(0.96f + 0.10f * breathe, 0.96f + 0.10f * breathe, 160f, 160f)
        auraPaint.alpha = (140f + 115f * breathe).toInt()
        canvas.drawCircle(160f, 160f, 160f, auraPaint)
        canvas.restore()

        if (enabled) {
            drawActiveShield(canvas, breathe)
        } else {
            drawBrokenShield(canvas, cycle)
        }
        canvas.restore()
    }

    private fun drawActiveShield(canvas: Canvas, breathe: Float) {
        val appear = if (running) ((progress - 0.08f) / 0.42f).coerceIn(0f, 1f) else 1f
        val breathingScale = 0.98f + 0.035f * breathe
        val scale = (0.72f + 0.28f * appear) * breathingScale
        canvas.save()
        canvas.scale(scale, scale, 160f, 160f)

        outlinePaint.shader = LinearGradient(
            64f, 38f, 256f, 280f,
            intArrayOf(0xFFFFD666.toInt(), color, 0xFFE39A00.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        outlinePaint.strokeWidth = 11f
        outlinePaint.alpha = (255f * appear).toInt()
        outlinePaint.setShadowLayer(18f, 0f, 8f, 0x2EF6B400)
        canvas.drawPath(activeOuterPath, outlinePaint)
        canvas.drawPath(activeMiddlePath, outlinePaint)
        canvas.drawPath(activeInnerPath, outlinePaint)
        outlinePaint.clearShadowLayer()
        outlinePaint.shader = null

        val checkProgress = if (running) ((progress - 0.42f) / 0.36f).coerceIn(0f, 1f) else 1f
        if (checkProgress > 0f) {
            val length = PathMeasure(checkPath, false).length
            checkPaint.pathEffect = DashPathEffect(
                floatArrayOf(length * checkProgress, length * (1f - checkProgress) + 1f),
                0f
            )
            checkPaint.alpha = (255f * appear).toInt()
            canvas.drawPath(checkPath, checkPaint)
            checkPaint.pathEffect = null
        }
        canvas.restore()
    }

    private fun drawBrokenShield(canvas: Canvas, cycle: Float) {
        val crackProgress = when {
            cycle < 0.08f || cycle >= 0.84f -> 0f
            cycle < 0.34f -> (cycle - 0.08f) / 0.26f
            cycle < 0.72f -> 1f
            else -> 1f - (cycle - 0.72f) / 0.12f
        }.coerceIn(0f, 1f)
        val pieceProgress = when {
            cycle < 0.44f || cycle >= 0.94f -> 0f
            cycle < 0.60f -> (cycle - 0.44f) / 0.16f
            cycle < 0.78f -> 1f
            else -> 1f - (cycle - 0.78f) / 0.16f
        }.coerceIn(0f, 1f)
        val sweepAlpha = when {
            cycle < 0.22f || cycle >= 0.84f -> 0f
            cycle < 0.30f -> (cycle - 0.22f) / 0.08f
            cycle < 0.68f -> 1f
            else -> 1f - (cycle - 0.68f) / 0.16f
        }.coerceIn(0f, 1f)
        val offset = 12f * pieceProgress

        drawShieldPiece(canvas, -offset, true, cycle, sweepAlpha)
        drawShieldPiece(canvas, offset, false, cycle, sweepAlpha)

        if (crackProgress > 0f) {
            crackPaint.pathEffect = DashPathEffect(
                floatArrayOf(crackLength * crackProgress, crackLength * (1f - crackProgress) + 1f),
                0f
            )
            crackPaint.alpha = (230f * crackProgress).toInt()
            crackPaint.setShadowLayer(5f, 0f, 0f, GOLD)
            canvas.drawPath(crackPath, crackPaint)
            crackPaint.clearShadowLayer()
            crackPaint.pathEffect = null
        }
    }

    private fun drawShieldPiece(
        canvas: Canvas,
        offset: Float,
        left: Boolean,
        cycle: Float,
        sweepAlpha: Float
    ) {
        val save = canvas.save()
        canvas.translate(offset, 0f)
        canvas.clipPath(if (left) leftClip else rightClip)

        outlinePaint.shader = LinearGradient(
            0f, 0f, 320f, 320f,
            intArrayOf(0xFFFFD666.toInt(), 0xFFE39A00.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        outlinePaint.strokeWidth = 10f
        outlinePaint.alpha = 255
        canvas.drawPath(outerPath, outlinePaint)
        canvas.drawPath(innerPath, outlinePaint)

        if (sweepAlpha > 0f) {
            val travel = cycle * 640f - 320f
            outlinePaint.shader = LinearGradient(
                travel, 0f, travel + 72f, 320f,
                intArrayOf(
                    0x00FFD666,
                    0x40FFD666,
                    0xE6FFF4CC.toInt(),
                    0x40FFD666,
                    0x00FFD666
                ),
                floatArrayOf(0f, 0.42f, 0.52f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
            outlinePaint.strokeWidth = 8f
            outlinePaint.alpha = (230f * sweepAlpha).toInt()
            outlinePaint.setShadowLayer(5f, 0f, 0f, 0xBFF6B400.toInt())
            canvas.drawPath(outerPath, outlinePaint)
            canvas.drawPath(innerPath, outlinePaint)
            outlinePaint.clearShadowLayer()
        }
        outlinePaint.shader = null
        canvas.restoreToCount(save)
    }

    private fun shieldContour(top: Float, left: Float, right: Float, bottom: Float) =
        Path().apply {
            val center = (left + right) / 2f
            moveTo(center, top)
            cubicTo(center - 38f, top, left + 10f, top + 11f, left, top + 27f)
            lineTo(left, top + 94f)
            cubicTo(left, top + 127f, left + 13f, top + 150f, left + 36f, top + 169f)
            lineTo(center, bottom)
            lineTo(right - 36f, top + 169f)
            cubicTo(right - 13f, top + 150f, right, top + 127f, right, top + 94f)
            lineTo(right, top + 27f)
            cubicTo(right - 25f, top + 11f, center + 38f, top, center, top)
        }

    private companion object {
        val GOLD: Int = 0xFFF6B400.toInt()
    }
}
