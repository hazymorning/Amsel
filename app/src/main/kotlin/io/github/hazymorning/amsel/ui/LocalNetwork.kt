package io.github.hazymorning.amsel.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import io.github.hazymorning.amsel.graph

/**
 * True when this Android version gates local network access (API 37) and Amsel does not have it
 * yet. Without it the control socket, discovery and This phone's Sendspin socket to a server on
 * the local network all fail as "Can't reach".
 */
fun Context.needsLocalNetwork(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
    checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED

/**
 * [action], which connects to the server, behind the permission: where the system needs it, the
 * prompt comes first (following the tap, so the reason is on screen), then the action runs
 * whatever the answer, since a server outside the local network works without it. Elsewhere it
 * runs at once. Bound to one action, so the answer still runs it after the activity is recreated.
 */
@Composable
fun rememberLocalNetworkGate(action: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val current by rememberUpdatedState(action)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { current() }
    return remember(context, ask) {
        {
            if (context.needsLocalNetwork()) ask.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) else current()
        }
    }
}

/**
 * Retry after a load failed: asks for local network access where it is missing, tries an
 * unreachable server at once instead of waiting out the backoff, then runs [load]; a screen that
 * reloads by itself on reconnecting leaves it out.
 */
@Composable
fun rememberRetry(load: () -> Unit = {}): () -> Unit {
    val session = LocalContext.current.graph.data.session
    return rememberLocalNetworkGate {
        session.reconnect()
        load()
    }
}
