package io.github.hazymorning.amsel.ui.screens.library

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.Saver

/**
 * Each kind's own place in its list, kept while another kind shows, as the pages of a pager keep
 * theirs: a swipe to the next kind and back returns to where the person was.
 */
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
     * Before [to] takes [from]'s place: the header stays where it stood, so a swipe or a chip never
     * brings it back or takes it away. From where the header shows, [to] opens at the same height;
     * from further down, [to] keeps its own place where that is past its header too, else it opens
     * just past it.
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
