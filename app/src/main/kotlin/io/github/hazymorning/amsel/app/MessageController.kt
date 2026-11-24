package io.github.hazymorning.amsel.app

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface MessageAction {
    val run: () -> Unit

    data class Retry(override val run: () -> Unit) : MessageAction

    data class Undo(override val run: () -> Unit) : MessageAction

    data class Update(override val run: () -> Unit) : MessageAction

    data class Download(override val run: () -> Unit) : MessageAction
}

class Message(@StringRes val text: Int, val arg: String? = null, val action: MessageAction? = null) {
    val retry: (() -> Unit)? get() = (action as? MessageAction.Retry)?.run
}

/**
 * Only while the app is in front ([inFront]): unread messages go when it leaves, or they would show
 * hours later with a Retry replaying an old command. The car and the notification get playback
 * failures from the session.
 */
class MessageController(private val inFront: StateFlow<Boolean>) {
    private val _pending = MutableStateFlow<List<Message>>(emptyList())

    /** Oldest first. Each stays until [seen], so one cut short by a rotation shows again. */
    val pending: StateFlow<List<Message>> = _pending.asStateFlow()

    fun show(@StringRes text: Int, arg: String? = null, retry: (() -> Unit)? = null) =
        post(Message(text, arg, retry?.let(MessageAction::Retry)))

    fun showUndo(@StringRes text: Int, arg: String? = null, undo: () -> Unit) =
        post(Message(text, arg, MessageAction.Undo(undo)))

    fun showUpdate(@StringRes text: Int, arg: String, download: () -> Unit) =
        post(Message(text, arg, MessageAction.Update(download)))

    fun showDownload(@StringRes text: Int, arg: String, download: () -> Unit) =
        post(Message(text, arg, MessageAction.Download(download)))

    fun seen(message: Message) = _pending.update { it - message }

    fun withdraw(retry: () -> Unit) = _pending.update { all -> all.filterNot { it.retry === retry } }

    private fun post(message: Message) = synchronized(this) {
        if (inFront.value) _pending.update { (it + message).takeLast(MAX_PENDING) }
    }

    /** Call after [inFront] turned false, so nothing posted after the clear stays. */
    fun dropUnread() = synchronized(this) { _pending.value = emptyList() }

    private companion object {
        const val MAX_PENDING = 4
    }
}

/** A message with this Retry is withdrawn when the ViewModel is cleared: it could do nothing then. */
internal fun ViewModel.retryWhileOpen(messages: MessageController, retry: () -> Unit): () -> Unit =
    retry.also { addCloseable { messages.withdraw(it) } }
