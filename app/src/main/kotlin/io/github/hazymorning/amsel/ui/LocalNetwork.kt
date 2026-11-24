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
 * Without local network access (gated from API 37), the control socket, discovery and This phone's
 * Sendspin socket to a server on the local network all fail as unreachable.
 */
fun Context.needsLocalNetwork(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
    checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED

/**
 * Runs [action] after the local network prompt where one is needed, whatever the answer: a server
 * outside the local network works without it. The launcher is bound to this one action, so the
 * answer still runs it after the activity is recreated.
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
 * Retries an unreachable server at once instead of waiting out the backoff, then runs [load]; a
 * screen that reloads by itself on reconnecting leaves [load] out.
 */
@Composable
fun rememberRetry(load: () -> Unit = {}): () -> Unit {
    val session = LocalContext.current.graph.data.session
    return rememberLocalNetworkGate {
        session.reconnect()
        load()
    }
}
