package io.github.hazymorning.amsel.ui.screens.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.lengthText
import io.github.hazymorning.amsel.ui.model.DOT_SEPARATOR

@Composable
internal fun songsAndLength(songs: Int, durationSec: Int, lead: String? = null): String {
    val count = pluralStringResource(R.plurals.pages_songs, songs, songs)
    val length = if (durationSec > 0) lengthText(durationSec) else null
    return listOfNotNull(lead, count, length).joinToString(DOT_SEPARATOR)
}
