package io.github.hazymorning.amsel.app

import android.os.SystemClock
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.MaException
import io.github.hazymorning.amsel.api.Player
import io.github.hazymorning.amsel.api.QueueOption
import io.github.hazymorning.amsel.api.membersOf
import io.github.hazymorning.amsel.data.DataGraph
import io.github.hazymorning.amsel.data.ItemPosition
import io.github.hazymorning.amsel.data.SessionState
import io.github.hazymorning.amsel.playback.LocalPlayback
import io.github.hazymorning.amsel.playback.MaSpeakerCommands
import io.github.hazymorning.amsel.playback.PhoneNotReadyException
import io.github.hazymorning.amsel.playback.PhoneSpeaker
import io.github.hazymorning.amsel.playback.PlayDispatcher
import io.github.hazymorning.amsel.playback.PlayRequest
import io.github.hazymorning.amsel.playback.failureText
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.model.positionAt
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class PlayOnController(
    private val data: DataGraph,
    private val playing: PlaybackState,
    private val phone: PhoneSpeaker,
    private val local: LocalPlayback,
    private val dispatcher: PlayDispatcher,
    private val messages: MessageController,
    private val scope: CoroutineScope,
    private val commands: MaSpeakerCommands,
) {
    private val steps = object : SpeakerSteps {
        override suspend fun select(target: SpeakerUi) = store(target)

        override suspend fun keepPhoneDuring(block: suspend () -> Unit) = phone.keepConnectedDuring(block)

        override suspend fun awaitPhone(): String = phone.awaitConnected()

        override fun releasePhoneHold() = phone.hold(false)

        override suspend fun transfer(from: String, to: String, play: Boolean) {
            // A seek made while paused was parked, never sent, so the server's transfer cannot carry it.
            val pausedPosition = data.speakers.pausedPosition(from)
            if (play) commands.starting(to)
            data.session.api.transfer(from, to, play)
            when {
                pausedPosition == null -> Unit
                play -> commands.seekIn(to, pausedPosition)
                else -> setPausedPositionOnceReported(to, pausedPosition)
            }
        }
    }

    val transferTargets: StateFlow<List<SpeakerUi>> = combine(playing.speakers, playing.speaker) { all, current ->
        all.filter { it.available && it.id != current.id }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** On [PlayOnAction.Ask] nothing has happened yet: the caller answers with [moveTo] or [select]. */
    suspend fun choose(target: SpeakerUi): PlayOnAction {
        val current = playing.speaker.value
        val music = held(current)
        val choice = choiceOf(
            sameSpeaker = target.id == current.id,
            fromPhone = current.isPhone,
            holds = music != null,
            plays = music?.isPlaying == true,
            targetPlays = target.isPlaying,
        )
        when (choice) {
            PlayOnAction.Stay, PlayOnAction.Ask -> Unit
            PlayOnAction.Select -> select(target)
            PlayOnAction.Move -> moveTo(target, current, play = music?.isPlaying == true)
        }
        return choice
    }

    // Only the speaker's own queue item or a download playing here: not another source's music
    // (AirPlay, an input), nor a play still on its way.
    private fun held(speaker: SpeakerUi): NowPlayingUi? = playing.nowPlaying.value?.takeIf {
        val own = playing.playsDownload(speaker.id) || data.speakers.currentItemStatus(speaker.id) != null
        own && speaker.id !in dispatcher.pending.value
    }

    /** Runs in the app's scope, so closing the sheet or leaving the screen does not stop it halfway. */
    fun moveTo(target: SpeakerUi, from: SpeakerUi = playing.speaker.value, play: Boolean = true) {
        val now = SystemClock.elapsedRealtime()
        val held = playing.nowPlaying.value
        // The position stands until the target reports it, and the source's volume is not the target's.
        val music = held?.copy(
            speaker = target,
            volume = null,
            positionMs = held.positionAt(now),
            positionAtMs = now,
            isPlaying = play,
            arriving = play,
        )
        val place = held?.key?.let { ItemPosition(it, held.positionAt(now) / MS_PER_SECOND) }
        val move: suspend () -> Unit = {
            when {
                place != null && playing.playsDownload(from.id) -> sendDownload(place, held.title, target, play)

                place != null && target.isPhone && data.downloads.manifest(
                    place.uri,
                ) != null -> playDownloadHere(place, from)

                else -> moveMusic(from, target, steps, play)
            }
        }
        scope.launch {
            val failure = try {
                return@launch if (music == null) move() else playing.whileMoving(music, move)
            } catch (e: PhoneNotReadyException) {
                e
            } catch (e: MaException) {
                e
            } catch (e: IOException) {
                e
            }
            // The phone's own sentence says whether trying again can help.
            val text = if (failure is PhoneNotReadyException) failureText(failure) else R.string.error_move_failed
            messages.show(
                text,
                playing.nameOf(target),
                retry = phone.afterStop(failure, scope) {
                    moveTo(target, from, play)
                },
            )
        }
    }

    // No speaker can reach this phone's files: the server plays the item on [to] from [place] instead.
    private suspend fun sendDownload(place: ItemPosition, title: String, to: SpeakerUi, play: Boolean) {
        store(to)
        withContext(Dispatchers.Main) { local.stop() }
        if (play) {
            val startMs = (place.seconds * MS_PER_SECOND).toLong()
            dispatcher.play(PlayRequest(to.id, listOf(place.uri), title = title, startPositionMs = startMs))
        } else {
            val api = data.session.api
            api.clearQueue(to.id)
            api.playMedia(to.id, listOf(place.uri), QueueOption.Add)
            setPausedPositionOnceReported(to.id, place)
        }
    }

    // On this phone a download always plays from its files, so [from] only pauses.
    private suspend fun playDownloadHere(place: ItemPosition, from: SpeakerUi) {
        store(playing.speakers.value.first { it.isPhone })
        data.session.api.pause(from.id)
        withContext(Dispatchers.Main) { local.play(place.uri, (place.seconds * MS_PER_SECOND).toLong()) }
    }

    private suspend fun setPausedPositionOnceReported(queueId: String, place: ItemPosition) {
        withTimeoutOrNull(REPORT_WAIT_MS) {
            data.speakers.queues.first { it[queueId]?.queue?.currentItem?.mediaItem?.uri == place.uri }
        }
        data.speakers.setPausedPosition(queueId, place)
    }

    suspend fun select(target: SpeakerUi) {
        try {
            store(target)
        } catch (e: IOException) {
            messages.show(R.string.error_select_failed, playing.nameOf(target), retry = {
                scope.launch { select(target) }
            })
        }
    }

    /** Waits for this connection's speaker list and changes nothing without one, rather than follow an old list. */
    suspend fun followPlaying() {
        val speakers = data.speakers
        if (data.session.state.value !is SessionState.Ready || speakers.loads.value == 0) {
            val before = speakers.loads.value
            withTimeoutOrNull(LIST_WAIT_MS) { speakers.loads.first { it > before } } ?: return
        }
        val skipped = data.prefs.current().followSkipped
        val leader = speakerToFollow(speakers.players.value, phone.playerId.value, phone.audible.value, skipped)
        val current = playing.speaker.value
        when {
            leader == null -> if (!current.isPhone) select(playing.speakers.value.first { it.isPhone })
            leader.playerId != current.id -> select(SpeakerUi(leader.playerId, leader.shownName))
        }
    }

    private suspend fun store(target: SpeakerUi) =
        data.prefs.setSpeaker(target.id.takeUnless { target.isPhone }, target.name)
}

/**
 * Null is This phone. Of several speakers that play, the one leading the most others wins; one
 * [skipped] in Settings counts only as the leader of one that is not.
 */
internal fun speakerToFollow(
    players: Map<String, Player>,
    phoneId: String?,
    phoneAudible: Boolean,
    skipped: Set<String> = emptySet(),
): Player? {
    if (phoneAudible || phoneId?.let(players::get)?.isPlaying == true) return null
    return players.values
        .filter { it.isShownSpeaker(phoneId) && it.available && it.isPlaying }
        .filter { leader ->
            leader.playerId !in skipped ||
                players.membersOf(leader.playerId).any { it.playerId !in skipped }
        }
        .maxByOrNull { players.membersOf(it.playerId).size }
        ?.takeUnless { it.playerId == phoneId }
}

/** Reconnects This phone before [retry] when its client had stopped for good. */
internal fun PhoneSpeaker.afterStop(error: Throwable, scope: CoroutineScope, retry: () -> Unit): () -> Unit =
    if ((error as? PhoneNotReadyException)?.stopped == true) {
        {
            scope.launch {
                reconnect()
                retry()
            }
        }
    } else {
        retry
    }

private const val LIST_WAIT_MS = 10_000L

private const val REPORT_WAIT_MS = 5_000L

private const val MS_PER_SECOND = 1000.0
