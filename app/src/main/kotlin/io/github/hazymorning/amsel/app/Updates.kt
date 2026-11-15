package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.R
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** [version] is out; [apk] is its download. */
    data class Available(val version: String, val apk: String) : UpdateState

    /** GitHub could not be asked, or did not answer as expected. */
    data object Failed : UpdateState
}

/**
 * Asks GitHub for Amsel's newest release: when the person asks in Settings, and once when the app
 * starts, if they turned that on. Never in the background. [fetch] hands the download to Android:
 * its notification, tapped, opens Android's installer. Installing from the app itself would need a
 * permission Amsel does not take (rule 5).
 */
class Updates(
    private val http: OkHttpClient,
    private val current: String,
    private val messages: Messages,
    private val scope: CoroutineScope,
    private val fetch: (UpdateState.Available) -> Unit,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Unknown)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val lookedAtStart = AtomicBoolean(false)

    /** Once per run of the app, when it first comes to the front, if [enabled] says so. */
    fun lookAtStart(enabled: suspend () -> Boolean) {
        if (!lookedAtStart.compareAndSet(false, true)) return
        scope.launch {
            if (!enabled()) return@launch
            val found = look()
            if (found is UpdateState.Available) {
                messages.showUpdate(R.string.update_out, found.version) { download(found) }
            }
        }
    }

    fun lookNow() {
        if (_state.value == UpdateState.Looking) return
        scope.launch { look() }
    }

    fun download(update: UpdateState.Available) = fetch(update)

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
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk") }?.url
            if (apk != null && isNewer(version, current)) UpdateState.Available(version, apk) else UpdateState.Newest
        }
    }

    @Serializable
    private data class Release(@SerialName("tag_name") val tag: String, val assets: List<Asset> = emptyList())

    @Serializable
    private data class Asset(val name: String, @SerialName("browser_download_url") val url: String)

    private companion object {
        /** Releases are published as regular releases, so the latest is the newest. */
        const val LATEST = "https://api.github.com/repos/hazymorning/Amsel/releases/latest"
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
