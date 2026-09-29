package com.xiaopo.flying.sticker

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import kotlin.math.ceil

/**
 * A freehand pen stroke. Points are in the item's local coordinates.
 */
class DrawingSticker private constructor(
    val color: Int,
    val strokeWidth: Float,
    val points: FloatArray,
) : Sticker() {
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val boxWidth: Int
    private val boxHeight: Int

    init {
        buildPath(path, points)
        var maxX = 0f
        var maxY = 0f
        for (i in points.indices step 2) {
            maxX = maxOf(maxX, points[i])
            maxY = maxOf(maxY, points[i + 1])
        }
        boxWidth = maxOf(1, ceil(maxX + strokeWidth / 2).toInt())
        boxHeight = maxOf(1, ceil(maxY + strokeWidth / 2).toInt())
        realBounds = Rect(0, 0, boxWidth, boxHeight)
        croppedBounds = RectF(realBounds)
    }

    override fun copy(sameIdentity: Boolean): DrawingSticker {
        val other = DrawingSticker(color, strokeWidth, points)
        other.matrix.set(matrix)
        other.canvasMatrix.set(canvasMatrix)
        other.isFlippedHorizontally = isFlippedHorizontally
        other.isFlippedVertically = isFlippedVertically
        other.isVisible = isVisible
        other.isGrayscale = isGrayscale
        other.isLocked = isLocked
        other.opacity = opacity
        other.groupId = groupId
        other.name = name
        other.comment = comment
        other.source = source
        other.addedOrder = addedOrder
        other.adoptIdentity(this, sameIdentity)
        other.recalcFinalMatrix()
        return other
    }

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.concat(finalMatrix)
        paint.color = color
        paint.alpha = Color.alpha(color) * opacity / 255
        paint.strokeWidth = strokeWidth
        paint.colorFilter = if (isGrayscale) GRAYSCALE else null
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    override fun getWidth() = boxWidth

    override fun getHeight() = boxHeight

    override fun isCroppable() = false

    override fun setDrawable(drawable: Drawable): Sticker = this

    override fun getDrawable(): Drawable = ColorDrawable(color)

    override fun setAlpha(alpha: Int): Sticker {
        opacity = alpha
        return this
    }

    override fun stateSignature() = super.stateSignature() + "|" + color + "," + strokeWidth

    companion object {
        private val GRAYSCALE = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })

        fun buildPath(path: Path, points: FloatArray) {
            path.reset()
            if (points.size < 2) {
                return
            }
            path.moveTo(points[0], points[1])
            if (points.size == 2) {
                // A single tap still leaves a dot.
                path.lineTo(points[0] + 0.01f, points[1])
                return
            }
            var i = 2
            while (i < points.size) {
                val prevX = points[i - 2]
                val prevY = points[i - 1]
                // Smooth by curving through midpoints.
                path.quadTo(prevX, prevY, (prevX + points[i]) / 2, (prevY + points[i + 1]) / 2)
                i += 2
            }
            path.lineTo(points[points.size - 2], points[points.size - 1])
        }

        /** Builds a sticker from world-space points, positioned where they were drawn. */
        fun fromWorldPoints(color: Int, strokeWidth: Float, world: FloatArray): DrawingSticker {
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            for (i in world.indices step 2) {
                minX = minOf(minX, world[i])
                minY = minOf(minY, world[i + 1])
            }
            val half = strokeWidth / 2
            val local = FloatArray(world.size)
            for (i in world.indices step 2) {
                local[i] = world[i] - minX + half
                local[i + 1] = world[i + 1] - minY + half
            }
            return DrawingSticker(color, strokeWidth, local).also {
                it.matrix.setTranslate(minX - half, minY - half)
            }
        }

        /** Restores a saved drawing whose points are already local. */
        fun fromLocalPoints(color: Int, strokeWidth: Float, local: FloatArray, matrix: Matrix) =
            DrawingSticker(color, strokeWidth, local).also { it.matrix.set(matrix) }
    }
}
