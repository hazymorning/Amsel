package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.R
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** What is known of Amsel's newest release. */
sealed interface UpdateState {
    /** Not asked yet in this run of the app. */
    data object Unknown : UpdateState

    data object Looking : UpdateState

    /** This is the newest. */
    data object Newest : UpdateState

    /** [version] is out; [apk] is its download, [size] bytes long (0 where GitHub does not say). */
    data class Available(val version: String, val apk: String, val size: Long = 0) : UpdateState

    /** [update] is downloading, [share] of it in, from 0 to 1. */
    data class Loading(val update: Available, val share: Float) : UpdateState

    /** GitHub could not be asked, or did not answer as expected. */
    data object Failed : UpdateState
}

/**
 * Asks GitHub for Amsel's newest release: when the person asks in Settings, and once when the app
 * starts, if they turned that on. Never in the background. [install] downloads it into [folder] and,
 * once the app is in front, hands it to Android's installer ([openInstaller]), which asks before it
 * replaces the app; cancelled there, all stays as it was.
 */
class Updates(
    private val http: OkHttpClient,
    private val current: String,
    private val messages: Messages,
    private val scope: CoroutineScope,
    private val inFront: StateFlow<Boolean>,
    private val folder: File,
    private val openInstaller: (File) -> Boolean,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Unknown)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val lookedAtStart = AtomicBoolean(false)

    /** The version downloaded in this run, and where: tapped again, it opens the installer at once. */
    @Volatile private var downloaded: Pair<String, File>? = null

    /**
     * Once per run of the app, when it first comes to the front, if [enabled] says so. The download
     * a last run left behind goes then: it is installed by now, or was not wanted.
     */
    fun lookAtStart(enabled: suspend () -> Boolean) {
        if (!lookedAtStart.compareAndSet(false, true)) return
        scope.launch {
            withContext(Dispatchers.IO) { folder.deleteRecursively() }
            if (!enabled()) return@launch
            val found = look()
            if (found is UpdateState.Available) {
                messages.showUpdate(R.string.update_out, found.version) {
                    messages.show(R.string.update_downloading, found.version)
                    install(found)
                }
            }
        }
    }

    fun lookNow() {
        if (_state.value == UpdateState.Looking || _state.value is UpdateState.Loading) return
        scope.launch { look() }
    }

    /** Downloads [update], then opens Android's installer on it as soon as the app is in front. */
    fun install(update: UpdateState.Available) {
        if (_state.value is UpdateState.Loading) return
        _state.value = UpdateState.Loading(update, 0f)
        scope.launch {
            val apk = downloaded?.takeIf { (version, file) -> version == update.version && file.isFile }?.second
                ?: try {
                    download(update).also { downloaded = update.version to it }
                } catch (e: IOException) {
                    null
                }
            _state.value = update
            if (apk == null) {
                messages.show(R.string.update_not_downloaded, update.version) { install(update) }
            } else {
                inFront.first { it }
                if (!openInstaller(apk)) messages.show(R.string.error_no_installer)
            }
        }
    }

    private suspend fun download(update: UpdateState.Available): File = withContext(Dispatchers.IO) {
        if (!update.apk.startsWith(RELEASES)) throw IOException("Not one of Amsel's releases: ${update.apk}")
        folder.deleteRecursively()
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("No room for the update in the cache")
        val part = File(folder, "amsel.apk.part")
        http.newCall(Request.Builder().url(update.apk).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
            val body = response.body
            val total = body.contentLength().takeIf { it > 0 } ?: update.size
            var done = 0L
            var told = 0f
            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (done > MAX_BYTES) throw IOException("The update is larger than an APK can be")
                        val share = if (total > 0) (done.toFloat() / total).coerceAtMost(1f) else 0f
                        if (share - told >= STEP) {
                            told = share
                            _state.value = UpdateState.Loading(update, share)
                        }
                    }
                }
            }
            if (update.size > 0 && done != update.size) throw IOException("Cut short at $done of ${update.size} bytes")
        }
        File(folder, "amsel.apk").also { if (!part.renameTo(it)) throw IOException("The update could not be kept") }
    }

    private suspend fun look(): UpdateState {
        _state.value = UpdateState.Looking
        val found = try {
            latest()
        } catch (e: IOException) {
            UpdateState.Failed
        } catch (e: SerializationException) {
            UpdateState.Failed
        }
        _state.value = found
        return found
    }

    private suspend fun latest(): UpdateState = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
            val release = json.decodeFromString(Release.serializer(), response.body.string())
            val version = release.tag.removePrefix("v")
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk") }
            if (apk != null && isNewer(version, current)) {
                UpdateState.Available(version, apk.url, apk.size)
            } else {
                UpdateState.Newest
            }
        }
    }

    @Serializable
    private data class Release(@SerialName("tag_name") val tag: String, val assets: List<Asset> = emptyList())

    @Serializable
    private data class Asset(
        val name: String,
        @SerialName("browser_download_url") val url: String,
        val size: Long = 0,
    )

    private companion object {
        /** Releases are published as regular releases, so the latest is the newest. */
        const val LATEST = "https://api.github.com/repos/hazymorning/Amsel/releases/latest"

        /** Where an asset of Amsel's releases downloads from; GitHub sends it on to its file host. */
        const val RELEASES = "https://github.com/hazymorning/Amsel/releases/download/"

        /** Far over the 8 MB an APK may take (rule 6): what is larger is not Amsel. */
        const val MAX_BYTES = 64L * 1024 * 1024
        const val BUFFER = 64 * 1024

        /** How far a download moves before the sheet hears of it. */
        const val STEP = 0.02f
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Whether [candidate] comes after [current], as versions such as `0.9.0-beta.14` are ordered:
 * numbers first, then a version without a suffix after one with, then the suffix part by part,
 * numbers by value.
 */
internal fun isNewer(candidate: String, current: String): Boolean = compareVersions(candidate, current) > 0

private fun compareVersions(a: String, b: String): Int {
    val (aMain, aPre) = a.split('-', limit = 2).let { it[0] to it.getOrNull(1) }
    val (bMain, bPre) = b.split('-', limit = 2).let { it[0] to it.getOrNull(1) }
    val main = compareParts(aMain.split('.'), bMain.split('.'))
    return when {
        main != 0 -> main
        aPre == bPre -> 0
        aPre == null -> 1
        bPre == null -> -1
        else -> compareParts(aPre.split('.'), bPre.split('.'))
    }
}

private fun compareParts(a: List<String>, b: List<String>): Int {
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrNull(i) ?: return -1
        val y = b.getOrNull(i) ?: return 1
        val xn = x.toLongOrNull()
        val yn = y.toLongOrNull()
        val order = when {
            xn != null && yn != null -> xn.compareTo(yn)
            xn != null -> -1
            yn != null -> 1
            else -> x.compareTo(y)
        }
        if (order != 0) return order
    }
    return 0
}
