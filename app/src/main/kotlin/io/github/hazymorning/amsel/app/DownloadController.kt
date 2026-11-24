package io.github.hazymorning.amsel.app

import android.content.Context
import android.text.format.Formatter
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.data.downloads.AbsAccount
import io.github.hazymorning.amsel.data.downloads.AbsClient
import io.github.hazymorning.amsel.data.downloads.AbsException
import io.github.hazymorning.amsel.data.downloads.DownloadManifest
import io.github.hazymorning.amsel.data.downloads.DownloadRepository
import io.github.hazymorning.amsel.data.downloads.DownloadState
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

data class ItemDownloadState(
    val state: DownloadState? = null,
    val manifest: DownloadManifest? = null,
    val cover: File? = null,
)

class DownloadController(
    private val context: Context,
    private val downloads: DownloadRepository,
    private val abs: AbsClient,
    private val messages: MessageController,
    private val scope: CoroutineScope,
) {
    val states: StateFlow<Map<String, DownloadState>> get() = downloads.states
    val entries: StateFlow<Map<String, DownloadManifest>> get() = downloads.entries
    val account: StateFlow<AbsAccount?> get() = abs.account

    /** Lists leave a stopped download unmarked; its page says why. */
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

    /** Callers ask for the Audiobookshelf sign-in first. */
    fun download(uri: String) {
        scope.launch {
            when (downloads.add(uri)) {
                DownloadRepository.Added.Yes, DownloadRepository.Added.SignInFirst -> Unit

                DownloadRepository.Added.NotFromAudiobookshelf -> messages.show(R.string.download_not_abs)

                DownloadRepository.Added.NotSaved -> messages.show(R.string.download_not_saved, retry = {
                    download(uri)
                })

                DownloadRepository.Added.NotFound -> messages.show(PlaybackR.string.error_not_connected, retry = {
                    download(uri)
                })
            }
        }
    }

    val rows: Flow<List<MediaUi>> = combine(downloads.entries, downloads.states) { entries, states ->
        entries.values.sortedByDescending { it.addedAt }.map { row(it, states[it.uri]) }
    }.flowOn(Dispatchers.IO)

    fun of(uri: String): Flow<ItemDownloadState> = combine(downloads.states, downloads.done) { states, done ->
        val manifest = done[uri]
        ItemDownloadState(states[uri], manifest, manifest?.let { downloads.cover(uri) })
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    /** Cancels or deletes [uri]'s download; if it plays, LocalPlayback stops it once it is gone. */
    fun remove(uri: String) {
        scope.launch { downloads.remove(uri) }
    }

    /** Call while the app is in front: only then does Android let the download service start. */
    fun resume() {
        scope.launch { downloads.resume() }
    }

    /** Null when it worked, else why not. */
    suspend fun signIn(address: String, username: String, password: String): AbsException.Reason? {
        try {
            abs.signIn(address, username, password)
        } catch (e: AbsException) {
            return e.reason
        } catch (e: IOException) {
            // Network failures arrive as AbsException: this is the account failing to save.
            return AbsException.Reason.Server
        }
        downloads.states.value.filterValues { it == DownloadState.Failed(AbsException.Reason.SignedOut) }
            .keys.forEach(::download)
        return null
    }

    /** Keeps the downloads: they play without the account, which only new ones need. */
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
                // Forgets the lapsed sign-in, so the next download asks for it.
                try {
                    abs.signOut()
                } catch (e: IOException) {
                    // The account stays shown until the next sign-out; the message says what to do.
                }
                messages.show(R.string.download_failed_signed_out, title)
            }

            AbsException.Reason.NotFound -> messages.show(R.string.download_failed_gone, title)

            AbsException.Reason.Refused -> messages.show(R.string.download_failed_refused, title)

            AbsException.Reason.Unreachable,
            AbsException.Reason.BadAddress,
            AbsException.Reason.Certificate,
            AbsException.Reason.HttpsFailed,
            -> messages.show(R.string.download_failed_unreachable, title, retry)

            // No Retry: it cannot work from this network, and the message says why.
            AbsException.Reason.AwayFromHome -> messages.show(R.string.download_failed_away, title)

            AbsException.Reason.WrongLogin, AbsException.Reason.Server ->
                messages.show(R.string.download_failed_server, title, retry)

            null -> messages.show(R.string.download_failed_space, title, retry)
        }
    }
}

private const val PERCENT = 100
