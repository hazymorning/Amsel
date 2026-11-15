package io.github.hazymorning.amsel.app

import android.content.Context
import android.text.format.Formatter
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.data.downloads.AbsAccount
import io.github.hazymorning.amsel.data.downloads.AbsClient
import io.github.hazymorning.amsel.data.downloads.AbsException
import io.github.hazymorning.amsel.data.downloads.DownloadManifest
import io.github.hazymorning.amsel.data.downloads.DownloadState
import io.github.hazymorning.amsel.data.downloads.Downloads
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.ui.model.DOT_SEPARATOR
import io.github.hazymorning.amsel.ui.model.DownloadMark
import io.github.hazymorning.amsel.ui.model.MediaUi
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One item's download: how far it has come and, once complete, what it holds and its cover. */
data class ItemDownload(
    val state: DownloadState? = null,
    val manifest: DownloadManifest? = null,
    val cover: File? = null,
)

/**
 * Downloads as the app offers them: a book or an episode from Audiobookshelf comes down to the
 * phone ([Downloads], in the download service) and plays from there without a connection. One that
 * stops says why, with Retry where trying again can help; a lapsed sign-in is let go, so the next
 * download asks for it, and signing in again carries on with what it had stopped.
 */
class Downloading(
    private val context: Context,
    private val downloads: Downloads,
    private val abs: AbsClient,
    private val messages: Messages,
    private val scope: CoroutineScope,
) {
    val states: StateFlow<Map<String, DownloadState>> get() = downloads.states
    val entries: StateFlow<Map<String, DownloadManifest>> get() = downloads.entries
    val account: StateFlow<AbsAccount?> get() = abs.account

    /** Each download as the lists mark it; what stopped is not marked, its page says so. */
    val marks: StateFlow<Map<String, DownloadMark>> = downloads.states.map { states ->
        states.mapNotNull { (uri, state) ->
            when (state) {
                DownloadState.Done -> uri to DownloadMark.Done
                is DownloadState.Waiting, is DownloadState.Running -> uri to DownloadMark.Coming
                is DownloadState.Failed -> null
            }
        }.toMap()
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        scope.launch {
            var before = downloads.states.value
            downloads.states.collect { now ->
                now.forEach { (uri, state) ->
                    if (state is DownloadState.Failed &&
                        before[uri] != state
                    ) {
                        failed(uri, state)
                    }
                }
                before = now
            }
        }
    }

    /** Downloads [uri]; the person is signed in to Audiobookshelf (the screens ask first). */
    fun download(uri: String) {
        scope.launch {
            when (downloads.add(uri)) {
                Downloads.Added.Yes, Downloads.Added.SignInFirst -> Unit

                Downloads.Added.NotFromAudiobookshelf -> messages.show(R.string.download_not_abs)

                Downloads.Added.NotSaved -> messages.show(R.string.download_not_saved, retry = { download(uri) })

                Downloads.Added.NotFound -> messages.show(PlaybackR.string.error_not_connected, retry = {
                    download(uri)
                })
            }
        }
    }

    /** Every download as a row of the Library's Downloads, the newest first, saying how far it has come. */
    val rows: Flow<List<MediaUi>> = combine(downloads.entries, downloads.states) { entries, states ->
        entries.values.sortedByDescending { it.addedAt }.map { row(it, states[it.uri]) }
    }.flowOn(Dispatchers.IO)

    /** [uri]'s download as its page shows it. */
    fun of(uri: String): Flow<ItemDownload> = combine(downloads.states, downloads.done) { states, done ->
        val manifest = done[uri]
        ItemDownload(states[uri], manifest, manifest?.let { downloads.cover(uri) })
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    /** Stops [uri]'s download, or deletes it from the phone; playing, it stops first ([LocalPlayback]). */
    fun remove(uri: String) {
        scope.launch { downloads.remove(uri) }
    }

    /** Starts what waits again: the app is in front, the only time Android lets the service start. */
    fun resume() {
        scope.launch { downloads.resume() }
    }

    /** Signs in to Audiobookshelf; null when it worked, else why not. */
    suspend fun signIn(address: String, username: String, password: String): AbsException.Reason? {
        try {
            abs.signIn(address, username, password)
        } catch (e: AbsException) {
            return e.reason
        } catch (e: IOException) {
            // The phone could not keep the sign-in.
            return AbsException.Reason.Server
        }
        downloads.states.value.filterValues { it == DownloadState.Failed(AbsException.Reason.SignedOut) }
            .keys.forEach(::download)
        return null
    }

    /** Downloads stay on the phone and play on; only new ones need the account. */
    fun signOut() {
        scope.launch {
            try {
                abs.signOut()
            } catch (e: IOException) {
                messages.show(R.string.settings_sign_out_failed, retry = ::signOut)
            }
        }
    }

    private fun row(manifest: DownloadManifest, state: DownloadState?): MediaUi {
        val res = context.resources
        val line = when (state) {
            is DownloadState.Running -> res.getString(R.string.downloading_percent, (state.fraction * PERCENT).toInt())

            is DownloadState.Waiting -> res.getString(
                when {
                    state.offline -> PlaybackR.string.download_waiting_network
                    state.forWifi -> PlaybackR.string.download_waiting_wifi
                    else -> R.string.download_waiting
                },
            )

            is DownloadState.Failed -> res.getString(R.string.download_stopped)

            DownloadState.Done, null ->
                listOfNotNull(manifest.author, Formatter.formatShortFileSize(context, manifest.bytes))
                    .joinToString(DOT_SEPARATOR)
        }
        return MediaUi(
            key = manifest.uri,
            kind = manifest.kind,
            title = manifest.title,
            subtitle = line,
            art = downloads.cover(manifest.uri)?.art(),
            downloadable = true,
        )
    }

    private suspend fun failed(uri: String, state: DownloadState.Failed) {
        val title = downloads.entries.value[uri]?.title.orEmpty()
        val retry = { download(uri) }
        when (state.reason) {
            AbsException.Reason.SignedOut -> {
                try {
                    abs.signOut()
                } catch (e: IOException) {
                    // The account stays shown until the next sign-out; the message says what to do.
                }
                messages.show(R.string.download_failed_signed_out, title)
            }

            AbsException.Reason.NotFound -> messages.show(R.string.download_failed_gone, title)

            AbsException.Reason.Refused -> messages.show(R.string.download_failed_refused, title)

            AbsException.Reason.Unreachable, AbsException.Reason.BadAddress ->
                messages.show(R.string.download_failed_unreachable, title, retry)

            AbsException.Reason.WrongLogin, AbsException.Reason.Server ->
                messages.show(R.string.download_failed_server, title, retry)

            null -> messages.show(R.string.download_failed_space, title, retry)
        }
    }
}

private const val PERCENT = 100
