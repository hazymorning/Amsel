package io.github.hazymorning.amsel.ui.screens.library

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.Saver

@Stable
internal class LibraryScrollPositions(private val saved: Map<String, List<Int>> = emptyMap()) {
    private val lists = mutableMapOf<LibraryKind, LazyListState>()
    private val grids = mutableMapOf<LibraryKind, LazyGridState>()

    fun list(kind: LibraryKind): LazyListState = lists.getOrPut(kind) {
        val (index, offset) = saved[kind.name].takeUnless { kind.covers } ?: TOP
        LazyListState(index, offset)
    }

    fun grid(kind: LibraryKind): LazyGridState = grids.getOrPut(kind) {
        val (index, offset) = saved[kind.name].takeIf { kind.covers } ?: TOP
        LazyGridState(index, offset)
    }

    /**
     * Call before [to] replaces [from], so a switch never shows or hides the header: with the header in
     * view, [to] opens at the same offset; scrolled past it, [to] keeps its own place if that is past
     * its header too, else opens just below it.
     */
    fun handOver(from: LibraryKind, to: LibraryKind) {
        val (index, offset) = place(from)
        when {
            index == 0 -> request(to, 0, offset)
            place(to).first == 0 -> request(to, 1, 0)
        }
    }

    private fun place(kind: LibraryKind): Pair<Int, Int> = if (kind.covers) {
        grid(kind).let { it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset }
    } else {
        list(kind).let { it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset }
    }

    private fun request(kind: LibraryKind, index: Int, offset: Int) = if (kind.covers) {
        grid(kind).requestScrollToItem(index, offset)
    } else {
        list(kind).requestScrollToItem(index, offset)
    }

    private fun places(): HashMap<String, List<Int>> = HashMap<String, List<Int>>().apply {
        putAll(saved)
        lists.forEach { (kind, state) ->
            if (!kind.covers) {
                put(
                    kind.name,
                    arrayListOf(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset),
                )
            }
        }
        grids.forEach { (kind, state) ->
            if (kind.covers) {
                put(
                    kind.name,
                    arrayListOf(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset),
                )
            }
        }
    }

    companion object {
        private val TOP = listOf(0, 0)

        val Saver: Saver<LibraryScrollPositions, HashMap<String, List<Int>>> =
            Saver(save = { it.places() }, restore = { LibraryScrollPositions(it) })
    }
}
