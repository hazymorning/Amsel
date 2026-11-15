package io.github.hazymorning.amsel.app

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What a message's one button does: Retry tries the failed thing again; Undo takes back what was
 * just done; Update downloads a new version of Amsel; Download brings what plays onto the phone.
 */
sealed interface MessageAction {
    val run: () -> Unit

    data class Retry(override val run: () -> Unit) : MessageAction

    data class Undo(override val run: () -> Unit) : MessageAction

    data class Update(override val run: () -> Unit) : MessageAction

    data class Download(override val run: () -> Unit) : MessageAction
}

/** One plain sentence for the message bar, with at most one [action]. Never a code, never a dialog. */
class Message(@StringRes val text: Int, val arg: String? = null, val action: MessageAction? = null) {
    val retry: (() -> Unit)? get() = (action as? MessageAction.Retry)?.run
}

/**
 * App-wide messages; the shell shows them one at a time. They are for whoever has the app in front
 * ([inFront]): one said while it is away is dropped, and those not read go when it leaves, or they
 * would come up hours on, a Retry replaying an old command. The car and the notification hear of
 * playback failures from the session.
 */
class Messages(private val inFront: StateFlow<Boolean>) {
    private val _pending = MutableStateFlow<List<Message>>(emptyList())

    /** Oldest first. A message stays until [seen], so one cut short by a rotation shows again. */
    val pending: StateFlow<List<Message>> = _pending.asStateFlow()

    fun show(@StringRes text: Int, arg: String? = null, retry: (() -> Unit)? = null) =
        post(Message(text, arg, retry?.let(MessageAction::Retry)))

    /** Says what was just done, offering to take it back. */
    fun showUndo(@StringRes text: Int, arg: String? = null, undo: () -> Unit) =
        post(Message(text, arg, MessageAction.Undo(undo)))

    /** Says a new version is out, offering to download it. */
    fun showUpdate(@StringRes text: Int, arg: String, download: () -> Unit) =
        post(Message(text, arg, MessageAction.Update(download)))

    /** Offers to download what plays on this phone. */
    fun showDownload(@StringRes text: Int, arg: String, download: () -> Unit) =
        post(Message(text, arg, MessageAction.Download(download)))

    /** [message] was read, dismissed or acted on. */
    fun seen(message: Message) = _pending.update { it - message }

    /** Takes back what was said with [retry], once that Retry could no longer do anything. */
    fun withdraw(retry: () -> Unit) = _pending.update { all -> all.filterNot { it.retry === retry } }

    private fun post(message: Message) = synchronized(this) {
        if (inFront.value) _pending.update { (it + message).takeLast(MAX_PENDING) }
    }

    /** The app has left the front: call after [inFront] turned false. */
    fun dropUnread() = synchronized(this) { _pending.value = emptyList() }

    private companion object {
        const val MAX_PENDING = 4
    }
}

/** [retry] for a screen's messages, taken back when its ViewModel is cleared: it could do nothing then. */
internal fun ViewModel.retryWhileOpen(messages: Messages, retry: () -> Unit): () -> Unit =
    retry.also { addCloseable { messages.withdraw(it) } }
