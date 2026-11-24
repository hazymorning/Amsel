package io.github.hazymorning.amsel.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReorderRuleTest {
    private val heights = mapOf("small" to 100, "tall" to 400, "mid" to 150)

    /**
     * Simulates a drag, one event per step in px, with the order laid out top to bottom and the dragged
     * item under the finger. Returns the order after each swap.
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
        // The finger holds still just past the tall section's middle. The tall one, now in the dragged
        // item's old slot, spans the dragged item's middle, which must not swap them back.
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
