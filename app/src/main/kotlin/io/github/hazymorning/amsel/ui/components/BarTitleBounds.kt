package io.github.hazymorning.amsel.ui.components

internal data class BarTitleBounds(val start: Int, val width: Int)

/**
 * Centres the title, keeping [inset] or the end actions' share ([taken]), whichever is larger, from
 * both sides. Where actions wider than [inset] leave too little for the shortest title ([needed]),
 * it gets all the room from [from] to the actions instead, off centre, so it is cut last.
 */
internal fun barTitleBounds(width: Int, inset: Int, taken: Int, from: Int, needed: Int): BarTitleBounds {
    val side = maxOf(inset, taken)
    val middle = (width - side * 2).coerceAtLeast(0)
    if (taken <= inset || needed <= middle) return BarTitleBounds(side, middle)
    return BarTitleBounds(from, (width - taken - from).coerceAtLeast(0))
}
