package io.github.hazymorning.amsel

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.FileProvider
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.api.Schema
import io.github.hazymorning.amsel.api.itemByUri
import io.github.hazymorning.amsel.api.localCleartext
import io.github.hazymorning.amsel.api.plainHttpOnlyWhen
import io.github.hazymorning.amsel.app.ArtworkKeys
import io.github.hazymorning.amsel.app.ArtworkLoader
import io.github.hazymorning.amsel.app.Bookmarks
import io.github.hazymorning.amsel.app.DownloadOffer
import io.github.hazymorning.amsel.app.Downloading
import io.github.hazymorning.amsel.app.Favorites
import io.github.hazymorning.amsel.app.Hints
import io.github.hazymorning.amsel.app.ItemActions
import io.github.hazymorning.amsel.app.Messages
import io.github.hazymorning.amsel.app.Offers
import io.github.hazymorning.amsel.app.PlayOn
import io.github.hazymorning.amsel.app.Playback
import io.github.hazymorning.amsel.app.Playing
import io.github.hazymorning.amsel.app.PlaylistEditor
import io.github.hazymorning.amsel.app.QueueDjs
import io.github.hazymorning.amsel.app.RecentSearches
import io.github.hazymorning.amsel.app.SleepTimer
import io.github.hazymorning.amsel.app.SpeakerGroups
import io.github.hazymorning.amsel.app.SpeakerVolumes
import io.github.hazymorning.amsel.app.Tips
import io.github.hazymorning.amsel.app.Updates
import io.github.hazymorning.amsel.data.AndroidPhoneNetwork
import io.github.hazymorning.amsel.data.DataGraph
import io.github.hazymorning.amsel.data.KeystoreTokenVault
import io.github.hazymorning.amsel.data.PhoneNetwork
import io.github.hazymorning.amsel.data.SessionState
import io.github.hazymorning.amsel.data.TokenVault
import io.github.hazymorning.amsel.playback.DataPlaybackGraph
import io.github.hazymorning.amsel.playback.DownloadService
import io.github.hazymorning.amsel.playback.LocalPlayback
import io.github.hazymorning.amsel.playback.PhoneSpeaker
import io.github.hazymorning.amsel.playback.PlayDispatcher
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import io.github.hazymorning.amsel.ui.model.SpokenWord
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient

/**
 * Every app-wide singleton, built by hand (plain constructor injection, no DI framework). One
 * OkHttpClient serves the WebSocket, discovery and artwork. Everything is lazy: nothing heavy runs
 * in Application.onCreate.
 */
class AppGraph(context: Context, private val vault: TokenVault = KeystoreTokenVault()) {
    private val app = context.applicationContext

    /** Outlives screens: play actions and sync must finish even if the user navigates away. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val network: PhoneNetwork by lazy { AndroidPhoneNetwork(app) }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            // An https server redirected to http would carry the token in cleartext, past the local-only guard.
            .followSslRedirects(false)
            .build()
            .plainHttpOnlyWhen(network::plainHttpAllowed)
    }

    val data: DataGraph by lazy {
        DataGraph(app, http, scope, vault, network, startDownloads = { DownloadService.start(app) }).also { it.start() }
    }

    /** Downloads playing on this phone, built on first use. */
    val local: LocalPlayback by lazy { LocalPlayback(app, data.downloads, data.abs, network.current, scope) }

    val dispatcher: PlayDispatcher by lazy {
        PlayDispatcher(
            data.session.api,
            phone,
            scope,
            data.speakers,
            seekIn = { queueId, place -> playbackGraph.commands.seekIn(queueId, place) },
            starting = { queueId -> playbackGraph.commands.starting(queueId) },
            phoneMixStarts = { playback.stopDownload() },
            elapsedRealtime = SystemClock::elapsedRealtime,
        )
    }

    /** "This phone": built on first use; it connects while it is the selected speaker, or music moves away from it. */
    val phone: PhoneSpeaker by lazy {
        PhoneSpeaker(app, data, http, BuildConfig.VERSION_NAME, BuildConfig.DEBUG, scope)
    }

