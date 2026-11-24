package io.github.hazymorning.amsel.app

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.endlessMixUri
import io.github.hazymorning.amsel.data.DataGraph
import io.github.hazymorning.amsel.data.QueueState
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.PlayerControl
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class HintController(
    private val context: Context,
    private val data: DataGraph,
    private val offers: OfferController,
    private val messages: MessageController,
    private val favorites: FavoritesRepository,
    private val playlists: PlaylistEditor,
    private val playing: PlaybackState,
    private val playOn: PlayOnController,
    private val playback: PlaybackController,
    private val sleepTimer: SleepTimerController,
    private val scope: CoroutineScope,
) {
    // elapsedRealtime when Wi-Fi, also beneath a VPN, came up; LONG_AGO if it was up before
    // listening began; null without.
    @Volatile private var wifiSince: Long? = null

    private val wifis = ConcurrentHashMap.newKeySet<Network>()

    private val headphones = buildSet {
        add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        add(AudioDeviceInfo.TYPE_USB_HEADSET)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    private var started = false

    /** Main thread only; later calls do nothing. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            playing.speaker.map { it.id.takeUnless { _ -> it.isPhone } }.distinctUntilChanged().collect { id ->
                if (id != null) data.prefs.setHomeSpeaker(id)
            }
        }
        scope.launch {
            data.session.foreground.drop(1).filter { it }.collect {
                delay(SETTLE_MS)
                withContext(Dispatchers.Main) { cameBack() }
            }
        }
        scope.launch { followQueueEnds() }
        scope.launch(Dispatchers.Main) { playback.events.collect(::answer) }
        scope.launch(Dispatchers.Main) { playlists.added.collect { addedTo(it.playlist, it.open) } }
        listenForHeadphones()
        listenForWifi()
    }

    private fun answer(event: PlaybackEvent) = when (event) {
        is PlaybackEvent.Unreachable -> unreachable(event.speaker, event.again)
        is PlaybackEvent.Started -> started(event.speaker)
        is PlaybackEvent.SpokenStarted -> spokenStarted(event.title, event.speakerId)
    }

    fun liked(song: MediaUi, choose: () -> Unit) {
        if (song.kind != MediaKind.Track) return
        scope.launch {
            val prefs = data.prefs.current()
            val editable = playlists.editable()
            if (editable.isEmpty()) return@launch
            val last = editable.firstOrNull { it.key == prefs.lastPlaylistKey }
            val offer = if (last != null) {
                Offer(
                    about = "liked:${song.key}",
                    title = song.title,
                    art = song.art,
                    line = R.string.hint_liked_into,
                    lineArg = last.title,
                    button = R.string.hint_add,
                    hint = HINT_LIKED,
                    take = { playlists.add(song.key, last) },
                )
            } else {
                Offer(
                    about = "liked:${song.key}",
                    title = song.title,
                    art = song.art,
                    line = R.string.hint_liked_playlist,
                    button = R.string.hint_choose,
                    hint = HINT_LIKED,
                    take = choose,
                )
            }
            withContext(Dispatchers.Main) { offers.offer(offer) }
        }
    }

    fun unliked(uri: String, title: String) {
        messages.showUndo(R.string.hint_unliked, title) { favorites.set(uri, true) }
    }

    private fun addedTo(playlist: MediaUi, open: (() -> Unit)?) {
        scope.launch { data.prefs.setLastPlaylist(playlist.key) }
        if (open == null) return messages.show(R.string.added_to_playlist, playlist.title)
        offers.offer(
            Offer(
                about = "added:${playlist.key}",
                title = playlist.title,
                art = playlist.art,
                kind = MediaKind.Playlist,
                line = R.string.hint_added,
                button = R.string.hint_open,
                take = open,
            ),
        )
    }

    // No hint kind, so passing it up never turns it off: it stands in for the play that was held back.
    private fun unreachable(speaker: SpeakerUi, again: () -> Unit) {
        val phone = playing.speakers.value.firstOrNull { it.isPhone } ?: return
        offers.offer(
            Offer(
                about = "unreachable:${speaker.id}",
                title = playing.nameOf(speaker),
                glyph = R.drawable.ic_speaker,
                line = R.string.hint_unreachable,
                button = R.string.hint_play_here,
                take = {
                    scope.launch {
                        playOn.select(phone)
                        // The selection reaches the play's target a hop later, through the stored preferences.
                        withTimeoutOrNull(SELECT_WAIT_MS) { playing.speaker.first { it.isPhone } } ?: return@launch
                        withContext(Dispatchers.Main) { again() }
                    }
                },
            ),
        )
    }

    private fun started(speaker: SpeakerUi) {
        val silent = if (speaker.isPhone) phoneVolume() == 0 else speaker.volume == 0
        if (!silent) return
        offers.offer(
            Offer(
                about = "silent:${speaker.id}",
                title = playing.nameOf(speaker),
                glyph = if (speaker.isPhone) R.drawable.ic_phone else R.drawable.ic_speaker,
                line = R.string.hint_silent,
                button = R.string.hint_louder,
                hint = HINT_SILENT,
                take = {
                    if (speaker.isPhone) raisePhone() else playback.control(PlayerControl.SetVolume(LOUDER))
                },
            ),
        )
    }

    private fun spokenStarted(title: String, speakerId: String) {
        if (!atNight(LocalTime.now().hour)) return
        scope.launch {
            val prefs = data.prefs.current()
            val now = System.currentTimeMillis()
            if (prefs.sleepOnPlay || sleepTimer.timers.value[speakerId] != null) return@launch
            if (prefs.sleepOfferedAt?.let { now - it < NIGHT_MS } == true) return@launch
            data.prefs.setSleepOffered(now)
            val minutes = prefs.sleepMinutes
            withContext(Dispatchers.Main) {
                offers.offer(
                    Offer(
                        about = "sleep:$speakerId",
                        title = title,
                        glyph = R.drawable.ic_sleep,
                        line = R.string.hint_sleep,
                        button = R.string.hint_sleep_minutes,
                        buttonArg = minutes,
                        hint = HINT_SLEEP,
                        take = { sleepTimer.set(speakerId, SleepMode.Minutes(minutes)) },
                    ),
                )
            }
        }
    }

    private fun cameBack() {
        val now = playing.nowPlaying.value?.takeIf { it.isPlaying } ?: return
        val speaker = playing.speaker.value
        val wifi = wifiSince
        val away = wifi == null
        val arrived = wifi != null && SystemClock.elapsedRealtime() - wifi < ARRIVED_MS
        when {
            away && !speaker.isPhone -> {
                val phone = playing.speakers.value.firstOrNull { it.isPhone } ?: return
                offers.offer(
                    Offer(
                        about = "away:${speaker.id}",
                        title = now.title,
                        art = now.art,
                        kind = now.kind,
                        line = R.string.hint_away,
                        lineArg = playing.nameOf(speaker),
                        button = R.string.hint_play_here,
                        hint = HINT_AWAY,
                        take = { playOn.moveTo(phone, speaker) },
                    ),
                )
            }

            arrived && speaker.isPhone -> {
                val homeId = data.prefs.prefs.value?.homeSpeakerId ?: return
                val home = playing.speakers.value.firstOrNull { it.id == homeId }
                    ?.takeIf { it.available && !it.isPlaying } ?: return
                offers.offer(
                    Offer(
                        about = "home:${home.id}",
                        title = now.title,
                        art = now.art,
                        kind = now.kind,
                        line = R.string.hint_home,
                        lineArg = playing.nameOf(home),
                        button = R.string.resume,
                        hint = HINT_HOME,
                        take = { playOn.moveTo(home, speaker) },
                    ),
                )
            }
        }
    }

    private fun listenForHeadphones() {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        audio.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                // Android's first call lists the devices already connected, however late it comes: not new ones.
                private var listed = false

                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                    if (!listed) {
                        listed = true
                        return
                    }
                    if (added.none { it.isSink && it.type in headphones }) return
                    headphonesIn()
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    private fun headphonesIn() {
        val now = playing.nowPlaying.value?.takeIf { it.isPlaying } ?: return
        val speaker = playing.speaker.value.takeUnless { it.isPhone } ?: return
        val phone = playing.speakers.value.firstOrNull { it.isPhone } ?: return
        offers.offer(
            Offer(
                about = "headphones:${speaker.id}",
                title = now.title,
                art = now.art,
                kind = now.kind,
                line = R.string.hint_headphones,
                button = R.string.hint_play_here,
                hint = HINT_HEADPHONES,
                take = { playOn.moveTo(phone, speaker) },
            ),
        )
    }

    private suspend fun followQueueEnds() {
        var before: Pair<String, QueueState?>? = null
        combine(playing.speaker, data.speakers.queues) { speaker, queues -> speaker.id to queues[speaker.id] }
            .collect { now ->
                // Compares the same speaker's queue only: picking another speaker is not an ending.
                val last = before?.takeIf { it.first == now.first }?.second
                before = now
                val state = now.second ?: return@collect
                if (last == null || !queueEnded(last, state, SystemClock.elapsedRealtime())) return@collect
                val queue = state.queue
                val song = last.queue.currentItem?.mediaItem ?: return@collect
                if (song.mediaType != "track" || !data.extras.offered.value.mix) return@collect
                val title = song.name
                withContext(Dispatchers.Main) {
                    offers.offer(
                        Offer(
                            about = "ended:${queue.queueId}",
                            title = title,
                            art = playing.nowPlaying.value?.art,
                            line = R.string.hint_ended,
                            button = R.string.hint_go_on,
                            hint = HINT_ENDED,
                            take = {
                                playback.play(
                                    listOf(endlessMixUri(song.uri)),
                                    title = context.getString(R.string.endless_mix_of, title),
                                )
                            },
                        ),
                    )
                }
            }
    }

    // A NetworkRequest excludes VPNs by default, so this sees Wi-Fi or Ethernet beneath a VPN.
    private fun listenForWifi() {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val since = SystemClock.elapsedRealtime()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        manager.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (wifis.isEmpty()) {
                        val now = SystemClock.elapsedRealtime()
                        wifiSince = if (now - since < ALREADY_THERE_MS) LONG_AGO else now
                    }
                    wifis += network
                }

                override fun onLost(network: Network) {
                    wifis -= network
                    if (wifis.isEmpty()) wifiSince = null
                }
            },
        )
    }

    private fun phoneVolume(): Int? = context.getSystemService(AudioManager::class.java)
        ?.getStreamVolume(AudioManager.STREAM_MUSIC)

    private fun raisePhone() {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, max * LOUDER / 100, AudioManager.FLAG_SHOW_UI)
    }

    companion object {
        // Stored keys: the kinds Settings turns off and whose passes are counted.
        const val HINT_LIKED = "liked"
        const val HINT_SILENT = "silent"
        const val HINT_SLEEP = "sleep"
        const val HINT_AWAY = "away"
        const val HINT_HOME = "home"
        const val HINT_HEADPHONES = "headphones"
        const val HINT_ENDED = "ended"

        // Percent of the speaker's or the phone's range: audible without startling.
        private const val LOUDER = 30

        private const val NIGHT_MS = 8 * 60 * 60 * 1000L

        // Time for the speakers and what plays to be current again after the app comes back.
        private const val SETTLE_MS = 1_500L

        private const val SELECT_WAIT_MS = 2_000L

        // Networks reported this soon after registering were up already.
        private const val ALREADY_THERE_MS = 1_000L

        // Wi-Fi this recent when the app comes back means the user just got home; a longer window
        // would take a café's Wi-Fi for home more often.
        private const val ARRIVED_MS = 30 * 60 * 1000L

        // Wi-Fi already up when listening began: when it came is unknown.
        private const val LONG_AGO = Long.MIN_VALUE / 2
    }
}

// No ending: a queue left empty (its music moved away) or a dynamic one, which the server keeps filling.
internal fun queueEnded(before: QueueState, now: QueueState, nowMs: Long): Boolean {
    val queue = now.queue
    if (!before.queue.isPlaying || queue.state != IDLE || queue.isDynamic) return false
    if (queue.nextItem != null || queue.items == 0) return false
    val length = before.queue.currentItem?.duration ?: return false
    return before.secondsAt(nowMs) >= length - END_SLACK_S
}

internal fun atNight(hour: Int): Boolean = hour >= EVENING || hour < MORNING

private const val IDLE = "idle"

private const val END_SLACK_S = 10

private const val EVENING = 21
private const val MORNING = 5
