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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** What is known of Amsel's newest release. */
sealed interface UpdateState {
    /** Not asked yet in this run of the app. */
    data object Unknown : UpdateState

    data object Checking : UpdateState

    /** This is the newest. */
    data object Newest : UpdateState

    /** [version] is out; [apk] is its download, [size] bytes long (0 where GitHub does not say). */
    data class Available(val version: String, val apk: String, val size: Long = 0) : UpdateState

    /** [update] is downloading, [share] of it in, from 0 to 1. */
    data class Downloading(val update: Available, val share: Float) : UpdateState

    /** GitHub could not be asked, or did not answer as expected. */
    data object Failed : UpdateState
}

/**
 * Asks GitHub for Amsel's newest release: when the person asks in Settings, and once when the app
 * starts, if they turned that on. Never in the background. [install] downloads it into [folder] and,
 * once the app is in front, hands it to Android's installer ([openInstaller]), which asks before it
 * replaces the app; cancelled there, all stays as it was.
 */
class UpdateController(
    private val http: OkHttpClient,
    private val current: String,
    private val messages: MessageController,
    private val scope: CoroutineScope,
    private val inFront: StateFlow<Boolean>,
    private val folder: File,
    /** Whether a downloaded APK is Amsel under this app's own name and key. */
    private val isOurs: (File) -> Boolean,
    private val openInstaller: (File) -> Boolean,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Unknown)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val checkedAtStart = AtomicBoolean(false)

    /** The version downloaded in this run, and where: tapped again, it opens the installer at once. */
    @Volatile private var downloaded: Pair<String, File>? = null

    /**
     * Once per run of the app, when it first comes to the front, if [enabled] says so. The download
     * a last run left behind goes then: it is installed by now, or was not wanted.
     */
    fun checkAtStart(enabled: suspend () -> Boolean) {
        if (!checkedAtStart.compareAndSet(false, true)) return
        scope.launch {
            withContext(Dispatchers.IO) { folder.deleteRecursively() }
            if (!enabled()) return@launch
            val found = fetchLatest()
            if (found is UpdateState.Available) {
                messages.showUpdate(R.string.update_out, found.version) {
                    messages.show(R.string.update_downloading, found.version)
                    install(found)
                }
            }
        }
    }

    fun checkNow() {
        if (_state.value == UpdateState.Checking || _state.value is UpdateState.Downloading) return
        scope.launch { fetchLatest() }
    }

    /** Downloads [update], then opens Android's installer on it as soon as the app is in front. */
    fun install(update: UpdateState.Available) {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Downloading(update, 0f)
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
            } else if (!withContext(Dispatchers.IO) { isOurs(apk) }) {
                // Another app's name or another key: Android would install it beside Amsel, or refuse it.
                downloaded = null
                folder.deleteRecursively()
                messages.show(R.string.update_not_ours, update.version)
            } else {
                inFront.first { it }
                if (!openInstaller(apk)) messages.show(R.string.error_no_installer)
            }
        }
    }

    private suspend fun download(update: UpdateState.Available): File = withContext(Dispatchers.IO) {
        if (!isRelease(update.apk)) throw IOException("Not one of Amsel's releases: ${update.apk}")
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
                            _state.value = UpdateState.Downloading(update, share)
                        }
                    }
                }
            }
            if (update.size > 0 && done != update.size) throw IOException("Cut short at $done of ${update.size} bytes")
        }
        File(folder, "amsel.apk").also { if (!part.renameTo(it)) throw IOException("The update could not be kept") }
    }

    private suspend fun fetchLatest(): UpdateState {
        _state.value = UpdateState.Checking
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
    private data class Asset(val name: String, @SerialName("browser_download_url") val url: String, val size: Long = 0)

    private companion object {
        /** Releases are published as regular releases, so the latest is the newest. */
        const val LATEST = "https://api.github.com/repos/hazymorning/Amsel/releases/latest"

        /** Where an asset of Amsel's releases downloads from; GitHub sends it on to its file host. */
        val RELEASE_PATH = listOf("hazymorning", "Amsel", "releases", "download")

        /** An asset of Amsel's releases on GitHub, by its path once dot segments are resolved, not its text. */
        fun isRelease(url: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            return parsed.isHttps && parsed.host == "github.com" &&
                parsed.pathSegments.take(RELEASE_PATH.size) == RELEASE_PATH
        }

        /** Far over the 15 MB an APK may take (rule 6): what is larger is not Amsel. */
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
 * numbers by value. The versions counted by the calendar before 1.0 (`2026.11.57`) come before
 * every one since.
 */
internal fun isNewer(candidate: String, current: String): Boolean = compareVersions(candidate, current) > 0

private fun compareVersions(a: String, b: String): Int {
    val byCalendar = byCalendar(b).compareTo(byCalendar(a))
    if (byCalendar != 0) return byCalendar
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

/** Counted by the calendar, as versions were before 1.0: its first number a year. */
private fun byCalendar(version: String): Boolean =
    (version.substringBefore('.').toIntOrNull() ?: 0) >= FIRST_CALENDAR_YEAR

private const val FIRST_CALENDAR_YEAR = 2000

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
