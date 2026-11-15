package io.github.hazymorning.amsel.ui.screens.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.Tip
import io.github.hazymorning.amsel.ui.components.TipCard
import io.github.hazymorning.amsel.ui.theme.Space

/** The tip above Home's first section, which closes up when it goes. */
internal fun LazyListScope.tip(tip: Tip, onGone: (Tip) -> Unit) {
    item(key = TIP, contentType = TIP) {
        TipCard(
            stringResource(tip.words),
            onGone = { onGone(tip) },
            Modifier.animateItem().padding(bottom = TipToHeading),
        )
    }
}

@get:StringRes
internal val Tip.words: Int
    get() = when (this) {
        Tip.EditHome -> R.string.tip_edit_home
        Tip.Settings -> R.string.tip_settings
        Tip.More -> R.string.tip_more
    }

private const val TIP = "tip"

/** From the card's edge to the first heading's capitals. */
private val TipToHeading = Space.S8
