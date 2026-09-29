package com.xiaopo.flying.sticker

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Layout algorithms for PureRef-style "Arrange". Works on plain sizes so it can
 * be unit tested off-device. Positions are top-left corners relative to (0, 0).
 */
object Arranger {
    data class Box(val w: Float, val h: Float)
    data class Pos(val x: Float, val y: Float)

    private val WIDTH_FACTORS = floatArrayOf(0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1f, 1.1f, 1.25f, 1.4f, 1.6f, 1.8f, 2.2f)

    /**
     * Packs boxes as tightly as possible into a rectangle close to [aspect]
     * (width / height), like PureRef's "Arrange optimal".
     */
    fun optimal(boxes: List<Box>, gap: Float, aspect: Float): List<Pos> {
        if (boxes.isEmpty()) return emptyList()
        val orders = listOf<Comparator<Int>>(
            compareByDescending { boxes[it].h },
            compareByDescending { boxes[it].w * boxes[it].h },
            compareByDescending { max(boxes[it].w, boxes[it].h) },
            compareByDescending { boxes[it].w },
        )
        var best: List<Pos>? = null
        var bestScore = Float.MAX_VALUE
        for (width in candidateWidths(boxes, gap, aspect)) {
            for (order in orders) {
                val indices = boxes.indices.sortedWith(order)
                val positions = skyline(boxes, indices, gap, width)
                val score = score(boxes, positions, aspect)
                if (score < bestScore) {
                    bestScore = score
                    best = positions
                }
            }
        }
        return best!!
    }

    /**
     * Lays boxes out in reading order (left to right, top to bottom), used for
     * "Arrange by name", "by order" and "randomly".
     */
    fun rows(boxes: List<Box>, gap: Float, aspect: Float): List<Pos> {
        if (boxes.isEmpty()) return emptyList()
        var best: List<Pos>? = null
        var bestScore = Float.MAX_VALUE
        for (width in candidateWidths(boxes, gap, aspect)) {
            val positions = rowsWithWidth(boxes, gap, width)
            val score = score(boxes, positions, aspect)
            if (score < bestScore) {
                bestScore = score
                best = positions
            }
        }
        return best!!
    }

    fun rowsWithWidth(boxes: List<Box>, gap: Float, width: Float): List<Pos> {
        val result = ArrayList<Pos>(boxes.size)
        var x = 0f
        var y = 0f
        var rowHeight = 0f
        for (box in boxes) {
            if (x > 0f && x + box.w > width) {
                x = 0f
                y += rowHeight + gap
                rowHeight = 0f
            }
            result.add(Pos(x, y))
            x += box.w + gap
            rowHeight = max(rowHeight, box.h)
        }
        return result
    }

    /** Width and height of the area covered by the laid out boxes. */
    fun extent(boxes: List<Box>, positions: List<Pos>): Box {
        var w = 0f
        var h = 0f
        for (i in boxes.indices) {
            w = max(w, positions[i].x + boxes[i].w)
            h = max(h, positions[i].y + boxes[i].h)
        }
        return Box(w, h)
    }

    /** Area of the smallest [aspect]-shaped rectangle holding the layout: what has to fit on screen. */
    private fun score(boxes: List<Box>, positions: List<Pos>, aspect: Float): Float {
        val e = extent(boxes, positions)
        val side = max(e.w, e.h * aspect)
        return side * side
    }

    private fun candidateWidths(boxes: List<Box>, gap: Float, aspect: Float): List<Float> {
        val area = boxes.sumOf { ((it.w + gap) * (it.h + gap)).toDouble() }.toFloat()
        val base = sqrt(area * aspect)
        val widest = boxes.maxOf { it.w }
        return (WIDTH_FACTORS.map { max(widest, base * it) } + widest).distinct()
    }

    private class Segment(var x: Float, var y: Float, var w: Float)

    /**
     * Skyline bottom-left packing into a strip of the given width.
     */
    private fun skyline(boxes: List<Box>, order: List<Int>, gap: Float, width: Float): List<Pos> {
        val result = arrayOfNulls<Pos>(boxes.size)
        val binWidth = width + gap
        val skyline = mutableListOf(Segment(0f, 0f, binWidth))
        for (index in order) {
            val w = boxes[index].w + gap
            val h = boxes[index].h + gap
            var bestY = Float.MAX_VALUE
            var bestX = 0f
            var bestI = -1
            for (i in skyline.indices) {
                val x = skyline[i].x
                if (x + w > binWidth + EPSILON) {
                    break
                }
                // The box rests on the highest segment it spans.
                var y = 0f
                var j = i
                var covered = 0f
                while (covered < w - EPSILON && j < skyline.size) {
                    y = max(y, skyline[j].y)
                    covered = skyline[j].x + skyline[j].w - x
                    j++
                }
                if (covered < w - EPSILON) {
                    continue
                }
                if (y + h < bestY - EPSILON || (y + h < bestY + EPSILON && x < bestX)) {
                    bestY = y + h
                    bestX = x
                    bestI = i
                }
            }
            if (bestI < 0) {
                // Wider than the strip: start a new row below everything.
                val top = skyline.maxOf { it.y }
                result[index] = Pos(0f, top)
                skyline.clear()
                skyline.add(Segment(0f, top + h, max(binWidth, w)))
                continue
            }
            val y = bestY - h
            result[index] = Pos(bestX, y)
            place(skyline, bestX, w, bestY)
        }
        return result.map { it!! }
    }

    private fun place(skyline: MutableList<Segment>, x: Float, w: Float, top: Float) {
        val end = x + w
        val updated = mutableListOf<Segment>()
        var inserted = false
        for (s in skyline) {
            val sEnd = s.x + s.w
            if (sEnd <= x + EPSILON || s.x >= end - EPSILON) {
                if (!inserted && s.x >= end - EPSILON) {
                    updated.add(Segment(x, top, w))
                    inserted = true
                }
                updated.add(s)
                continue
            }
            // Overlaps the placed box: keep the parts sticking out on either side.
            if (s.x < x) {
                updated.add(Segment(s.x, s.y, x - s.x))
            }
            if (!inserted) {
                updated.add(Segment(x, top, w))
                inserted = true
            }
            if (sEnd > end) {
                updated.add(Segment(end, s.y, sEnd - end))
            }
        }
        if (!inserted) {
            updated.add(Segment(x, top, w))
        }
        // Merge neighbours at the same height.
        skyline.clear()
        for (s in updated) {
            val last = skyline.lastOrNull()
            if (last != null && kotlin.math.abs(last.y - s.y) < EPSILON) {
                last.w = s.x + s.w - last.x
            } else {
                skyline.add(s)
            }
        }
    }

    private const val EPSILON = 0.01f
}
