package io.github.hazymorning.amsel.widget

/** What a widget's tap asks of Amsel. */
interface WidgetTransport {
    /** Opens the connection, as a command from outside the app does: what the speaker holds is known sooner. */
    fun wake()

    suspend fun play()

    suspend fun pause()

    suspend fun next()

    suspend fun previous()

    suspend fun play(item: WidgetItem)
}
