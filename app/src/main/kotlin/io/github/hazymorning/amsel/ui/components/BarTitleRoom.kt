package io.github.hazymorning.amsel.ui.components

/** Where a bar's title stands: from [start] on, [width] wide, its words in the middle of that. */
internal data class BarTitleRoom(val start: Int, val width: Int)

/**
 * The title's room in a bar [width] wide, in pixels. It lies in the bar's middle and keeps [inset]
 * from the bar's sides: a glyph's room. Where the actions with their distance to the title take
 * more of a side ([taken]), it keeps that from both. Where even the title's shortest form
 * ([needed] wide) does not fit between such actions, it has all they leave, [from] the bar's start
 * on, so it is cut last.
 */
internal fun barTitleRoom(width: Int, inset: Int, taken: Int, from: Int, needed: Int): BarTitleRoom {
    val side = maxOf(inset, taken)
    val middle = (width - side * 2).coerceAtLeast(0)
    if (taken <= inset || needed <= middle) return BarTitleRoom(side, middle)
    return BarTitleRoom(from, (width - taken - from).coerceAtLeast(0))
}
