package io.github.hazymorning.amsel

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import io.github.hazymorning.amsel.playback.PlaybackGraph
import io.github.hazymorning.amsel.ui.theme.CoverHues
import kotlinx.coroutines.launch

open class AmselApp :
    Application(),
    SingletonImageLoader.Factory {
    val graph: AppGraph by lazy { createGraph() }

    /** Tests replace the graph (for example with an in-memory token vault; Robolectric has no Keystore). */
    protected open fun createGraph(): AppGraph = AppGraph(this)

    override fun onCreate() {
        super.onCreate()
        // Covers' hues found before, so pages and Now Playing open already washed.
        CoverHues.load(this)
        // Sleep timers follow playback however it started: from the app, a car, a headset.
        PlaybackGraph.provider = { graph.playbackGraph.also { graph.sleepTimer.start() } }
        // The socket stays open while the app is in front (or something plays; see PlaybackService).
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    graph.data.session.setForeground(true)
                    graph.sleepTimer.start()
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
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = graph.imageLoader
}

val android.content.Context.graph: AppGraph get() = (applicationContext as AmselApp).graph
