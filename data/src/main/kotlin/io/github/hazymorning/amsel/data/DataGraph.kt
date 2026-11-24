package io.github.hazymorning.amsel.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import io.github.hazymorning.amsel.api.ConnectionState
import io.github.hazymorning.amsel.api.ItemUri
import io.github.hazymorning.amsel.api.MaEvent
import io.github.hazymorning.amsel.api.MaException
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.api.PlayProgress
import io.github.hazymorning.amsel.api.ServerInfo
import io.github.hazymorning.amsel.api.itemByUri
import io.github.hazymorning.amsel.api.orNullOnFailure
import io.github.hazymorning.amsel.data.downloads.AbsClient
import io.github.hazymorning.amsel.data.downloads.AndroidNetworkState
import io.github.hazymorning.amsel.data.downloads.DownloadRepository
import io.github.hazymorning.amsel.data.downloads.DownloadStore
import io.github.hazymorning.amsel.data.downloads.PrefsAbsAccountStore
import io.github.hazymorning.amsel.data.home.HomeLayoutStore
import io.github.hazymorning.amsel.data.home.HomeRepository
import io.github.hazymorning.amsel.data.home.ServerSectionLoader
import io.github.hazymorning.amsel.data.library.LibraryRepository
import io.github.hazymorning.amsel.data.library.openLibraryDb
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

