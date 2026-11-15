package io.github.hazymorning.amsel.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.navigation.Route
import io.github.hazymorning.amsel.ui.navigation.Tab

/**
 * The three tabs' back stacks. Tapping a tab drops its pages back to the top, but each tab decorates
 * its own entries, so a top page keeps its scroll position while another tab is shown. Entry keys
 * carry the tab, so the same album open in two tabs is two pages, as is one opened again above
 * itself ([Route.Again]).
 */
@Stable
internal class TabStacks(
    private val stacks: Map<Tab, NavBackStack<NavKey>>,
    private val navigators: Map<Tab, Navigator>,
    private val decorated: Map<Tab, List<NavEntry<NavKey>>>,
) {
    fun stack(tab: Tab): NavBackStack<NavKey> = stacks.getValue(tab)

    fun entries(tab: Tab): List<NavEntry<NavKey>> = decorated.getValue(tab)

    fun navigator(tab: Tab): Navigator = navigators.getValue(tab)

    fun popToRoot(tab: Tab) {
        val stack = stack(tab)
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

@Composable
internal fun rememberTabStacks(closePlayer: () -> Unit, slots: ShellSlots): TabStacks {
    val stacks = Tab.entries.associateWith { rememberNavBackStack(it.root) }
    val navigators = Tab.entries.associateWith { t -> remember(t) { Navigator(stacks.getValue(t), closePlayer) } }
    val decorated = Tab.entries.associateWith { t ->
        val navigator = navigators.getValue(t)
        rememberDecoratedNavEntries(
            backStack = stacks.getValue(t),
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = { key ->
                NavEntry(key, contentKey = "${t.name}:$key") { slots.screen((key as Route).page, navigator) }
            },
        )
    }
    return TabStacks(stacks, navigators, decorated)
}

/** The tab a scene belongs to, by the first entry of its back stack. */
internal fun Scene<*>.root(): Any = (previousEntries.firstOrNull() ?: entries.first()).contentKey
