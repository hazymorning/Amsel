package io.github.hazymorning.amsel

import android.app.SearchManager
import android.content.Intent
import android.graphics.Color
import android.media.session.MediaController
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.os.BundleCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import io.github.hazymorning.amsel.data.SessionState
import io.github.hazymorning.amsel.playback.PlaybackService
import io.github.hazymorning.amsel.ui.AppRoot
import io.github.hazymorning.amsel.ui.navigation.DeepLink
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var search: Intent? = null

    private var opening by mutableStateOf<DeepLink?>(null)

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        // No scrim under button navigation: the dock's background runs beneath the bar, and AppRoot
        // sets the buttons' light or dark appearance.
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        super.onCreate(savedInstanceState)
        val graph = graph
        // Kept until the stored session and Home's snapshot are loaded, so neither Setup, an empty
        // Home nor the default sections flash.
        splash.setKeepOnScreenCondition {
            graph.data.session.state.value == SessionState.Restoring || !graph.data.home.loaded.value
        }
        // A recreated activity restores a waiting request; its launch intent has played already.
        // Launched from Recents, Android delivers the task's first intent again: an old request.
        search = savedInstanceState?.let { BundleCompat.getParcelable(it, WAITING_SEARCH, Intent::class.java) }
            ?: intent.takeIf { savedInstanceState == null && it.isSearch && !it.fromHistory }
        opening = savedInstanceState?.getString(WAITING_OPENING)?.let(DeepLink::valueOf)
            ?: intent.takeIf { savedInstanceState == null && !it.fromHistory }?.let { DeepLink.of(it.data) }
        setContent { AppRoot(graph, opening, onOpened = { opening = null }) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    graph.hints.start()
                    graph.playback.connect()
                    playSearch()
                    // The system may not route the volume keys to a paused session; as the activity's
                    // media controller it gets them while Amsel is in front.
                    PlaybackService.current?.let {
                        mediaController =
                            MediaController(this@MainActivity, it.platformToken)
                    }
                    awaitCancellation()
                } finally {
                    graph.playback.release()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        search?.let { outState.putParcelable(WAITING_SEARCH, it) }
        opening?.let { outState.putString(WAITING_OPENING, it.name) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        DeepLink.of(intent.data)?.let {
            opening = it
            return
        }
        if (!intent.isSearch) return
        search = intent
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) playSearch()
    }

    private fun playSearch() {
        val request = search ?: return
        search = null
        graph.playback.playFromSearch(
            request.getStringExtra(SearchManager.QUERY).orEmpty(),
            request.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS),
        )
    }
}

private val Intent.isSearch: Boolean get() = action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH

private val Intent.fromHistory: Boolean get() = flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0

private const val WAITING_SEARCH = "waiting_search"

private const val WAITING_OPENING = "waiting_opening"
