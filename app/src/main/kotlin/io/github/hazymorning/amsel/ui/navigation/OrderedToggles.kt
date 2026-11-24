package io.github.hazymorning.amsel.ui.navigation

import androidx.compose.runtime.Immutable

/**
 * [order] keeps hidden items too, so one hidden and shown again returns to its place. Stored as ids in
 * order, a hidden one prefixed with [HIDDEN]: "home,speaker,-search,library".
 */
@Immutable
data class OrderedToggles<T>(val order: List<T>, val shown: Set<T>) {
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
         * Null [tokens] gives [defaults]; unknown and repeated ids are dropped. Items missing from [tokens]
         * (older versions stored only the shown ones; newer versions add items) join at the end, hidden.
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
