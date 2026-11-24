package io.github.hazymorning.amsel.playback

import io.github.hazymorning.amsel.data.CurrentItemStatus
import io.github.hazymorning.amsel.data.ItemPosition
import io.github.hazymorning.amsel.data.SeekTracker
import kotlinx.coroutines.CompletableDeferred

/** The speakers' positions for a test: [calls] records what they are told; [currentItemStatus] is what the queue holds. */
class FakeSeekTracker(var currentItemStatus: CurrentItemStatus? = null) : SeekTracker {
    val calls = mutableListOf<String>()
    var pausedPosition: ItemPosition? = null

    /** Completes when the server has finished the play it is starting; null when it starts none. */
    var starting: CompletableDeferred<Unit>? = null
    private var tickets = 0L

    override fun currentItemStatus(queueId: String): CurrentItemStatus? = currentItemStatus

    override fun startSeek(queueId: String, place: ItemPosition): Long {
        pausedPosition = null
        return (++tickets).also { calls += "startSeek $it $queueId ${place.seconds} ${place.uri}" }
    }

    override fun sent(queueId: String, ticket: Long) {
        calls += "sent $ticket"
    }

    override fun answered(queueId: String, ticket: Long) {
        calls += "answered $ticket"
    }

    override fun failed(queueId: String, ticket: Long) {
        calls += "failed $ticket"
    }

    override fun setPausedPosition(queueId: String, place: ItemPosition) {
        calls += "setPausedPosition ${place.seconds}"
        pausedPosition = place
    }

    override fun pausedPosition(queueId: String): ItemPosition? = pausedPosition

    override suspend fun awaitStarted(queueId: String) {
        starting?.await()
    }
}
