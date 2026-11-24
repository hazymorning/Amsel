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

sealed interface DownloadState {
    /** Queued; or waiting for Wi-Fi ([forWifi]) or for any network ([offline]). */
    data class Waiting(val forWifi: Boolean = false, val offline: Boolean = false) : DownloadState

    data class Running(val done: Long, val total: Long) : DownloadState {
        val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    /** A null [reason]: reading or writing failed while online, most likely a full phone. */
    data class Failed(val reason: AbsException.Reason?) : DownloadState

    data object Done : DownloadState
}

interface NetworkState {
    val online: StateFlow<Boolean>
    val unmetered: StateFlow<Boolean>
}

/**
 * Keyed by Music Assistant uri. One download runs at a time, in the order asked, inside [work], which the
 * download service runs.
 */
class DownloadRepository(
    private val abs: AbsClient,
    private val store: DownloadStore,
    private val network: NetworkState,
    private val wifiOnly: StateFlow<Boolean>,
    /** With its provider mappings, which hold the Audiobookshelf id. */
    private val itemAt: suspend (String) -> MediaItem?,
    /** Starts the service that runs [work]. */
    private val onWork: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val job = DownloadTask(abs, store)
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    private val _entries = MutableStateFlow<Map<String, DownloadManifest>>(emptyMap())
    private val _done = MutableStateFlow<Map<String, DownloadManifest>>(emptyMap())

    @Volatile private var running: Pair<String, Job>? = null

    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    val entries: StateFlow<Map<String, DownloadManifest>> = _entries.asStateFlow()

    val done: StateFlow<Map<String, DownloadManifest>> = _done.asStateFlow()

    val pending: Boolean get() = _states.value.values.any { it is DownloadState.Waiting || it is DownloadState.Running }

    /** Call once, at start, off the main thread; what changed meanwhile takes precedence. */
    fun load() {
        val manifests = store.all()
        _entries.update { manifests.associateBy { m -> m.uri } + it }
        _done.update { manifests.filter { m -> m.complete }.associateBy { m -> m.uri } + it }
        _states.update {
            manifests.associate { m -> m.uri to if (m.complete) DownloadState.Done else DownloadState.Waiting() } + it
        }
        resume()
    }

    /** Call with the app in the foreground: Android starts the download service only then. */
    fun resume() {
        if (pending) onWork()
    }

    enum class Added { Yes, SignInFirst, NotFromAudiobookshelf, NotFound, NotSaved }

    /** A failed download resumes from its manifest; Music Assistant is asked only for a new one. */
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

    fun remove(uri: String) {
        running?.takeIf { it.first == uri }?.second?.cancel()
        store.remove(uri)
        _states.update { it - uri }
        _entries.update { it - uri }
        _done.update { it - uri }
    }

    fun clear() = _states.value.keys.forEach(::remove)

    suspend fun used(): Long = withContext(Dispatchers.IO) { store.used() }

    fun manifest(uri: String): DownloadManifest? = _done.value[uri]

    fun progress(uri: String): LocalProgress? = store.progress(uri)

    fun saveProgress(uri: String, progress: LocalProgress) = store.saveProgress(uri, progress)

    fun file(uri: String, file: DownloadFile) = store.file(uri, file)

    fun cover(uri: String) = store.cover(uri).takeIf { it.isFile }

    suspend fun work() = coroutineScope {
        try {
            while (true) {
                val uri = _states.value.entries.firstOrNull { it.value is DownloadState.Waiting }?.key ?: break
                val one = launch { run(uri) }
                running = uri to one
                one.join()
                running = null
                // Removed while it ran: delete what it wrote after the removal.
                if (uri !in _states.value) store.remove(uri)
            }
        } finally {
            // Stopped with the service (Android 15 allows it six hours a day): what ran waits for the next start.
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
                // A connection cut mid-file, which the retry resumes, or a full phone.
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
        DownloadRepository.AUDIOBOOKSHELF
}

val MediaItem.downloadable: Boolean
    get() = audiobookshelf != null &&
        (mediaType == MediaType.Audiobook.api || mediaType == MediaType.PodcastEpisode.api)
