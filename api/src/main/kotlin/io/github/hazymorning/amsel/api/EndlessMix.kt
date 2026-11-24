package io.github.hazymorning.amsel.api

import kotlin.time.Duration.Companion.seconds

/** A mix is offered only where this plugin is loaded ([providers]). See docs/API-NOTES.md, "Extras". */
public const val MIX_PLUGIN: String = "radio_playlist"
private const val MIX_PREFIX = "$MIX_PLUGIN://playlist/"

/** Appended by the server to the seed's name, in English whatever the language. */
private const val MIX_NAME_END = " Endless Mix"

public fun endlessMixUri(seedUri: String): String = MIX_PREFIX + seedUri

/** No mix is offered of a mix: the server would accept one and append " Endless Mix" twice. */
public fun isEndlessMix(uri: String): Boolean = uri.startsWith("$MIX_PLUGIN://")

public fun endlessMixSeed(uri: String): String? =
    uri.takeIf { it.startsWith(MIX_PREFIX) }?.removePrefix(MIX_PREFIX)?.takeIf { it.isNotEmpty() }

/**
 * The seed's name while the queue plays an endless mix, else null. `is_dynamic` alone is also true
 * for the "Infinite Mix" playlists.
 */
public val PlayerQueue.endlessMixOf: String?
    get() = sources.firstOrNull { isDynamic && isEndlessMix(it.uri) }?.name?.removeSuffix(MIX_NAME_END)

/** One song with nothing like it would play over and over. */
private const val MIX_MIN_SONGS = 2

/**
 * A seed without songs answers at once; a slower answer means the server is looking for similar
 * songs, so the mix is started.
 */
private val MIX_SAMPLE_TIMEOUT = 5.seconds

public class NothingToMixException : Exception("The server has nothing to mix")

/**
 * The mix is sampled first: for a seed without songs `play_media` empties the queue before it fails.
 * Repeat goes off before the play, as a mix locks the mode it starts with. [beforeStart] runs once
 * the mix is known to have songs, before the queue is touched. See docs/API-NOTES.md, "Extras".
 */
public suspend fun MaApi.playEndlessMix(queueId: String, seedUri: String, beforeStart: suspend () -> Unit = {}) {
    val sample = try {
        list("music/playlists/playlist_tracks", MediaItem.serializer(), MIX_SAMPLE_TIMEOUT) { ids(seedUri, MIX_PLUGIN) }
    } catch (e: MaConnectionException) {
        null
    }
    if (sample != null && sample.distinctBy { it.uri }.size < MIX_MIN_SONGS) throw NothingToMixException()
    beforeStart()
    try {
        setRepeat(queueId, RepeatMode.Off)
    } catch (e: MaCommandException) {
        if (e.code != MaCommandException.ERROR_INVALID_COMMAND) throw e
        // A mix already playing there locks its repeat mode until the queue is cleared.
        clearQueue(queueId)
        setRepeat(queueId, RepeatMode.Off)
    }
    try {
        playMedia(queueId, listOf(endlessMixUri(seedUri)))
    } catch (e: MaCommandException) {
        if (!e.isNotFound) throw e
        // The server emptied the queue before it failed; left so, it stays a dynamic queue of no items.
        if (queues().any { it.queueId == queueId && it.items == 0 && it.isDynamic }) clearQueue(queueId)
        throw NothingToMixException()
    }
}
