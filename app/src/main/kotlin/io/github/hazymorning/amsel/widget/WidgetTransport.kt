package io.github.hazymorning.amsel.widget

interface WidgetTransport {
    /** Opens the server connection ahead of the command, so the speaker's state arrives sooner. */
    fun wake()

    suspend fun play()

    suspend fun pause()

    suspend fun next()

    suspend fun previous()

    suspend fun play(item: WidgetItem)
}
