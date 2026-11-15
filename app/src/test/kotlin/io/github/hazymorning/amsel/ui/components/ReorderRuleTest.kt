package io.github.hazymorning.amsel.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's sections differ a lot in height; a drag past one must swap with it once, not flicker. */
class ReorderRuleTest {
    private val heights = mapOf("small" to 100, "tall" to 400, "mid" to 150)

    /**
     * Drags [dragged] from [start] by [steps] px, one drag event each, as the list would: the list
     * lays the order out top to bottom, the dragged item stays under the finger. Returns every order
     * a swap made.
     */
    private fun drag(start: List<String>, steps: List<Int>, dragged: String = "small"): List<List<String>> {
        val order = start.toMutableList()
        val startTop = topOf(order, dragged)
        var delta = 0
        val swaps = mutableListOf<List<String>>()
        steps.forEach { step ->
            delta += step
            val index = order.indexOf(dragged)
            val top = startTop + delta.toFloat()
            val bottom = top + heights.getValue(dragged)
            val target = order.indices.firstOrNull { i ->
                i != index && swapsWith(top, bottom, i - index, topOf(order, order[i]), heights.getValue(order[i]))
            }
            if (target != null) {
                order.add(target, order.removeAt(index))
                swaps += order.toList()
            }
        }
        return swaps
    }

    private fun topOf(order: List<String>, key: String) = order.takeWhile { it != key }.sumOf { heights.getValue(it) }

    @Test fun aShortItemDraggedDownPastATallOneSwapsOncePerCrossing() {
        val swaps = drag(listOf("small", "tall", "mid"), List(140) { 5 })
        assertEquals(listOf(listOf("tall", "small", "mid"), listOf("tall", "mid", "small")), swaps)
    }

    @Test fun aShortItemDraggedUpPastATallOneSwapsOncePerCrossing() {
        val swaps = drag(listOf("tall", "mid", "small"), List(140) { -5 })
        assertEquals(listOf(listOf("tall", "small", "mid"), listOf("small", "tall", "mid")), swaps)
    }

    @Test fun aTallItemDraggedDownPastAShortOneSwapsOnce() {
        val swaps = drag(listOf("tall", "small", "mid"), List(30) { 5 }, dragged = "tall")
        assertEquals(listOf(listOf("small", "tall", "mid")), swaps)
    }

    @Test fun holdingStillAfterASwapDoesNotSwapBack() {
        // Just past the tall section's middle, the finger rests: the tall one, moved up into the
        // dragged item's old slot, now holds its middle in its span, which must not swap them back.
        val swaps = drag(listOf("small", "tall", "mid"), listOf(201) + List(20) { 0 })
        assertEquals(listOf(listOf("tall", "small", "mid")), swaps)
    }

    @Test fun movingBackUndoesASwapWhereItHappened() {
        val swaps = drag(listOf("small", "tall", "mid"), listOf(201, -2))
        assertEquals(listOf(listOf("tall", "small", "mid"), listOf("small", "tall", "mid")), swaps)
    }

    @Test fun onlyTheNextOrThePreviousItemCounts() {
        assertFalse(swapsWith(top = 900f, bottom = 1000f, step = 2, offset = 0, size = 10))
        assertTrue(swapsWith(top = 151f, bottom = 251f, step = 1, offset = 100, size = 300))
        assertFalse(swapsWith(top = 149f, bottom = 249f, step = 1, offset = 100, size = 300))
        assertTrue(swapsWith(top = 249f, bottom = 349f, step = -1, offset = 100, size = 300))
    }
}
