package io.github.hazymorning.amsel

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.CERT_INPUT_RAW_X509
import android.content.pm.PackageManager.PackageInfoFlags
import android.os.Build
import android.os.SystemClock
import androidx.core.content.FileProvider
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.github.hazymorning.amsel.api.ConnectionState
import io.github.hazymorning.amsel.api.MaException
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.api.Schema
import io.github.hazymorning.amsel.api.itemByUri
import io.github.hazymorning.amsel.api.localCleartext
import io.github.hazymorning.amsel.api.plainHttpOnlyWhen
import io.github.hazymorning.amsel.app.ArtworkKeys
import io.github.hazymorning.amsel.app.ArtworkLoader
import io.github.hazymorning.amsel.app.BookmarkStore
import io.github.hazymorning.amsel.app.DownloadController
import io.github.hazymorning.amsel.app.DownloadOfferPolicy
import io.github.hazymorning.amsel.app.FavoritesRepository
import io.github.hazymorning.amsel.app.GroupVolumeController
import io.github.hazymorning.amsel.app.HintController
import io.github.hazymorning.amsel.app.ItemActionController
import io.github.hazymorning.amsel.app.MessageController
import io.github.hazymorning.amsel.app.OfferController
import io.github.hazymorning.amsel.app.PlayOnController
import io.github.hazymorning.amsel.app.PlaybackController
import io.github.hazymorning.amsel.app.PlaybackState
import io.github.hazymorning.amsel.app.PlaylistEditor
import io.github.hazymorning.amsel.app.QueueDjController
import io.github.hazymorning.amsel.app.RecentSearchStore
import io.github.hazymorning.amsel.app.SleepTimerController
import io.github.hazymorning.amsel.app.SpeakerGroupController
import io.github.hazymorning.amsel.app.TipController
import io.github.hazymorning.amsel.app.UpdateController
import io.github.hazymorning.amsel.data.AndroidPhoneNetwork
import io.github.hazymorning.amsel.data.DataGraph
import io.github.hazymorning.amsel.data.KeystoreTokenCipher
import io.github.hazymorning.amsel.data.PhoneNetwork
import io.github.hazymorning.amsel.data.SessionState
import io.github.hazymorning.amsel.data.TokenCipher
import io.github.hazymorning.amsel.playback.DataPlaybackGraph
import io.github.hazymorning.amsel.playback.DownloadService
import io.github.hazymorning.amsel.playback.LocalPlayback
import io.github.hazymorning.amsel.playback.PhoneSpeaker
import io.github.hazymorning.amsel.playback.PlayDispatcher
import io.github.hazymorning.amsel.playback.PlaybackGraph
import io.github.hazymorning.amsel.playback.PlaybackTarget
import io.github.hazymorning.amsel.playback.SeekDispatcher
import io.github.hazymorning.amsel.playback.SpeakerSelection
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.model.SpokenWord
import io.github.hazymorning.amsel.widget.OptimisticWidgetActions
import io.github.hazymorning.amsel.widget.SessionTransport
import io.github.hazymorning.amsel.widget.SpeakerTransport
import io.github.hazymorning.amsel.widget.WidgetController
import io.github.hazymorning.amsel.widget.WidgetItem
import io.github.hazymorning.amsel.widget.WidgetRefreshJobScheduler
import io.github.hazymorning.amsel.widget.WidgetSource
import io.github.hazymorning.amsel.widget.WidgetStore
import io.github.hazymorning.amsel.widget.widgetContent
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient

class AppGraph(context: Context, private val vault: TokenCipher = KeystoreTokenCipher()) {
    private val app = context.applicationContext

