package com.xiaopo.flying.sticker

import com.xiaopo.flying.sticker.Arranger.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ArrangerTest {
    private fun randomBoxes(seed: Int, n: Int): List<Box> {
        val random = Random(seed)
        return List(n) { Box(50f + random.nextFloat() * 950f, 50f + random.nextFloat() * 950f) }
    }

    private fun assertNoOverlap(boxes: List<Box>, positions: List<Arranger.Pos>, gap: Float) {
        assertEquals(boxes.size, positions.size)
        for (i in boxes.indices) {
            assertTrue(positions[i].x >= -0.01f && positions[i].y >= -0.01f)
            for (j in i + 1 until boxes.size) {
                val a = positions[i]
                val b = positions[j]
                val overlapX = a.x < b.x + boxes[j].w + gap - 0.02f && b.x < a.x + boxes[i].w + gap - 0.02f
                val overlapY = a.y < b.y + boxes[j].h + gap - 0.02f && b.y < a.y + boxes[i].h + gap - 0.02f
                assertFalse("boxes $i and $j overlap", overlapX && overlapY)
            }
        }
    }

    @Test
    fun optimalPackingNeverOverlaps() {
        for (seed in 0 until 20) {
            val boxes = randomBoxes(seed, 1 + seed * 5)
            assertNoOverlap(boxes, Arranger.optimal(boxes, 10f, 0.45f), 10f)
        }
    }

    @Test
    fun rowsNeverOverlap() {
        for (seed in 0 until 20) {
            val boxes = randomBoxes(seed, 1 + seed * 5)
            assertNoOverlap(boxes, Arranger.rows(boxes, 10f, 1.6f), 10f)
        }
    }

    @Test
    fun optimalPackingFitsTheScreenShape() {
        // 36 photos of 400x300 fit a square at best as 6x6 or 5x8: 2400 on a side.
        val boxes = List(36) { Box(400f, 300f) }
        val extent = Arranger.extent(boxes, Arranger.optimal(boxes, 0f, 1f))
        assertTrue(maxOf(extent.w, extent.h) <= 2400.5f)
    }

    @Test
    fun optimalPackingFillsGapsWithSmallImages() {
        // A big image plus small ones: best square fit is 1250x1250, with a
        // column of small images beside the big one and a row beneath.
        val boxes = listOf(Box(1000f, 1000f)) + List(8) { Box(250f, 250f) }
        val extent = Arranger.extent(boxes, Arranger.optimal(boxes, 0f, 1f))
        assertTrue(maxOf(extent.w, extent.h) <= 1250.5f)
    }

    @Test
    fun rowsKeepOrder() {
        val boxes = List(10) { Box(100f, 100f) }
        val positions = Arranger.rowsWithWidth(boxes, 0f, 500f)
        for (i in 1 until boxes.size) {
            val prev = positions[i - 1]
            val cur = positions[i]
            assertTrue(cur.y > prev.y || (cur.y == prev.y && cur.x > prev.x))
        }
    }
}
