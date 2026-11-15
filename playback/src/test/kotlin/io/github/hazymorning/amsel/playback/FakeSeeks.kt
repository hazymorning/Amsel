package io.github.hazymorning.amsel.playback

import io.github.hazymorning.amsel.data.Holding
import io.github.hazymorning.amsel.data.Place
import io.github.hazymorning.amsel.data.Seeks
import kotlinx.coroutines.CompletableDeferred

/** The speakers' positions for a test: [calls] records what they are told; [holding] is what the queue holds. */
class FakeSeeks(var holding: Holding? = null) : Seeks {
    val calls = mutableListOf<String>()
    var parked: Place? = null

    /** Completes when the server has finished the play it is starting; null when it starts none. */
    var starting: CompletableDeferred<Unit>? = null
    private var tickets = 0L

    override fun holding(queueId: String): Holding? = holding

    override fun fly(queueId: String, place: Place): Long {
        parked = null
        return (++tickets).also { calls += "fly $it $queueId ${place.seconds} ${place.uri}" }
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

    override fun park(queueId: String, place: Place) {
        calls += "park ${place.seconds}"
        parked = place
    }

    override fun parked(queueId: String): Place? = parked

    override suspend fun started(queueId: String) {
        starting?.await()
    }
}