    /** Outlives screens: play actions and sync must finish even if the user navigates away. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val network: PhoneNetwork by lazy { AndroidPhoneNetwork(app) }

    /** For traffic outside [http], such as the Home Assistant sign-in WebView. */
    fun plainHttpAllowed(): Boolean = network.plainHttpAllowed()

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            // An https server redirected to http would carry the token in cleartext, past the local-only guard.
            .followSslRedirects(false)
            .build()
            .plainHttpOnlyWhen(network::plainHttpAllowed)
    }

    val data: DataGraph by lazy {
        DataGraph(app, http, scope, vault, network, startDownloads = { DownloadService.start(app) })
    }

    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        // Subscribed before the session starts, as data.ended replays nothing.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { data.ended.collect { forgetSession() } }
        data.start()
        PlaybackGraph.instance = playbackGraph
        scope.launch { widgets.follow() }
        scope.launch {
            merge(data.session.foreground, playbackGraph.serviceCreated).first { it }
            sleepTimer.start()
        }
    }

    // The next session may be another user's.
    private suspend fun forgetSession() {
        recentSearches.clear()
        bookmarks.clear()
        widgets.forgetContent()
        imageLoader.memoryCache?.clear()
        withContext(Dispatchers.IO) { imageLoader.diskCache?.clear() }
    }

    val local: LocalPlayback by lazy { LocalPlayback(app, data.downloads, data.abs, network.current, scope) }

    private val seekDispatcher: SeekDispatcher by lazy {
        SeekDispatcher(data.session.api, data.session::wake, data.speakers, phone, scope, SystemClock::elapsedRealtime)
    }

    val dispatcher: PlayDispatcher by lazy {
        PlayDispatcher(
            data.session.api,
            phone,
            scope,
            data.speakers,
            seekIn = seekDispatcher::seekIn,
            starting = seekDispatcher::starting,
            phoneMixStarts = local::stopDownloadPlayback,
            elapsedRealtime = SystemClock::elapsedRealtime,
        )
    }

    val phone: PhoneSpeaker by lazy {
        PhoneSpeaker(app, data, http, BuildConfig.VERSION_NAME, BuildConfig.DEBUG, scope)
    }

    private val selection: SpeakerSelection by lazy { SpeakerSelection(data, phone, scope) }

    val playbackGraph: DataPlaybackGraph by lazy {
        DataPlaybackGraph(
            data,
            dispatcher,
            seekDispatcher,
            selection.target,
            scope,
            phone,
            local,
            ArtworkLoader(app, imageLoader, scope),
            heart = playing.heart,
            onHeart = playing::toggleHeart,
            nameOf = playing::nameOf,
        )
    }

    val messages: MessageController by lazy { MessageController(data.session.foreground) }

    val offers: OfferController by lazy {
        OfferController(
            data.session.foreground,
            passes = { data.prefs.prefs.value?.hintPasses.orEmpty() },
            setPasses = { kind, passes -> scope.launch { data.prefs.setHintPasses(kind, passes) } },
            off = { data.prefs.prefs.value?.hintsOff.orEmpty() },
            scope = scope,
        )
    }

    val hints: HintController by lazy {
        HintController(app, data, offers, messages, favorites, playlists, playing, playOn, playback, sleepTimer, scope)
    }

    val favorites: FavoritesRepository by lazy {
        FavoritesRepository(
            data.session.api,
            data.library,
            messages,
            data.session.state,
            data.session.client.events,
            scope,
        )
    }

    val playing: PlaybackState by lazy {
        PlaybackState(app, data, selection.target, phone, dispatcher, local, mapper, favorites, scope)
    }

    val widgets: WidgetController by lazy {
        WidgetController(
            app,
            WidgetStore(app.filesDir.resolve(WIDGET_FOLDER)),
            imageLoader,
            scope,
            hands = { speaker ->
                if (speaker == null) {
                    SessionTransport(
                        app,
                        wakeSession = data.session::wake,
                        showPlaying = { on ->
                            playbackGraph.sessionTarget.value.playerId?.let { playing.optimistic.playing(it, on) }
                        },
                    )
                } else {
                    SpeakerTransport(
                        wakeSession = data.session::wake,
                        player = { playbackGraph.playerFor(PlaybackTarget(speaker, isPhone = false)) },
                        showPlaying = { on -> playing.optimistic.playing(speaker, on) },
                    )
                }
            },
            lastStarted = { playbackGraph.catalog.inProgress(1).firstOrNull()?.let { WidgetItem(it.uri, it.title) } },
            source = { widgetSource() },
            live = true,
            actions = OptimisticWidgetActions(app, data, playbackGraph.commands, playing.optimistic, favorites, scope),
            connected = { data.session.client.state.value is ConnectionState.Connected },
            refreshSpeakers = data::refreshSpeakers,
            plan = WidgetRefreshJobScheduler(app),
        )
    }

    private fun widgetSource() = object : WidgetSource {
        private val heard = combine(data.session.state, data.session.upToDate, ::Pair)

        override val content =
            combine(playing.speaker, playing.nowPlaying, heard, data.permissions) {
                    speaker,
                    np,
                    (session, current),
                    may,
                ->
                widgetContent(speaker, playing.nameOf(speaker), np, session, may.canWriteLibrary, current)
            }

        override fun contentOf(id: String) = combine(
            playing.speakers,
            playing.nowPlayingOf(id),
            heard,
            data.permissions,
            favorites.known,
        ) { speakers, np, (session, current), may, liked ->
            val speaker = speakers.firstOrNull { it.id == id } ?: SpeakerUi(id, "", available = false)
            val heart = np?.key?.let(liked::get)?.let { np.copy(favorite = it) } ?: np
            widgetContent(speaker, playing.nameOf(id), heart, session, may.canWriteLibrary, current)
        }

        override val failures =
            playbackGraph.failures.map { it.playerId to app.getString(it.text, playing.nameOf(it.playerId)) }
    }

    val playback: PlaybackController by lazy {
        PlaybackController(app, data, playbackGraph, playing, phone, dispatcher, messages, local, scope)
    }

    val playOn: PlayOnController by lazy {
        PlayOnController(data, playing, phone, local, dispatcher, messages, scope, playbackGraph.commands)
    }

    val groups: SpeakerGroupController by lazy { SpeakerGroupController(data, playing, phone, messages, scope) }

    val speakerVolumes: GroupVolumeController by lazy { GroupVolumeController(data, messages, scope) }

    val playlists: PlaylistEditor by lazy { PlaylistEditor(data.session.api, data.library, mapper, messages, scope) }

    val actions: ItemActionController by lazy {
        ItemActionController(
            data.session.api,
            data.library,
            playback,
            playlists,
            messages,
            data.home,
            data.permissions,
            favorites,
            downloading,
            data.session.state,
            scope,
            data.extras.offered,
            dynamicQueue = {
                val playsOn = playbackGraph.target.value.playerId
                data.speakers.queues.value[playsOn]?.queue?.isDynamic == true
            },
            mixTitle = { app.getString(R.string.endless_mix_of, it) },
            serverQueue = { playbackGraph.target.value.playerId?.let(playing::playsDownload) == false },
        )
    }

    val djs: QueueDjController by lazy { QueueDjController(data.extras, messages, scope, nameOf = playing::nameOf) }

    val mapper: UiMapper by lazy { UiMapper { data.session.images } }

    val sleepTimer: SleepTimerController by lazy { SleepTimerController(app, data, playing, local, messages, scope) }

    val downloading: DownloadController by lazy { DownloadController(app, data.downloads, data.abs, messages, scope) }

    val downloadOffer: DownloadOfferPolicy by lazy {
        DownloadOfferPolicy(
            playing.nowPlaying,
            data.session.foreground,
            data.downloads.states,
            offered = data.prefs::downloadOffered,
            setOffered = data.prefs::setDownloadOffered,
            itemAt = { uri ->
                try {
                    data.session.api.itemByUri(uri)?.let(mapper::media)
                } catch (e: MaException) {
                    null
                }
            },
        )
    }

    val tips: TipController by lazy {
        TipController(data.prefs.prefs, data.session.foreground, data.prefs::setTipSeen, scope)
    }

    val bookmarks: BookmarkStore by lazy { BookmarkStore(app, playing, playback, messages, scope) }
    val recentSearches: RecentSearchStore by lazy { RecentSearchStore(app, scope) }

    val updates: UpdateController by lazy {
        UpdateController(
            http,
            BuildConfig.VERSION_NAME,
            messages,
            scope,
            data.session.foreground,
            app.cacheDir.resolve(UPDATE_FOLDER),
            ::isOurs,
            ::openInstaller,
        )
    }

    val spokenWord: Flow<SpokenWord> by lazy {
        combine(data.library.heldTypes, data.session.state) { held, session ->
            val schema = (session as? SessionState.Ready)?.server?.schemaVersion ?: 0
            val podcasts = MediaType.Podcast in held
            SpokenWord(
                MediaType.Audiobook in held,
                podcasts,
                newEpisodes = podcasts && schema >= Schema.LATEST_EPISODES,
            )
        }
    }

    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(app)
            .components {
                add(ArtworkKeys)
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = {
                            val local = http.localCleartext()
                            Call.Factory { request -> (if (request.url.isHttps) http else local).newCall(request) }
                        },
                    ),
                )
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(app, 0.2).build() }
            .diskCache {
                DiskCache.Builder().directory(app.cacheDir.resolve("artwork")).maxSizeBytes(256L * 1024 * 1024).build()
            }
            .build()
    }

    // Android updates the app only from the same package name and key; another name installs as a new app.
    private fun isOurs(apk: File): Boolean {
        val pm = app.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apk.path, PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            // The flags as an Int are deprecated from API 33, where PackageInfoFlags replaces them.
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        } ?: return false
        val signing = info.signingInfo ?: return false
        val signers = with(signing) { if (hasMultipleSigners()) apkContentsSigners else signingCertificateHistory }
        return info.packageName == app.packageName &&
            signers.orEmpty().any { pm.hasSigningCertificate(app.packageName, it.toByteArray(), CERT_INPUT_RAW_X509) }
    }

    private fun openInstaller(apk: File): Boolean = try {
        app.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(FileProvider.getUriForFile(app, "${app.packageName}.updates", apk), APK_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

private const val APK_TYPE = "application/vnd.android.package-archive"

// In the cache: res/xml/updates.xml shares this path with the installer.
private const val UPDATE_FOLDER = "update"

private const val WIDGET_FOLDER = "widget"
