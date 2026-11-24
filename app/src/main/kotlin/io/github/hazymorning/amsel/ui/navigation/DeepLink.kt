package io.github.hazymorning.amsel.ui.navigation

import android.net.Uri
import androidx.core.net.toUri

/**
 * Hosts of amsel:// links, opened by launcher shortcuts and widgets (Amsel's and third-party ones such
 * as KWGT): keep them stable, and declare each in the manifest's intent filter.
 */
enum class DeepLink(private val host: String) {
    Search("search"),

    /** Opens once the speaker reports what plays; with nothing playing, Home stays. */
    NowPlaying("now-playing"),

    Speakers("speakers"),

    /** As [NowPlaying], with Up next over it, or the chapters of a book or an episode. */
    UpNext("up-next"),

    /** The speaker picker, at its group step. */
    Group("group"),
    ;

    val link: Uri get() = "amsel://$host".toUri()

    companion object {
        fun of(uri: Uri?): DeepLink? {
            if (uri == null || !uri.scheme.equals("amsel", ignoreCase = true)) return null
            return entries.firstOrNull { it.host.equals(uri.host, ignoreCase = true) }
        }
    }
}