private val Context.homeStore: DataStore<Preferences> by preferencesDataStore(
    name = "home",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Built once, by AppGraph; nothing heavy runs before [start]. */
class DataGraph(
    context: Context,
    http: OkHttpClient,
    private val scope: CoroutineScope,
    vault: TokenCipher,
    // Shared with the app's http client, which also follows [PhoneNetwork.plainHttpAllowed].
    network: PhoneNetwork,
    // The service that runs [DownloadRepository.work] lives in the app module.
    startDownloads: () -> Unit,
) {
    private val app = context.applicationContext
    val prefs = PrefsStore(app, scope)
    val session = Session(prefs, vault, http, scope, deviceName = "Amsel (${Build.MODEL})", network = network)
    val discovery = ServerDiscovery(app, http)
    private val db by lazy { openLibraryDb(app) }
    val library by lazy {
        LibraryRepository(db.dao(), session.api, userId = { session.user.value?.userId })
    }
    val speakers = SpeakerRepository(session.api, SystemClock::elapsedRealtime, scope)
    val extras = ExtrasRepository(session.api)

    val abs = AbsClient(http, PrefsAbsAccountStore(prefs, vault, scope), reachesHome = session::reachesHome)

    val downloads by lazy {
        DownloadRepository(
            abs,
            DownloadStore(File(app.noBackupFilesDir, "downloads")),
            AndroidNetworkState(app),
            prefs.prefs.map { it?.downloadOnWifi ?: true }.stateIn(scope, SharingStarted.Eagerly, true),
            itemAt = { session.api.itemByUri(it) },
            onWork = startDownloads,
        )
    }

    val pages = PageCache(File(app.cacheDir, "pages")) { prefs.current().serverId }

    private val speakerInUse =
        prefs.prefs.map { it?.speakerId ?: it?.phonePlayerId }.stateIn(scope, SharingStarted.Eagerly, null)

    val home = HomeRepository(
        ServerSectionLoader(session.api, { library }, { speakerInUse.value }),
        HomeLayoutStore(app.homeStore),
        File(app.filesDir, "home.json"),
        serverIds = prefs.prefs.filterNotNull().map { it.serverId },
        scope = scope,
        speakers = speakerInUse,
    )

    // Cancelled and joined by [clear], so no load for the old server writes after it.
    @Volatile private var serverWork = serverScope()
    private val homeRefresh = Debounced()
    private val indexRefresh = Debounced()
    private val librarySync = Debounced()
    private val extrasReload = Debounced()
    private val _ended = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits after a session ended and its server's data was cleared; the app clears its own copies then. */
    val ended: SharedFlow<Unit> = _ended.asSharedFlow()

    private val _permissions = MutableStateFlow(EVERYTHING)

    // Everything until the server names the scopes, so no control flickers away and back.
    val permissions: StateFlow<UserPermissions> = _permissions.asStateFlow()

    private val _speakersFailure = MutableStateFlow<Exception?>(null)

    val speakersFailure: StateFlow<Exception?> = _speakersFailure.asStateFlow()

    fun reloadSpeakers() {
        follow { loadSpeakers() }
    }

    /**
     * For the widget: reads the speakers over a socket of its own while the app's is closed; while it is
     * connected, events keep them current. False when the server did not answer.
     */
    suspend fun refreshSpeakers(): Boolean =
        session.client.state.value is ConnectionState.Connected || session.readOnOwnSocket { speakers.read(it) } != null

    /** Call once, as the app starts. */
    fun start() {
        // Separate launches: Android Auto needs the connection, not Home's snapshot, and home.load() never
        // overwrites a refresh that finished first.
        scope.launch { home.load() }
        scope.launch { session.restore() }
        // A dropped socket would wait out its backoff, and one closed while [Session.stale] would stay
        // closed: a network that returns is reason to try at once.
        app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = session.reconnect()
            },
        )
        // Downloads resume each time the app comes to the foreground, as Android starts the download service
        // only then.
        scope.launch(Dispatchers.IO) {
            downloads.load()
            session.foreground.filter { it }.collect { downloads.resume() }
        }
        // A session that ends (signed out, or its token refused) takes its server's data with it: the next one
        // may be another server or another user.
        scope.launch {
            session.state.ends().collect {
                clear()
                _ended.emit(Unit)
            }
        }
        // Events are not replayed after a reconnect, so reload what they would have changed.
        scope.launch {
            session.state.connects().collect { ready ->
                follow { loadSpeakers() }
                follow { library.syncIfNeeded(ready.server) }
            }
        }
        // What only the screens show loads once the app is in the foreground, not on a connection opened by
        // Android Auto, a headset or the notification.
        scope.launch {
            session.state.connects().collectLatest { ready ->
                // No debounce; replaces a reload an event left waiting for the foreground.
                homeRefresh.restart(waitMs = 0) {
                    inFront()
                    home.refresh()
                }
                inFront()
                follow {
                    _permissions.value = UserPermissions.of(ready.user?.role, session.api.scopes())
                }
                follow { extras.load() }
            }
        }
        scope.launch {
            library.replaced.collect {
                indexRefresh.restart {
                    inFront()
                    if (library.holdsSpokenWord()) {
                        try {
                            home.offerListening()
                        } catch (e: IOException) {
                            // Not stored, so not offered: the next replaced kind tries again.
                        }
                    }
                    home.refreshIndexed()
                }
            }
        }
        // Random albums change between visits: reshuffled as the app leaves the foreground.
        scope.launch {
            session.foreground.drop(1).filter { !it }.collect {
                if (session.state.value is SessionState.Ready) follow { home.reshuffle() }
            }
        }
        // A finished or marked book or episode leaves Continue listening before Home reloads.
        scope.launch { library.progress.collect { if (it.fullyPlayed) home.finished(it.uri) } }
        // The speakers get a collector of their own: the library writes item events to its index and falls
        // behind during a provider sync's burst of events, and a pause must not wait for that.
        scope.launch { session.client.events.collect { speakers.onEvent(it) } }
        scope.launch {
            session.client.events.collect { event ->
                val indexChanged = library.onEvent(event)
                val ready = session.state.value as? SessionState.Ready
                if (event.name == "music_sync_completed" && ready != null) resyncLibrarySoon(ready.server)
                if (extras.reloadsOn(event)) {
                    extrasReload.restart {
                        inFront()
                        extras.load()
                    }
                }
                if (refreshesHome(event, session.user.value?.userId)) {
                    homeRefresh.restart {
                        inFront()
                        home.refresh()
                    }
                }
                if (indexChanged && event.name in INDEX_CHANGES) {
                    indexRefresh.restart {
                        inFront()
                        home.refreshIndexed()
                    }
                }
            }
        }
    }

    private suspend fun inFront() = session.foreground.first { it }

    private suspend fun loadSpeakers() {
        _speakersFailure.value = try {
            speakers.refresh()
            null
        } catch (e: MaException) {
            e
        }
    }

    private suspend fun clear() {
        serverWork.coroutineContext.job.cancelAndJoin()
        serverWork = serverScope()
        _permissions.value = EVERYTHING
        _speakersFailure.value = null
        speakers.clear()
        extras.clear()
        home.clear()
        library.clear()
        pages.clear()
        // Downloads are that server's items, keyed by its uris; the Audiobookshelf account was signed in for them.
        downloads.clear()
        try {
            abs.signOut()
        } catch (e: IOException) {
            // Left stored; the next sign-in replaces it.
        }
    }

    // A failure keeps what was loaded before; the next connect loads again.
    private fun follow(load: suspend () -> Unit) = serverWork.launch { orNullOnFailure { load() } }

    private fun serverScope() = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    // One music_sync_completed comes per provider sync: debounced, and run outside the event loop, which a full
    // sync would hold up for seconds. See docs/API-NOTES.md, "Events".
    private fun resyncLibrarySoon(server: ServerInfo) = librarySync.restart {
        inFront()
        library.syncAll(server)
    }

    private inner class Debounced {
        private var job: Job? = null

        // The event and connect collectors call this from different threads: unsynchronised, both could cancel
        // the same job and one of the two new jobs would run unreplaced.
        @Synchronized
        fun restart(waitMs: Long = EVENT_DEBOUNCE_MS, load: suspend () -> Unit) {
            job?.cancel()
            job = follow {
                delay(waitMs)
                load()
            }
        }
    }

    private companion object {
        const val EVENT_DEBOUNCE_MS = 2_000L

        val EVERYTHING = UserPermissions(setOf("*"))

        // 2.10 reports a favourite only as media_item_updated. See docs/API-NOTES.md, "Favourites".
        val INDEX_CHANGES = setOf("media_item_updated", "media_item_deleted")
    }
}

