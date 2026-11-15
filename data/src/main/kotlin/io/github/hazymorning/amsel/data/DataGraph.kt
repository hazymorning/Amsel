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
import androidx.room.Room
import io.github.hazymorning.amsel.api.ItemUri
import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.api.MaEvent
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.api.PlayProgress
import io.github.hazymorning.amsel.api.ServerInfo
import io.github.hazymorning.amsel.api.itemByUri
import io.github.hazymorning.amsel.api.orNullOnFailure
import io.github.hazymorning.amsel.data.downloads.AbsClient
import io.github.hazymorning.amsel.data.downloads.AndroidNetworkState
import io.github.hazymorning.amsel.data.downloads.DownloadStore
import io.github.hazymorning.amsel.data.downloads.Downloads
import io.github.hazymorning.amsel.data.downloads.PrefsAbsAccountStore
import io.github.hazymorning.amsel.data.home.HomeLayoutStore
import io.github.hazymorning.amsel.data.home.HomeRepository
import io.github.hazymorning.amsel.data.home.SectionSources
import io.github.hazymorning.amsel.data.library.LibraryDb
import io.github.hazymorning.amsel.data.library.LibraryRepository
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Home layouts, one per server ([HomeLayoutStore]). */
private val Context.homeStore: DataStore<Preferences> by preferencesDataStore(
    name = "home",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The data layer's singletons and the one loop that turns server events into state. Built once
 * by the app's AppGraph; nothing heavy happens until [start].
 */
class DataGraph(
    context: Context,
    http: OkHttpClient,
    private val scope: CoroutineScope,
    vault: TokenVault,
    /** The networks the phone is on; the app's, as its http client follows [PhoneNetwork.plainHttpAllowed] too. */
    network: PhoneNetwork,
    /** Starts the service that runs [Downloads.work]; the app's, as the service is not this module's. */
    startDownloads: () -> Unit = {},
) {
    private val app = context.applicationContext
    val prefs = AppPrefs(app, scope)
    val session = Session(prefs, vault, http, scope, deviceName = "Amsel (${Build.MODEL})", network = network)
    val discovery = Discovery(app, http)
    private val db by lazy {
        Room.databaseBuilder(app, LibraryDb::class.java, "library.db").fallbackToDestructiveMigration(true).build()
    }
    val library by lazy {
        LibraryRepository(db.dao(), session.api, userId = { session.user.value?.userId })
    }
    val speakers = Speakers(session.api, SystemClock::elapsedRealtime, scope)
    val extras = Extras(session.api)

    /** The Audiobookshelf account downloads come from. */
    val abs = AbsClient(http, PrefsAbsAccountStore(prefs, vault, scope), reachesHome = session::reachesHome)

    val downloads by lazy {
        Downloads(
            abs,
            DownloadStore(File(app.noBackupFilesDir, "downloads")),
            AndroidNetworkState(app),
            prefs.prefs.map { it?.downloadOnWifi ?: true }.stateIn(scope, SharingStarted.Eagerly, true),
            itemAt = { session.api.itemByUri(it) },
            onWork = startDownloads,
        )
    }

    /** What each page last showed, for its next first paint. */
    val pages = PageMemory(File(app.cacheDir, "pages")) { prefs.current().serverId }

    /** The speaker music plays on, by player id: the one picked, else This phone. */
    private val speakerInUse =
        prefs.prefs.map { it?.speakerId ?: it?.phonePlayerId }.stateIn(scope, SharingStarted.Eagerly, null)

    val home = HomeRepository(
        SectionSources(session.api, { library }, { speakerInUse.value }),
        HomeLayoutStore(app.homeStore),
        File(app.filesDir, "home.json"),
        serverIds = prefs.prefs.filterNotNull().map { it.serverId },
        scope = scope,
        speakers = speakerInUse,
    )

    /** Everything loading for the signed-in server; [clear] ends it before forgetting the server. */
    @Volatile private var serverWork = serverScope()
    private val homeRefresh = Debounced()
    private val indexRefresh = Debounced()
    private val librarySync = Debounced()
    private val extrasReload = Debounced()
    private val _permissions = MutableStateFlow(EVERYTHING)

    /** What the user may change; everything until the server says otherwise, so nothing flickers. */
    val permissions: StateFlow<Permissions> = _permissions.asStateFlow()

    private val _speakersFailure = MutableStateFlow<Exception?>(null)

    /** Why the speakers could not be loaded after the last connect; null once they were. */
    val speakersFailure: StateFlow<Exception?> = _speakersFailure.asStateFlow()

    /** Loads the speakers again, for a Retry. */
    fun reloadSpeakers() {
        follow { loadSpeakers() }
    }

    /** Restores the session and starts following the server. Call from Application.onCreate's first use. */
    fun start() {
        // Apart: a car starting the app needs the connection, not Home's snapshot, and a refresh
        // that lands first is not overwritten by the older snapshot.
        scope.launch { home.load() }
        scope.launch { session.restore() }
        // A dropped socket waits out its backoff; the network coming back is reason to retry at once.
        app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = session.reconnect()
            },
        )
        // What was on its way goes on each time the app comes to the front: the download service may
        // start only then.
        scope.launch(Dispatchers.IO) {
            downloads.load()
            session.foreground.filter { it }.collect { downloads.resume() }
        }
        // A session that ends, signed out or its token refused, takes its server's data with it:
        // the next one may be another server or another person.
        scope.launch { session.state.filter { it == SessionState.NeedsSetup }.collect { clear() } }
        // Events are not replayed after a reconnect: reload what they would have told us.
        scope.launch {
            session.state.connects().collect { ready ->
                follow { loadSpeakers() }
                follow { library.syncIfNeeded(ready.server) }
            }
        }
        // What only the screens show waits until the app is in front, not for a connection the car,
        // a headset or the notification opened.
        scope.launch {
            session.state.connects().collectLatest { ready ->
                // At once, and in place of a reload an event left waiting for the app.
                homeRefresh.restart(waitMs = 0) {
                    inFront()
                    home.refresh()
                }
                inFront()
                follow {
                    _permissions.value = Permissions.of(ready.user?.role, session.api.scopes())
                }
                follow { extras.load() }
            }
        }
        // Home's options that read the index follow the kinds a sync replaced, once the app is in front.
        scope.launch {
            library.replaced.collect {
                indexRefresh.restart {
                    inFront()
                    if (library.holdsSpokenWord()) {
                        try {
                            home.offerListening()
                        } catch (e: IOException) {
                            // Not stored, so not offered either: the next kind replaced offers it again.
                        }
                    }
                    home.refreshIndexed()
                }
            }
        }
        // Random albums change between visits: new ones are picked as the app leaves the front.
        scope.launch {
            session.foreground.drop(1).filter { !it }.collect {
                if (session.state.value is SessionState.Ready) follow { home.reshuffle() }
            }
        }
        // A book or episode just finished or marked leaves Continue listening before Home reloads.
        scope.launch { library.progress.collect { if (it.fullyPlayed) home.finished(it.uri) } }
        scope.launch {
            session.client.events.collect { event ->
                speakers.onEvent(event)
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

    /** Home loads only while the app is in front: what changed meanwhile waits for it, stale. */
    private suspend fun inFront() = session.foreground.first { it }

    private suspend fun loadSpeakers() {
        _speakersFailure.value = try {
            speakers.refresh()
            null
        } catch (e: MaConnectionException) {
            e
        } catch (e: MaCommandException) {
            e
        }
    }

    /** Forgets the server's data after signing out, once nothing loads for it any more. */
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
        // Downloads are that server's books, kept under its uris; the account was signed in for them.
        downloads.clear()
        try {
            abs.signOut()
        } catch (e: IOException) {
            // The phone could not forget it; the next sign-in replaces it.
        }
    }

    /**
     * Loads for the server now connected. A dropped connection or a refusal keeps what was there,
     * and the next connect loads again.
     */
    private fun follow(load: suspend () -> Unit) = serverWork.launch { orNullOnFailure { load() } }

    private fun serverScope() = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    /**
     * A provider sync finished: sync the library index again once a burst of these events (one
     * per provider) settles and the app is in front, whose screens read the index; outside the
     * event loop, which a full sync would hold up for seconds.
     */
    private fun resyncLibrarySoon(server: ServerInfo) = librarySync.restart {
        inFront()
        library.syncAll(server)
    }

    /** Runs [load] once the events asking for it have settled; a newer request replaces a waiting one. */
    private inner class Debounced {
        private var job: Job? = null

        // Event and connect collectors restart it from different threads: unsynchronised, both
        // could cancel the same job and one of the two new ones would run unreplaced.
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

        /** Before the server names the person's scopes. */
        val EVERYTHING = Permissions(setOf("*"))

        /** Item changes the index-backed Home sections show; 2.10 sends a favorite only this way. */
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

/**
 * Whether [event] changes what Home shows for the person [userId]. Not a song added or liked (Home
 * lists songs only as played), not the report sent every 30 s while anything plays, and not
 * another person's progress: every socket receives every user's.
 */
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
 * Each time the session becomes Ready: the first connect, every reconnect, and a sign-in after
 * Sign out (which [DataGraph.clear] emptied), even on the same server. It names the user this
 * connection signed in, which [Session.user] learns apart from it.
 */
internal fun Flow<SessionState>.connects(): Flow<SessionState.Ready> =
    map { it as? SessionState.Ready }.distinctUntilChanged().filterNotNull()
