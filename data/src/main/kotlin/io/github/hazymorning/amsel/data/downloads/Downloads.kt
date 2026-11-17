package io.github.hazymorning.amsel.data.downloads

import io.github.hazymorning.amsel.api.MaException
import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.MediaType
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a download is doing. */
sealed interface DownloadState {
    /** In line, or waiting for a network it may use: Wi-Fi only ([forWifi]), or any ([offline]). */
    data class Waiting(val forWifi: Boolean = false, val offline: Boolean = false) : DownloadState

    data class Running(val done: Long, val total: Long) : DownloadState {
        val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    /** It stopped for [reason]; null when the phone could not store it (full). */
    data class Failed(val reason: AbsException.Reason?) : DownloadState

    data object Done : DownloadState
}

/** Whether the phone has a network, and one that costs nothing per byte (Wi-Fi, Ethernet). */
interface NetworkState {
    val online: StateFlow<Boolean>
    val unmetered: StateFlow<Boolean>
}

/**
 * Books and episodes downloaded from Audiobookshelf for listening without a connection, and those
 * on their way, by Music Assistant uri. One downloads at a time, in the order asked; [work] runs
 * them, in the download service, and retries a lost connection with growing pauses. What was on its
 * way when the app stopped goes on at its next start.
 */
class Downloads(
    private val abs: AbsClient,
    private val store: DownloadStore,
    private val network: NetworkState,
    private val wifiOnly: StateFlow<Boolean>,
    /** The item Music Assistant has at a uri, with where it comes from. */
    private val itemAt: suspend (String) -> MediaItem?,
    /** Starts the service that runs [work]. */
    private val onWork: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val job = DownloadJob(abs, store)
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    private val _entries = MutableStateFlow<Map<String, DownloadManifest>>(emptyMap())
    private val _done = MutableStateFlow<Map<String, DownloadManifest>>(emptyMap())

    @Volatile private var running: Pair<String, Job>? = null

    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    /** Every download, complete or on its way: what it is, for a list of them. */
    val entries: StateFlow<Map<String, DownloadManifest>> = _entries.asStateFlow()

    /** Every download that is complete. */
    val done: StateFlow<Map<String, DownloadManifest>> = _done.asStateFlow()

    /** Something waits or downloads. */
    val pending: Boolean get() = _states.value.values.any { it is DownloadState.Waiting || it is DownloadState.Running }

    /** Reads what is on the phone, once, at start and off the main thread; what changed meanwhile stays. */
    fun load() {
        val manifests = store.all()
        _entries.update { manifests.associateBy { m -> m.uri } + it }
        _done.update { manifests.filter { m -> m.complete }.associateBy { m -> m.uri } + it }
        _states.update {
            manifests.associate { m -> m.uri to if (m.complete) DownloadState.Done else DownloadState.Waiting() } + it
        }
        resume()
    }

    /** Starts the download service while something waits; Android lets it start only with the app in front. */
    fun resume() {
        if (pending) onWork()
    }

    enum class Added { Yes, SignInFirst, NotFromAudiobookshelf, NotFound, NotSaved }

    /**
     * Downloads the book or episode at [uri]. One that stopped goes on where it was, without asking
     * Music Assistant again, which is only asked where Audiobookshelf has a new one.
     */
    suspend fun add(uri: String): Added {
        if (abs.account.value == null) return Added.SignInFirst
        val state = _states.value[uri]
        if (state != null && state !is DownloadState.Failed) return Added.Yes
        val manifest = _entries.value[uri] ?: run {
            val item = try {
                itemAt(uri)
            } catch (e: MaException) {
                null
            } ?: return Added.NotFound
            val stub = stubFor(uri, item) ?: return Added.NotFromAudiobookshelf
            try {
                store.save(stub)
            } catch (e: IOException) {
                return Added.NotSaved
            }
            stub
        }
        _entries.update { it + (uri to manifest) }
        _states.update { it + (uri to DownloadState.Waiting()) }
        onWork()
        return Added.Yes
    }

    /** Stops [uri]'s download, if it runs, and deletes what is on the phone of it. */
    fun remove(uri: String) {
        running?.takeIf { it.first == uri }?.second?.cancel()
        store.remove(uri)
        _states.update { it - uri }
        _entries.update { it - uri }
        _done.update { it - uri }
    }

    /** Removes every download: the person signed out of Music Assistant, whose items they were. */
    fun clear() = _states.value.keys.forEach(::remove)

    /** What the downloads take up on the phone, in bytes. */
    suspend fun used(): Long = withContext(Dispatchers.IO) { store.used() }

    fun manifest(uri: String): DownloadManifest? = _done.value[uri]

    fun progress(uri: String): LocalProgress? = store.progress(uri)

    fun saveProgress(uri: String, progress: LocalProgress) = store.saveProgress(uri, progress)

    fun file(uri: String, file: DownloadFile) = store.file(uri, file)

    fun cover(uri: String) = store.cover(uri).takeIf { it.isFile }

    /** Runs every download in line until none is left. */
    suspend fun work() = coroutineScope {
        try {
            while (true) {
                val uri = _states.value.entries.firstOrNull { it.value is DownloadState.Waiting }?.key ?: break
                val one = launch { run(uri) }
                running = uri to one
                one.join()
                running = null
                // Removed while it ran: nothing of it may stay.
                if (uri !in _states.value) store.remove(uri)
            }
        } finally {
            // Stopped with the service (Android 15 gives it six hours a day): it waits for the next start.
            running = null
            _states.update { states ->
                states.mapValues { (_, s) -> if (s is DownloadState.Running) DownloadState.Waiting() else s }
            }
        }
    }

    private suspend fun run(uri: String) {
        var attempt = 0
        while (true) {
            awaitNetwork(uri)
            try {
                download(uri)
                return
            } catch (e: AbsException) {
                val transient = e.reason == AbsException.Reason.Unreachable || e.reason == AbsException.Reason.Server
                if (!transient || ++attempt > RETRIES) return fail(uri, e.reason)
            } catch (e: IOException) {
                // A connection cut mid-file, or a full phone: the first goes on where it stopped.
                if (++attempt >
                    RETRIES
                ) {
                    return fail(uri, if (network.online.value) null else AbsException.Reason.Unreachable)
                }
            }
            delay(RETRY_MS shl (attempt - 1))
        }
    }

    private suspend fun download(uri: String) {
        val stub = store.manifest(uri) ?: return
        val manifest = if (stub.files.isEmpty()) job.plan(stub).also(store::save) else stub
        _entries.update { if (uri in it) it + (uri to manifest) else it }
        job.cover(manifest)
        val total = manifest.bytes
        var before = 0L
        var shown = -1L
        for (file in manifest.files) {
            job.fetch(manifest, file) { bytes ->
                val done = before + bytes
                if (done - shown >= SHOW_EVERY || done == total) {
                    shown = done
                    _states.update { if (uri in it) it + (uri to DownloadState.Running(done, total)) else it }
                }
            }
            before += file.size
        }
        val complete = manifest.copy(complete = true)
        store.save(complete)
        _entries.update { if (uri in it) it + (uri to complete) else it }
        _done.update { it + (uri to complete) }
        _states.update { if (uri in it) it + (uri to DownloadState.Done) else it }
    }

    /** Waits, saying so, until a network that [wifiOnly] allows is there. */
    private suspend fun awaitNetwork(uri: String) {
        combine(network.online, network.unmetered, wifiOnly) { online, unmetered, onlyWifi ->
            when {
                !online -> DownloadState.Waiting(offline = true)
                onlyWifi && !unmetered -> DownloadState.Waiting(forWifi = true)
                else -> null
            }
        }.first { waiting ->
            if (waiting != null) _states.update { if (it[uri] is DownloadState.Running) it else it + (uri to waiting) }
            waiting == null
        }
    }

    private fun fail(uri: String, reason: AbsException.Reason?) {
        _states.update { if (uri in it) it + (uri to DownloadState.Failed(reason)) else it }
    }

    /** The first download state of [item], from where Audiobookshelf has it; null when it does not. */
    private fun stubFor(uri: String, item: MediaItem): DownloadManifest? {
        val mapping = item.audiobookshelf ?: return null
        val episode = item.mediaType == MediaType.PodcastEpisode.api
        // An episode's id there is "<podcast id> <episode id>".
        val ids = mapping.itemId.split(' ')
        if (episode && ids.size != 2) return null
        return DownloadManifest(
            uri = uri,
            absItemId = ids.first(),
            absEpisodeId = ids.getOrNull(1)?.takeIf { episode },
            title = item.name,
            author = item.subtitle,
            mediaType = item.mediaType,
            durationSec = item.duration?.toDouble() ?: 0.0,
            addedAt = now(),
        )
    }

    companion object {
        const val AUDIOBOOKSHELF = "audiobookshelf"
        private const val RETRIES = 6
        private const val RETRY_MS = 2_000L
        private const val SHOW_EVERY = 512L * 1024
    }
}

private val MediaItem.audiobookshelf get() = providerMappings.firstOrNull {
    it.providerDomain ==
        Downloads.AUDIOBOOKSHELF
}

/** A book or an episode Music Assistant has from Audiobookshelf, which can be downloaded. */
val MediaItem.downloadable: Boolean
    get() = audiobookshelf != null &&
        (mediaType == MediaType.Audiobook.api || mediaType == MediaType.PodcastEpisode.api)