private val HOME_EVENTS = setOf(
    "media_item_played",
    "playlog_updated",
    "media_item_added",
    "favorite_updated",
    "music_sync_completed",
)

// No refresh for a song added or liked (Home lists songs only as played), the report sent every 30 s while
// anything plays, or another user's progress, which every socket receives. See docs/API-NOTES.md, "Events".
internal fun refreshesHome(event: MaEvent, userId: String?): Boolean {
    if (event.name !in HOME_EVENTS) return false
    if (event.name == "media_item_added" || event.name == "favorite_updated") {
        return event.objectId?.let(ItemUri::parse)?.mediaType != MediaType.Track.api
    }
    if (event.name != "media_item_played" && event.name != "playlog_updated") return true
    val played = event.decode(PlayProgress.serializer()) ?: return true
    return !played.isPlaying && (played.userId == null || played.userId == userId)
}

/**
 * Emits on every Ready: the first connect, each reconnect, and a sign-in after sign-out on the same server. Its
 * user is this connection's; [Session.user] is updated separately.
 */
internal fun Flow<SessionState>.connects(): Flow<SessionState.Ready> =
    map { it as? SessionState.Ready }.distinctUntilChanged().filterNotNull()

// NeedsSetup straight after Restoring ends nothing (nothing stored yet, or a token the Keystore cannot open),
// so nothing is cleared, the downloads least of all.
internal fun Flow<SessionState>.ends(): Flow<Unit> = flow {
    var before: SessionState = SessionState.Restoring
    collect { now ->
        if (now == SessionState.NeedsSetup && before != SessionState.Restoring && before != now) emit(Unit)
        before = now
    }
}
