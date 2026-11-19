package io.github.hazymorning.amsel.widget

import io.github.hazymorning.amsel.api.RepeatMode
import io.github.hazymorning.amsel.ui.model.next

/**
 * What a widget's volume, shuffle, repeat and heart ask of the speaker it shows, through Amsel:
 * each shows at once, on the widgets and in Amsel, and the speaker confirms it. A failure throws
 * [io.github.hazymorning.amsel.api.MaException] and the speaker's own state shows again.
 */
interface WidgetActions {
    /** The level Louder stops at; 0 for none. */
    fun volumeLimit(): Int

    suspend fun volume(speakerId: String, level: Int)

    /** This phone's own media volume, one of Android's steps. */
    fun phoneVolume(up: Boolean)

    suspend fun shuffle(speakerId: String, on: Boolean)

    suspend fun repeat(speakerId: String, mode: RepeatMode)

    fun favourite(uri: String, on: Boolean)

    companion object {
        val None = object : WidgetActions {
            override fun volumeLimit() = 0

            override suspend fun volume(speakerId: String, level: Int) = Unit

            override fun phoneVolume(up: Boolean) = Unit

            override suspend fun shuffle(speakerId: String, on: Boolean) = Unit

            override suspend fun repeat(speakerId: String, mode: RepeatMode) = Unit

            override fun favourite(uri: String, on: Boolean) = Unit
        }
    }
}

/**
 * The level a tap on Quieter or Louder sets, from the level the widget shows: 5 at a time, 2 below
 * 10, so a tap is heard; Louder stops at [limit] (0 for none) and does not lower a level above it.
 * Set as a level, not as a step on the server, so the speaker ends where the widget says.
 */
internal fun volumeAfter(level: Int, up: Boolean, limit: Int): Int {
    val step = if (level < FINE_BELOW || (!up && level <= FINE_BELOW)) FINE_STEP else STEP
    if (!up) return (level - step).coerceAtLeast(0)
    val top = if (limit in 1..MAX_LEVEL) maxOf(limit, level) else MAX_LEVEL
    return (level + step).coerceAtMost(top)
}

private const val STEP = 5
private const val FINE_STEP = 2
private const val FINE_BELOW = 10
private const val MAX_LEVEL = 100

/**
 * Taps on the buttons that change the speaker a widget shows ([shown]): what they ask follows from
 * what the widget shows, not from the speaker Amsel had picked when the tap started it, which on a
 * cold start it does not know yet. Two taps in a row step on from the first, before the speaker
 * reports it.
 */
internal class StateTaps(private val actions: WidgetActions, private val elapsedRealtime: () -> Long) {
    private val asked = mutableMapOf<String, Pair<Int, Long>>()

    /** Says why nothing changed, or null. */
    suspend fun tap(button: WidgetButton, shown: WidgetLook): Refusal? {
        when (button) {
            WidgetButton.Quieter -> return volume(shown, up = false)
            WidgetButton.Louder -> return volume(shown, up = true)
            WidgetButton.Shuffle -> shown.speakerId?.let { id -> shown.shuffle?.let { actions.shuffle(id, !it) } }
            WidgetButton.Repeat -> shown.speakerId?.let { id -> shown.repeat?.let { actions.repeat(id, it.next()) } }
            WidgetButton.Favourite -> shown.key?.let { uri -> shown.favourite?.let { actions.favourite(uri, !it) } }
            else -> Unit
        }
        return null
    }

    private suspend fun volume(shown: WidgetLook, up: Boolean): Refusal? {
        if (shown.phone) {
            actions.phoneVolume(up)
            return null
        }
        val id = shown.speakerId ?: return null
        val now = elapsedRealtime()
        val level = asked[id]?.takeIf { (_, at) -> now - at < STEP_ON_MS }?.first ?: shown.volume ?: return null
        val limit = actions.volumeLimit()
        val next = volumeAfter(level, up, limit)
        if (next == level) return if (up && limit > 0 && level >= limit) Refusal.AtLimit(limit) else null
        asked[id] = next to now
        actions.volume(id, next)
        return null
    }

    sealed interface Refusal {
        data class AtLimit(val limit: Int) : Refusal
    }

    private companion object {
        /** A tap within this of the last steps on from what that one asked. */
        const val STEP_ON_MS = 2_000L
    }
}
