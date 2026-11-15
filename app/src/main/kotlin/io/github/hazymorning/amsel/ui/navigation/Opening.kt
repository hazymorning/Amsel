package io.github.hazymorning.amsel.ui.navigation

import android.net.Uri

/**
 * Where a link opens Amsel: amsel://search, amsel://now-playing or amsel://speakers. The launcher's
 * shortcuts use them, and a widget (KWGT and the like) can open them as it opens any link.
 */
enum class Opening(private val host: String) {
    /** Search, empty and ready to type. */
    Search("search"),

    /** Now Playing, once the speaker says what plays; nothing opens over Home if nothing does. */
    NowPlaying("now-playing"),

    /** The speaker picker. */
    Speakers("speakers"),
    ;

    companion object {
        fun of(uri: Uri?): Opening? {
            if (uri == null || !uri.scheme.equals("amsel", ignoreCase = true)) return null
            return entries.firstOrNull { it.host.equals(uri.host, ignoreCase = true) }
        }
    }
}
