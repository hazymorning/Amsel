package io.github.hazymorning.amsel.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.MessageAction
import io.github.hazymorning.amsel.app.MessageController

/**
 * Puts [messages] into [host] one at a time, for a `MessageHost` to show. A message with Retry,
 * Update or Download stays longer, so there is time to read it and decide; Undo goes with the
 * moment it belongs to.
 */
@Composable
fun ShowMessages(messages: MessageController, host: SnackbarHostState) {
    val resources = LocalResources.current
    val pending by messages.pending.collectAsStateWithLifecycle()
    val message = pending.firstOrNull() ?: return
    LaunchedEffect(message, host) {
        val text = message.arg?.let { resources.getString(message.text, it) } ?: resources.getString(message.text)
        val action = message.action
        val result = host.showSnackbar(
            text,
            actionLabel = when (action) {
                is MessageAction.Retry -> resources.getString(R.string.retry)
                is MessageAction.Undo -> resources.getString(R.string.undo)
                is MessageAction.Update -> resources.getString(R.string.update)
                is MessageAction.Download -> resources.getString(R.string.download)
                null -> null
            },
            duration = if (action is MessageAction.Undo ||
                action == null
            ) {
                SnackbarDuration.Short
            } else {
                SnackbarDuration.Long
            },
        )
        messages.seen(message)
        if (result == SnackbarResult.ActionPerformed) action?.run?.invoke()
    }
}
