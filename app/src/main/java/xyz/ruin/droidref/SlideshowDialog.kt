package xyz.ruin.droidref

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.xiaopo.flying.sticker.Sticker
import kotlin.math.min

/**
 * Shows items one at a time, full screen, advancing on a timer like PureRef's
 * slideshow. Tap the left or right third to step, the middle to pause.
 */
class SlideshowDialog(
    context: Context,
    stickers: List<Sticker>,
    private val intervalMillis: Long,
) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
    private val slides = stickers.map { it.copy(true) }
    private val view = SlideView(context)

    init {
        setContentView(view)
    }

    private inner class SlideView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private var index = 0
        private var paused = false
        private var shownAt = SystemClock.uptimeMillis()
        private var elapsedWhenPaused = 0L
        private val camera = Matrix()
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 16 * density
            textAlign = Paint.Align.CENTER
            setShadowLayer(3 * density, 0f, 0f, Color.BLACK)
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3 * density
            color = Color.WHITE
        }
        private val ring = RectF()

        init {
            contentDescription = "Slideshow"
        }

        private fun elapsed() =
            if (paused) elapsedWhenPaused else SystemClock.uptimeMillis() - shownAt

        private fun step(delta: Int) {
            index = Math.floorMod(index + delta, slides.size)
            shownAt = SystemClock.uptimeMillis()
            elapsedWhenPaused = 0
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            if (slides.isEmpty()) return
            if (!paused && elapsed() >= intervalMillis) {
                step(1)
            }
            val slide = slides[index]
            val bounds = slide.worldBounds
            val margin = 16 * density
            val scale = min(
                (width - 2 * margin) / bounds.width(),
                (height - 2 * margin) / bounds.height()
            )
            camera.setTranslate(-bounds.centerX(), -bounds.centerY())
            camera.postScale(scale, scale)
            camera.postTranslate(width / 2f, height / 2f)
            slide.setCanvasMatrix(camera)
            slide.draw(canvas)

            val label = buildString {
                append(index + 1).append(" / ").append(slides.size)
                slide.name?.let { append("  ·  ").append(it) }
                if (paused) append("  ·  paused")
            }
            canvas.drawText(label, width / 2f, height - 24 * density, textPaint)
            val r = 14 * density
            ring.set(width - margin - 2 * r, margin, width - margin, margin + 2 * r)
            val progress = (elapsed().toFloat() / intervalMillis).coerceIn(0f, 1f)
            canvas.drawArc(ring, -90f, 360f * progress, false, ringPaint)
            if (!paused) postInvalidateOnAnimation()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                when {
                    event.x < width / 3f -> step(-1)
                    event.x > width * 2 / 3f -> step(1)
                    else -> {
                        if (paused) {
                            shownAt = SystemClock.uptimeMillis() - elapsedWhenPaused
                        } else {
                            elapsedWhenPaused = elapsed()
                        }
                        paused = !paused
                        invalidate()
                    }
                }
                performClick()
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }
}
