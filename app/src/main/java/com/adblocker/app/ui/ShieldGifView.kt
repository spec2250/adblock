package com.adblocker.app.ui

import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Movie
import android.graphics.Paint
import android.view.View
import android.view.animation.AnimationUtils

class ShieldGifView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val movie: Movie = context.assets.open("shield-animated-centered.gif").use {
        Movie.decodeStream(it)
            ?: throw IllegalStateException("Unable to decode shield-animated-centered.gif")
    }
    private var animating = false
    private var startedAt = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(
            // Treat the source's white canvas as transparent and recolor its strokes gold.
            ColorMatrix(
                floatArrayOf(
                    0f, 0f, 0f, 0f, 246f,
                    0f, 0f, 0f, 0f, 180f,
                    0f, 0f, 0f, 0f, 0f,
                    -1f, -1f, -1f, 0f, 765f
                )
            )
        )
        setShadowLayer(
            18f * resources.displayMetrics.density,
            0f,
            8f * resources.displayMetrics.density,
            0x2EF6B400
        )
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setAnimating(enabled: Boolean) {
        if (animating == enabled) return
        animating = enabled
        startedAt = 0L
        if (enabled) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!animating || movie.width() == 0 || movie.height() == 0) return

        val duration = movie.duration().coerceAtLeast(1)
        val now = AnimationUtils.currentAnimationTimeMillis()
        if (startedAt == 0L) startedAt = now
        movie.setTime(((now - startedAt) % duration).toInt())

        val scale = minOf(
            width.toFloat() / movie.width(),
            height.toFloat() / movie.height()
        )
        val drawWidth = movie.width() * scale
        val drawHeight = movie.height() * scale
        canvas.save()
        canvas.translate((width - drawWidth) / 2f, (height - drawHeight) / 2f)
        canvas.scale(scale, scale)
        movie.draw(canvas, 0f, 0f, paint)
        canvas.restore()
        postInvalidateOnAnimation()
    }
}
