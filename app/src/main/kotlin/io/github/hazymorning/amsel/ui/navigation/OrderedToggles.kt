package io.github.hazymorning.amsel.ui.navigation

import androidx.compose.runtime.Immutable

/**
 * Things in the person's order, each shown or not: the bottom bar's places, the mini player's
 * controls. The order keeps every one, so turning one off leaves it where it stands, and turning it
 * on again brings it back there. Stored as their ids in that order, one left out with [HIDDEN]
 * before it ("home,speaker,-search,library").
 */
@Immutable
data class OrderedToggles<T>(val order: List<T>, val shown: Set<T>) {
    /** Those shown, in order: what the bar or the mini player holds. */
    val visible: List<T> get() = order.filter { it in shown }

    fun with(item: T, on: Boolean): OrderedToggles<T> = copy(shown = if (on) shown + item else shown - item)

    fun moved(from: Int, to: Int): OrderedToggles<T> {
        if (from !in order.indices || to !in order.indices || from == to) return this
        return copy(order = order.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun tokens(id: (T) -> String): List<String> = order.map { if (it in shown) id(it) else HIDDEN + id(it) }

    companion object {
        const val HIDDEN = "-"

        /**
         * From stored [tokens] (null: [defaults]), each known thing once. One the stored order lacks (an
         * earlier bar stored only what it showed, or a later Amsel added it) joins at the end, left out.
         */
        fun <T> of(
            tokens: List<String>?,
            all: List<T>,
            id: (T) -> String,
            defaults: OrderedToggles<T>,
        ): OrderedToggles<T> {
            if (tokens == null) return defaults
            val byId = all.associateBy(id)
            val order = mutableListOf<T>()
            val shown = mutableSetOf<T>()
            tokens.forEach { token ->
                val item = byId[token.removePrefix(HIDDEN)] ?: return@forEach
                if (item in order) return@forEach
                order += item
                if (!token.startsWith(HIDDEN)) shown += item
            }
            all.filterNot { it in order }.forEach { order += it }
            return OrderedToggles(order, shown)
        }
    }
}
