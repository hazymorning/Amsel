package io.github.hazymorning.amsel.api

import kotlin.time.Duration.Companion.seconds

/** The plugin that makes a mix; built in from 2.10.9 on, and only there is a mix offered. */
public const val MIX_PLUGIN: String = "radio_playlist"
private const val MIX_PREFIX = "$MIX_PLUGIN://playlist/"

/** What the server appends to the seed's name, in English whatever the language. */
private const val MIX_NAME_END = " Endless Mix"

/**
 * The server's endless mix of [seedUri], a song, an album, an artist or a playlist: a playlist it
 * keeps filling with the seed's songs and ones like them. Played with `play_media` like any other,
 * on 2.10 and 2.11 alike.
 */
public fun endlessMixUri(seedUri: String): String = MIX_PREFIX + seedUri

/** A mix has no mix of its own: the server would take one and name it twice. */
public fun isEndlessMix(uri: String): Boolean = uri.startsWith("$MIX_PLUGIN://")

/** What the mix [uri] was made from; null when [uri] is no mix. */
public fun endlessMixSeed(uri: String): String? =
    uri.takeIf { it.startsWith(MIX_PREFIX) }?.removePrefix(MIX_PREFIX)?.takeIf { it.isNotEmpty() }

/** The mix the queue plays, named for what it was made from; null while it plays no mix. */
public val PlayerQueue.endlessMixOf: String?
    get() = sources.firstOrNull { isDynamic && isEndlessMix(it.uri) }?.name?.removeSuffix(MIX_NAME_END)

/** Fewer songs than this make no mix: none, or one song that plays again and again. */
private const val MIX_MIN_SONGS = 2

/**
 * How long the server is asked what a mix would hold. A seed without songs answers at once; one
 * that takes longer is looking for similar songs, so it has some, and the mix is started.
 */
private val MIX_SAMPLE_TIMEOUT = 5.seconds

/** The server has nothing to make a mix from: the seed has no songs, or one with nothing like it. */
public class NothingToMixException : Exception("The server has nothing to mix")

/**
 * Plays the endless mix of [seedUri] on [queueId], in place of what is there. The server is asked
 * first what the mix would hold: started for a seed without songs it empties the queue before it
 * finds that out, and a lone song with nothing like it would play over and over. Repeat goes off
 * as well, since a mix keeps the mode it starts with and refuses to change it. [beforeStart] runs
 * once the mix is known to have songs, before anything that plays is touched.
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
        // A mix that plays there holds another mode locked; only an empty queue lets go of it.
        clearQueue(queueId)
        setRepeat(queueId, RepeatMode.Off)
    }
    try {
        playMedia(queueId, listOf(endlessMixUri(seedUri)))
    } catch (e: MaCommandException) {
        if (!e.isNotFound) throw e
        // The songs the sample promised could not be played after all, and the server has already
        // emptied the queue for them: left as it is, it would stay a mix of nothing.
        if (queues().any { it.queueId == queueId && it.items == 0 && it.isDynamic }) clearQueue(queueId)
        throw NothingToMixException()
    }
}
