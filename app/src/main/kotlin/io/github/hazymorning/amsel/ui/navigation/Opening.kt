package io.github.hazymorning.amsel.ui.navigation

import android.net.Uri
import androidx.core.net.toUri

/**
 * Where a link opens Amsel: amsel://search, amsel://now-playing, amsel://up-next or amsel://speakers.
 * The launcher's shortcuts use them, and a widget (KWGT and the like) can open them as it opens any link.
 */
enum class Opening(private val host: String) {
    /** Search, empty and ready to type. */
    Search("search"),

    /** Now Playing, once the speaker says what plays; nothing opens over Home if nothing does. */
    NowPlaying("now-playing"),

    /** The speaker picker. */
    Speakers("speakers"),

    /** Up next over Now Playing, or a book's or an episode's chapters, as Now Playing's last button opens them. */
    UpNext("up-next"),
    ;

    /** The link itself, as a widget opens it. */
    val link: Uri get() = "amsel://$host".toUri()

    companion object {
        fun of(uri: Uri?): Opening? {
            if (uri == null || !uri.scheme.equals("amsel", ignoreCase = true)) return null
            return entries.firstOrNull { it.host.equals(uri.host, ignoreCase = true) }
        }
    }
}
