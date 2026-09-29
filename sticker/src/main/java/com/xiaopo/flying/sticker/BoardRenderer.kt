package com.xiaopo.flying.sticker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Draws what isn't an item: the grid, selection outlines, the rubber band,
 * the stroke being drawn and the color picker loupe.
 */
class BoardRenderer(context: Context) {
    private val density = context.resources.displayMetrics.density

    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = SELECTION_COLOR
    }
    private val lockedPaint = Paint(selectionPaint).apply {
        color = LOCKED_COLOR
        pathEffect = DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
    }
    private val groupPaint = Paint(selectionPaint).apply {
        strokeWidth = density
        pathEffect = DashPathEffect(floatArrayOf(4 * density, 4 * density), 0f)
    }
    private val marqueeFill = Paint().apply { color = ColorUtils.setAlphaComponent(SELECTION_COLOR, 40) }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 213, 79) }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val loupeFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val loupeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
    }
    private val loupeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14 * density
        textAlign = Paint.Align.CENTER
        setShadowLayer(3 * density, 0f, 0f, Color.BLACK)
        color = Color.WHITE
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 16 * density
        textAlign = Paint.Align.CENTER
    }

    private val points = FloatArray(8)
    private val mapped = FloatArray(8)
    private val path = Path()

    fun drawGrid(canvas: Canvas, viewModel: StickerViewModel, width: Int, height: Int) {
        val mode = viewModel.gridMode.value ?: return
        if (mode == StickerViewModel.GridMode.NONE || width == 0) return
        val scale = viewModel.canvasScale()
        var step = StickerViewModel.GRID_SIZE
        // Coarsen the grid when zoomed out so it doesn't turn into noise.
        while (step * scale < MIN_GRID_SPACING * density) step *= 2
        val world = viewModel.visibleWorld()
        val background = viewModel.backgroundColor.value ?: StickerViewModel.DEFAULT_BACKGROUND
        val contrast = if (ColorUtils.calculateLuminance(background) > 0.5) Color.BLACK else Color.WHITE
        gridPaint.color = ColorUtils.setAlphaComponent(contrast, if (mode == StickerViewModel.GridMode.DOTS) 90 else 28)
        gridPaint.strokeWidth = if (mode == StickerViewModel.GridMode.DOTS) 3 * density else density

        val m = viewModel.canvasMatrix.value!!.getMatrix()
        val startX = floor(world.left / step) * step
        val startY = floor(world.top / step) * step
        val columns = ceil((world.right - startX) / step).toInt() + 1
        val rows = ceil((world.bottom - startY) / step).toInt() + 1
        val p = FloatArray(2)
        if (mode == StickerViewModel.GridMode.LINES) {
            for (i in 0..columns) {
                p[0] = startX + i * step; p[1] = 0f
                m.mapPoints(p)
                canvas.drawLine(p[0], 0f, p[0], height.toFloat(), gridPaint)
            }
            for (j in 0..rows) {
                p[0] = 0f; p[1] = startY + j * step
                m.mapPoints(p)
                canvas.drawLine(0f, p[1], width.toFloat(), p[1], gridPaint)
            }
        } else {
            gridPaint.strokeCap = Paint.Cap.ROUND
            val dots = FloatArray((columns + 1) * (rows + 1) * 2)
            var k = 0
            for (i in 0..columns) for (j in 0..rows) {
                dots[k++] = startX + i * step
                dots[k++] = startY + j * step
            }
            m.mapPoints(dots)
            canvas.drawPoints(dots, gridPaint)
        }
    }

    fun drawOverlay(canvas: Canvas, viewModel: StickerViewModel, stickers: List<Sticker>) {
        selectionPaint.color = viewModel.accentColor
        groupPaint.color = viewModel.accentColor
        marqueeFill.color = ColorUtils.setAlphaComponent(viewModel.accentColor, 40)
        if (stickers.isEmpty()) {
            drawEmptyHint(canvas, viewModel)
        }
        val canvasMatrix = viewModel.canvasMatrix.value!!.getMatrix()
        if (viewModel.isLocked.value != true) {
            drawSelection(canvas, viewModel, canvasMatrix)
        }
        drawBadges(canvas, stickers)

        viewModel.marquee?.let {
            canvas.drawRect(it, marqueeFill)
            canvas.drawRect(it, groupPaint)
        }

        viewModel.currentStroke()?.let { stroke ->
            canvas.save()
            canvas.concat(canvasMatrix)
            DrawingSticker.buildPath(path, stroke)
            strokePaint.color = viewModel.penColor
            strokePaint.strokeWidth = viewModel.penWidth / viewModel.canvasScale()
            canvas.drawPath(path, strokePaint)
            canvas.restore()
        }

        viewModel.pickPoint?.let { p ->
            val radius = 28 * density
            // Above the finger, unless that would put it off the top of the screen.
            val above = p.y - 72 * density
            val cy = if (above - radius - 30 * density < 0) p.y + 72 * density else above
            loupeFill.color = viewModel.pickedColor or 0xFF000000.toInt()
            loupeRing.color = if (ColorUtils.calculateLuminance(loupeFill.color) > 0.5) Color.BLACK else Color.WHITE
            canvas.drawCircle(p.x, cy, radius, loupeFill)
            canvas.drawCircle(p.x, cy, radius, loupeRing)
            canvas.drawCircle(p.x, p.y, 6 * density, loupeRing)
            canvas.drawText(hex(viewModel.pickedColor), p.x, cy - radius - 8 * density, loupeText)
        }
    }

    private fun drawEmptyHint(canvas: Canvas, viewModel: StickerViewModel) {
        val hint = viewModel.emptyHint ?: return
        val background = viewModel.backgroundColor.value ?: StickerViewModel.DEFAULT_BACKGROUND
        val contrast = if (ColorUtils.calculateLuminance(background) > 0.5) Color.BLACK else Color.WHITE
        val text = if (viewModel.emptyHintColor != 0) viewModel.emptyHintColor else contrast
        hintPaint.color = ColorUtils.setAlphaComponent(text, 170)
        val lines = hint.split('\n')
        val lineHeight = hintPaint.fontSpacing
        var y = viewModel.viewHeight / 2f - lineHeight * (lines.size - 1) / 2
        for (line in lines) {
            canvas.drawText(line, viewModel.viewWidth / 2f, y, hintPaint)
            y += lineHeight
        }
    }

    private fun outline(sticker: Sticker): Path {
        sticker.getCroppedBoundPoints(points)
        sticker.getMappedPoints(mapped, points)
        path.reset()
        path.moveTo(mapped[0], mapped[1])
        path.lineTo(mapped[2], mapped[3])
        path.lineTo(mapped[6], mapped[7])
        path.lineTo(mapped[4], mapped[5])
        path.close()
        return path
    }

    private fun drawSelection(canvas: Canvas, viewModel: StickerViewModel, canvasMatrix: Matrix) {
        val selected = viewModel.selected()
        if (selected.isEmpty()) return
        for (sticker in selected) {
            canvas.drawPath(outline(sticker), if (sticker.isLocked) lockedPaint else selectionPaint)
        }
        if (selected.size > 1) {
            val union = RectF(selected[0].worldBounds)
            selected.forEach { union.union(it.worldBounds) }
            canvasMatrix.mapRect(union)
            union.inset(-4 * density, -4 * density)
            canvas.drawRect(union, groupPaint)
        }
    }

    /** A dot on items that have a comment. */
    private fun drawBadges(canvas: Canvas, stickers: List<Sticker>) {
        for (sticker in stickers) {
            if (sticker.comment.isNullOrEmpty() || !sticker.isVisible) continue
            sticker.getCroppedBoundPoints(points)
            sticker.getMappedPoints(mapped, points)
            canvas.drawCircle(mapped[2], mapped[3], 5 * density, badgePaint)
        }
    }

    companion object {
        const val SELECTION_COLOR = 0xFF4FC3F7.toInt()
        const val LOCKED_COLOR = 0xFFFFB74D.toInt()
        private const val MIN_GRID_SPACING = 12f

        fun hex(color: Int) = "#%06X".format(color and 0xFFFFFF)
    }
}
