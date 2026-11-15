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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Small offers right after something the person did, or something that changed around them,
 * each with one button over the dock ([Offers]): a song liked goes into a playlist too, a silent
 * or unreachable speaker is mended, music follows the person out of the house and back in, a
 * queue that ran out goes on, a book at night gets a sleep timer. Only while the app is in front,
 * one at a time; a kind passed up three times in a row does not come again.
 */
class Hints(
    private val context: Context,
    private val data: DataGraph,
    private val offers: Offers,
    private val messages: Messages,
    private val favorites: Favorites,
    private val playlists: () -> PlaylistEditor,
    private val playing: Playing,
    private val playOn: () -> PlayOn,
    private val playback: () -> Playback,
    private val sleepTimer: SleepTimer,
    private val scope: CoroutineScope,
) {
    init {
        // The speaker to go back to at home: the last one picked other than This phone.
        scope.launch {
            playing.speaker.map { it.id.takeUnless { _ -> it.isPhone } }.distinctUntilChanged().collect { id ->
                if (id != null) data.prefs.setHomeSpeaker(id)
            }
        }
        // Back in front: where is the music, and where is the person?
        scope.launch {
            data.session.foreground.drop(1).filter { it }.collect {
                delay(SETTLE_MS)
                withContext(Dispatchers.Main) { cameBack() }
            }
        }
        scope.launch { followQueueEnds() }
        scope.launch(Dispatchers.Main) {
            listenForHeadphones()
            listenForWifi()
        }
    }

    /** When Wi-Fi beneath any VPN came up ([SystemClock.elapsedRealtime]); [LONG_AGO] if it was up before; null without. */
    @Volatile private var wifiSince: Long? = null

    private val wifis = ConcurrentHashMap.newKeySet<Network>()

    /** [song] was just liked here: it can go into a playlist too, the last one used at one tap. */
    fun liked(song: MediaUi, choose: () -> Unit) {
        if (song.kind != MediaKind.Track) return
        scope.launch {
            val prefs = data.prefs.current()
            val editable = playlists().editable()
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
                    take = { playlists().add(song.key, last) },
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

    /** [title] at [uri] was unliked here: a mistaken tap is taken back with Undo. */
    fun unliked(uri: String, title: String) {
        messages.showUndo(R.string.hint_unliked, title) { favorites.set(uri, true) }
    }

    /** A song went into [playlist]: said with its cover, and a way there where [open] leads. */
    fun addedTo(playlist: MediaUi, open: (() -> Unit)?) {
        scope.launch { data.prefs.setLastPlaylist(playlist.key, playlist.title) }
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

    /**
     * Something is about to play on [speaker], which cannot be reached: it plays on This phone
     * instead, at one tap ([replay] once the phone is the speaker). Never passed up for good, as it
     * stands in for the play. False where it can be reached, or nothing could be shown: the play
     * goes ahead then, and the server says why it fails.
     */
    fun unreachable(speaker: SpeakerUi, replay: () -> Unit): Boolean {
        if (speaker.isPhone || speaker.available) return false
        val phone = playing.speakers.value.firstOrNull { it.isPhone } ?: return false
        return offers.offer(
            Offer(
                about = "unreachable:${speaker.id}",
                title = playing.nameOf(speaker),
                glyph = R.drawable.ic_speaker,
                line = R.string.hint_unreachable,
                button = R.string.hint_play_here,
                take = {
                    scope.launch {
                        playOn().select(phone)
                        withContext(Dispatchers.Main) { replay() }
                    }
                },
            ),
        )
    }

    /** Something just started on the speaker in use: one that is all the way down is offered louder. */
    fun started() {
        val speaker = playing.speaker.value
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
                    if (speaker.isPhone) raisePhone() else playback().control(PlayerControl.SetVolume(LOUDER))
                },
            ),
        )
    }

    /** A book or an episode, [title], just started: late in the evening a sleep timer is a tap away. */
    fun spokenStarted(title: String) {
        if (!atNight(LocalTime.now().hour)) return
        val speakerId = playing.speaker.value.id
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

    /**
     * The app is in front again: music left playing at home while the phone is away comes along at
     * a tap, and music on the phone goes back to the speaker at home once the phone is there.
     */
    private fun cameBack() {
        val now = playing.nowPlaying.value?.takeIf { it.isPlaying } ?: return
        val speaker = playing.speaker.value
        val wifi = wifiSince
        val away = wifi == null
        // Just in from outside: Wi-Fi came up a little while ago, so a café's is rarely taken for home.
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
                        take = { playOn().moveTo(phone, speaker) },
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
                        take = { playOn().moveTo(home, speaker) },
                    ),
                )
            }
        }
    }

    /** Headphones plugged in or paired while a speaker plays: the music can come to the phone. */
    private fun listenForHeadphones() {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        // Android names the devices already there at once: those were not just connected.
        val since = SystemClock.elapsedRealtime()
        audio.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                    if (SystemClock.elapsedRealtime() - since < ALREADY_THERE_MS) return
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
                take = { playOn().moveTo(phone, speaker) },
            ),
        )
    }

    /**
     * The speaker in use played its last song and stopped: where the server makes endless mixes,
     * the music can go on with more like it.
     */
    private suspend fun followQueueEnds() {
        var before: Pair<String, QueueState?>? = null
        combine(playing.speaker, data.speakers.queues) { speaker, queues -> speaker.id to queues[speaker.id] }
            .collect { now ->
                // Only the same speaker's queue, before and after: picking another is no ending.
                val last = before?.takeIf { it.first == now.first }?.second
                before = now
                val state = now.second ?: return@collect
                if (last == null || !ranOut(last, state, SystemClock.elapsedRealtime())) return@collect
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
                                playback().play(
                                    listOf(endlessMixUri(song.uri)),
                                    title = context.getString(R.string.endless_mix_of, title),
                                )
                            },
                        ),
                    )
                }
            }
    }

    /** Wi-Fi or a cable beneath any VPN: a request leaves VPNs out by itself. */
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

    private val headphones = buildSet {
        add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        add(AudioDeviceInfo.TYPE_USB_HEADSET)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    private companion object {
        const val HINT_LIKED = "liked"
        const val HINT_SILENT = "silent"
        const val HINT_SLEEP = "sleep"
        const val HINT_AWAY = "away"
        const val HINT_HOME = "home"
        const val HINT_HEADPHONES = "headphones"
        const val HINT_ENDED = "ended"

        /** A level to hear, not to startle: the speaker's or the phone's, in percent. */
        const val LOUDER = 30

        /** Once a night: not again within this long. */
        const val NIGHT_MS = 8 * 60 * 60 * 1000L

        /** The speaker and what plays are known again this long after the app comes back. */
        const val SETTLE_MS = 1_500L

        /** Devices named this soon after listening began were there already. */
        const val ALREADY_THERE_MS = 1_000L

        /** Wi-Fi that came up within this long before the app came back: the person just got home. */
        const val ARRIVED_MS = 30 * 60 * 1000L

        /** Wi-Fi that was up before the app listened: when it came is not known. */
        const val LONG_AGO = Long.MIN_VALUE / 2
    }
}

/**
 * Whether a speaker's queue ran out on its own between [before] and [now]: it played, its last
 * song ran to its end, and it stopped with nothing after. Music that moved away, a pause or a stop
 * halfway is no ending, nor is a mix the server keeps flowing.
 */
internal fun ranOut(before: QueueState, now: QueueState, nowMs: Long): Boolean {
    val queue = now.queue
    if (!before.queue.isPlaying || queue.state != IDLE || queue.isDynamic) return false
    if (queue.nextItem != null || queue.items == 0) return false
    val length = before.queue.currentItem?.duration ?: return false
    return before.secondsAt(nowMs) >= length - END_SLACK_S
}

/** From nine in the evening until five in the morning: when a sleep timer is offered. */
internal fun atNight(hour: Int): Boolean = hour >= EVENING || hour < MORNING

private const val IDLE = "idle"

/** A song stopped this close to its end ran to it. */
private const val END_SLACK_S = 10

private const val EVENING = 21
private const val MORNING = 5
