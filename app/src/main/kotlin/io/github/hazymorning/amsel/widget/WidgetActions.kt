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

    /** The step the server's volume up and down take from [level], as its settings last said; never waits for it. */
    fun volumeStep(level: Int): Int

    /** The server's own step up or down, shown at once as [shown]. */
    suspend fun nudge(speakerId: String, up: Boolean, shown: Int)

    suspend fun volume(speakerId: String, level: Int)

    /** This phone's own media volume, one of Android's steps. */
    fun phoneVolume(up: Boolean)

    suspend fun shuffle(speakerId: String, on: Boolean)

    suspend fun repeat(speakerId: String, mode: RepeatMode)

    fun favourite(uri: String, on: Boolean)

    companion object {
        val None = object : WidgetActions {
            override fun volumeLimit() = 0

            override fun volumeStep(level: Int) = serverStep(level)

            override suspend fun nudge(speakerId: String, up: Boolean, shown: Int) = Unit

            override suspend fun volume(speakerId: String, level: Int) = Unit

            override fun phoneVolume(up: Boolean) = Unit

            override suspend fun shuffle(speakerId: String, on: Boolean) = Unit

            override suspend fun repeat(speakerId: String, mode: RepeatMode) = Unit

            override fun favourite(uri: String, on: Boolean) = Unit
        }
    }
}

/** The server's own step at [level] where its settings name none: finer near silence and near full. */
internal fun serverStep(level: Int): Int = when {
    level < LADDER_FINE || level > MAX_LEVEL - LADDER_FINE -> 1
    level < LADDER_MID || level > MAX_LEVEL - LADDER_MID -> 2
    else -> 3
}

private const val LADDER_FINE = 10
private const val LADDER_MID = 30
private const val MAX_LEVEL = 100

/**
 * Taps on the buttons that change the speaker a widget shows ([shown]): what they ask follows from
 * what the widget shows, not from the speaker Amsel had picked when the tap started it, which on a
 * cold start it does not know yet. Two taps in a row step on from the first, before the speaker
 * reports it.
 */
internal class StateButtonHandler(private val actions: WidgetActions, private val elapsedRealtime: () -> Long) {
    private val asked = mutableMapOf<String, Pair<Int, Long>>()

    /** Says why nothing changed, or null. */
    suspend fun tap(button: WidgetButton, shown: WidgetContent): Refusal? {
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

    /**
     * The server takes the step, by its own setting and from the level it last set, so taps in a row
     * add up; the widget shows at once where it lands by the setting last read. Louder stops at the
     * person's limit: a step that would pass it sets the limit, one from the limit or above it does
     * nothing and says so.
     */
    private suspend fun volume(shown: WidgetContent, up: Boolean): Refusal? {
        if (shown.phone) {
            actions.phoneVolume(up)
            return null
        }
        val id = shown.speakerId ?: return null
        val now = elapsedRealtime()
        val level = asked[id]?.takeIf { (_, at) -> now - at < STEP_ON_MS }?.first ?: shown.volume ?: return null
        val limit = actions.volumeLimit().takeIf { it > 0 }
        if (up && limit != null && level >= limit) return Refusal.AtLimit(limit)
        val step = actions.volumeStep(level)
        val next = (if (up) level + step else level - step).coerceIn(0, MAX_LEVEL)
        if (next == level) return null
        if (up && limit != null && next > limit) {
            asked[id] = limit to now
            actions.volume(id, limit)
        } else {
            asked[id] = next to now
            actions.nudge(id, up, next)
        }
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
