package io.github.hazymorning.amsel.widget

import io.github.hazymorning.amsel.api.RepeatMode
import io.github.hazymorning.amsel.ui.model.next

/**
 * Each change shows at once; a refusal throws [io.github.hazymorning.amsel.api.MaException] and the
 * speaker's own state shows again.
 */
interface WidgetActions {
    /** The level Louder stops at; 0 for none. */
    fun volumeLimit(): Int

    /** The step the server's volume up and down take from [level], by the setting last read; never blocks. */
    fun volumeStep(level: Int): Int

    /** The server's own volume step up or down; [shown] is the level displayed until the speaker reports. */
    suspend fun nudge(speakerId: String, up: Boolean, shown: Int)

    suspend fun volume(speakerId: String, level: Int)

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

/** The server's ladder where its volume_step is 0. See docs/API-NOTES.md, 5.3 "Player commands". */
internal fun serverStep(level: Int): Int = when {
    level < LADDER_FINE || level > MAX_LEVEL - LADDER_FINE -> 1
    level < LADDER_MID || level > MAX_LEVEL - LADDER_MID -> 2
    else -> 3
}

private const val LADDER_FINE = 10
private const val LADDER_MID = 30
private const val MAX_LEVEL = 100

/** Acts on what the widget shows, not on Amsel's current speaker, which a cold start does not know yet. */
internal class StateButtonHandler(private val actions: WidgetActions, private val elapsedRealtime: () -> Long) {
    private val asked = mutableMapOf<String, Pair<Int, Long>>()

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
     * The server steps from the level it last set, so taps within STEP_ON_MS add up before the speaker
     * reports (docs/API-NOTES.md, 5.3). A step past the limit sets the limit; at or above it, Louder refuses.
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
        const val STEP_ON_MS = 2_000L
    }
}
