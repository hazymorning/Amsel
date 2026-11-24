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
        // The socket stays open while the app is in front, or while something plays (DataPlaybackGraph).
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    graph.data.session.setForeground(true)
                    graph.scope.launch { if (graph.data.prefs.current().followPlaying) graph.playOn.followPlaying() }
                    // Acts on the first call only: the app's start, not a return to it.
                    graph.updates.checkAtStart { graph.data.prefs.current().lookForUpdates }
                }

                override fun onStop(owner: LifecycleOwner) {
                    graph.data.session.setForeground(false)
                    graph.messages.dropUnread()
                }
            },
        )
        // During playback the socket can drop while the phone sleeps; reconnecting at screen-on updates
        // the notification and widgets before the app is opened again.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = graph.data.session.tryAgain()
            },
            IntentFilter(Intent.ACTION_SCREEN_ON),
            // Only the system sends ACTION_SCREEN_ON. Not exported, AndroidX would need a permission below
            // Android 13 that the manifest removes, and throw.
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    /** Open for unit tests, which start nothing: each builds what it uses. */
    protected open fun startGraph() = graph.start()

    override fun newImageLoader(context: PlatformContext): ImageLoader = graph.imageLoader
}

val android.content.Context.graph: AppGraph get() = (applicationContext as AmselApp).graph
