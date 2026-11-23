package io.github.hazymorning.amsel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import kotlinx.coroutines.launch

open class AmselApp :
    Application(),
    SingletonImageLoader.Factory {
    val graph: AppGraph by lazy { createGraph() }

    /** Tests replace the graph (for example with an in-memory token vault; Robolectric has no Keystore). */
    protected open fun createGraph(): AppGraph = AppGraph(this)

    override fun onCreate() {
        super.onCreate()
        startGraph()
        // The socket stays open while the app is in front (or something plays; see PlaybackService).
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    graph.data.session.setForeground(true)
                    // Each time Amsel comes to the front, it can follow the music to the speaker that plays.
                    graph.scope.launch { if (graph.data.prefs.current().followPlaying) graph.playOn.followPlaying() }
                    // The first time only: a start of the app, never a return to it.
                    graph.updates.lookAtStart { graph.data.prefs.current().lookForUpdates }
                }

                override fun onStop(owner: LifecycleOwner) {
                    graph.data.session.setForeground(false)
                    graph.messages.dropUnread()
                }
            },
        )
        // While music plays the socket may have given up in the phone's sleep: the notification and
        // the widgets catch up as the screen comes on, not when Amsel is opened next.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = graph.data.session.tryAgain()
            },
            IntentFilter(Intent.ACTION_SCREEN_ON),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Unit tests start nothing: each builds what it uses. */
    protected open fun startGraph() = graph.start()

    override fun newImageLoader(context: PlatformContext): ImageLoader = graph.imageLoader
}

val android.content.Context.graph: AppGraph get() = (applicationContext as AmselApp).graph