    val playbackGraph: DataPlaybackGraph by lazy {
        DataPlaybackGraph(
            data,
            dispatcher,
            scope,
            phone,
            local,
            ArtworkLoader(app, imageLoader, scope),
            nameOf = { playing.nameOf(it) },
            heartOf = { playing.heart },
            onHeart = { playing.toggleHeart() },
        )
    }

    val messages: Messages by lazy { Messages(data.session.foreground) }

    val offers: Offers by lazy {
        Offers(
            data.session.foreground,
            passes = { data.prefs.prefs.value?.hintPasses.orEmpty() },
            setPasses = { kind, passes -> scope.launch { data.prefs.setHintPasses(kind, passes) } },
            off = { data.prefs.prefs.value?.hintsOff.orEmpty() },
            scope = scope,
        )
    }

    val hints: Hints by lazy {
        Hints(app, data, offers, messages, favorites, {
            playlists
        }, playing, { playOn }, { playback }, sleepTimer, scope)
    }

    val favorites: Favorites by lazy {
        Favorites(data.session.api, data.library, messages, data.session.state, data.session.client.events, scope)
    }

    val playing: Playing by lazy {
        Playing(app, data, playbackGraph, phone, dispatcher, local, mapper, favorites, scope)
    }

    val playback: Playback by lazy {
        Playback(app, data, playbackGraph, playing, phone, dispatcher, messages, local, scope) { hints }
    }

    val playOn: PlayOn by lazy {
        PlayOn(data, playing, phone, local, dispatcher, messages, scope, playbackGraph.commands)
    }

    val groups: SpeakerGroups by lazy { SpeakerGroups(data, playing, phone, messages, scope) }

    val speakerVolumes: SpeakerVolumes by lazy { SpeakerVolumes(data, messages, scope) }

    val playlists: PlaylistEditor by lazy {
        PlaylistEditor(data.session.api, data.library, mapper, messages, scope) { playlist, open ->
            hints.addedTo(playlist, open)
        }
    }

    val actions: ItemActions by lazy {
        ItemActions(
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

    val djs: QueueDjs by lazy { QueueDjs(data.extras, messages, scope, nameOf = { playing.nameOf(it) }) }

    val mapper: UiMapper by lazy { UiMapper { data.session.images } }

    val sleepTimer: SleepTimer by lazy { SleepTimer(app, data, playing, local, messages, scope) }

    val downloading: Downloading by lazy { Downloading(app, data.downloads, data.abs, messages, scope) }

    val downloadOffer: DownloadOffer by lazy {
        DownloadOffer(
            playing.nowPlaying,
            data.session.foreground,
            data.downloads.states,
            offered = data.prefs::downloadOffered,
            setOffered = data.prefs::setDownloadOffered,
            itemAt = { uri ->
                try {
                    data.session.api.itemByUri(uri)?.let(mapper::media)
                } catch (e: MaConnectionException) {
                    null
                } catch (e: MaCommandException) {
                    null
                }
            },
        )
    }

    val tips: Tips by lazy { Tips(data.prefs.prefs, data.session.foreground, data.prefs::setTipSeen, scope) }

    val bookmarks: Bookmarks by lazy { Bookmarks(app, playing, playback, messages, scope) }
    val recentSearches: RecentSearches by lazy { RecentSearches(app, scope) }

    val updates: Updates by lazy {
        Updates(
            http,
            BuildConfig.VERSION_NAME,
            messages,
            scope,
            data.session.foreground,
            app.cacheDir.resolve(UPDATE_FOLDER),
            ::openInstaller,
        )
    }

    /** What the index holds of spoken word, for Home's and Library's choices. */
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

    /**
     * Android's installer, on an update downloaded into the cache: it asks before it replaces the app,
     * the first time also to let Amsel install apps. False where nothing on the phone installs one.
     */
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

/** In the cache, as `res/xml/updates.xml` shares it with the installer. */
private const val UPDATE_FOLDER = "update"
