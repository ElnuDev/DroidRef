package com.xiaopo.flying.sticker

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A text note that moves, scales and rotates like an image.
 */
class NoteSticker(
    text: String,
    textColor: Int = Color.WHITE,
    backgroundColor: Int = 0x99000000.toInt(),
    textSize: Float = DEFAULT_TEXT_SIZE,
) : Sticker() {
    var text: String = text
        set(value) {
            field = value
            relayout()
        }
    var textColor: Int = textColor
        set(value) {
            field = value
            relayout()
        }
    var backgroundColor: Int = backgroundColor
    var textSize: Float = textSize
        set(value) {
            field = value
            relayout()
        }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private lateinit var layout: StaticLayout
    private var boxWidth = 1
    private var boxHeight = 1

    init {
        relayout()
    }

    private fun relayout() {
        textPaint.textSize = textSize
        textPaint.color = textColor
        val shown = text.ifEmpty { " " }
        val maxWidth = (textSize * MAX_WIDTH_EMS).toInt()
        val natural = shown.split('\n').maxOf { ceil(textPaint.measureText(it)).toInt() }
        val layoutWidth = min(max(natural, 1), maxWidth)
        layout = StaticLayout.Builder.obtain(shown, 0, shown.length, textPaint, layoutWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(true)
            .build()
        val padding = padding()
        boxWidth = layoutWidth + 2 * padding
        boxHeight = layout.height + 2 * padding
        realBounds = Rect(0, 0, boxWidth, boxHeight)
        croppedBounds = RectF(realBounds)
    }

    private fun padding() = (textSize * 0.5f).toInt()

    override fun copy(sameIdentity: Boolean): NoteSticker {
        val other = NoteSticker(text, textColor, backgroundColor, textSize)
        other.copyFrom(this)
        other.adoptIdentity(this, sameIdentity)
        return other
    }

    private fun copyFrom(source: Sticker) {
        matrix.set(source.matrix)
        canvasMatrix.set(source.canvasMatrix)
        isFlippedHorizontally = source.isFlippedHorizontally
        isFlippedVertically = source.isFlippedVertically
        isVisible = source.isVisible
        isGrayscale = source.isGrayscale
        isSmooth = source.isSmooth
        isLocked = source.isLocked
        opacity = source.opacity
        groupId = source.groupId
        name = source.name
        comment = source.comment
        this.source = source.source
        addedOrder = source.addedOrder
        recalcFinalMatrix()
    }

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.concat(finalMatrix)
        val filter = if (isGrayscale) GRAYSCALE else null
        backgroundPaint.color = backgroundColor
        backgroundPaint.alpha = Color.alpha(backgroundColor) * opacity / 255
        backgroundPaint.colorFilter = filter
        val radius = textSize * 0.25f
        canvas.drawRoundRect(RectF(realBounds), radius, radius, backgroundPaint)
        textPaint.color = textColor
        textPaint.alpha = Color.alpha(textColor) * opacity / 255
        textPaint.colorFilter = filter
        // Flipping a note mirrors its box but keeps the text readable.
        canvas.scale(
            if (isFlippedHorizontally) -1f else 1f,
            if (isFlippedVertically) -1f else 1f,
            boxWidth / 2f,
            boxHeight / 2f
        )
        val padding = padding().toFloat()
        canvas.translate(padding, padding)
        layout.draw(canvas)
        canvas.restore()
    }

    override fun getWidth() = boxWidth

    override fun getHeight() = boxHeight

    override fun isCroppable() = false

    override fun setAlpha(alpha: Int): Sticker {
        opacity = alpha
        return this
    }

    override fun stateSignature() =
        super.stateSignature() + "|" + text + textColor + "," + backgroundColor + "," + textSize

    companion object {
        const val DEFAULT_TEXT_SIZE = 48f
        private const val MAX_WIDTH_EMS = 16

        private val GRAYSCALE = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }
}
