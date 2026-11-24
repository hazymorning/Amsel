package io.github.hazymorning.amsel.app

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.hazymorning.amsel.data.HINTS_ALL
import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * One thing to do now, in a bar that slides up over the dock with one big [button]: playing what
 * was tapped on the speaker in use, or a step that helps after what was just done. [about] is what
 * it is for; where [secondTapTakes], tapping that again takes it, so a second tap plays at once.
 */
class Offer(
    val about: String,
    val title: String,
    @StringRes val button: Int,
    val art: ArtRef? = null,
    val kind: MediaKind = MediaKind.Track,
    /** Where it is about a speaker or a setting, not an item: its glyph in place of a cover. */
    @DrawableRes val glyph: Int? = null,
    /** Under the title; null names the speaker it plays on, and a tap there picks another. */
    @StringRes val line: Int? = null,
    val lineArg: String? = null,
    /** A button word with a number in it ("30 min"), in place of [button]'s plain word. */
    val buttonArg: Int? = null,
    /**
     * The kind of hint it is, where nothing was tapped for it: one passed up [GIVE_UP] times in a
     * row is not offered again. Null for what answers a tap.
     */
    val hint: String? = null,
    /** It answers a tap on [about], and a second tap there takes it; anything else asked again only shows anew. */
    val secondTapTakes: Boolean = false,
    val take: () -> Unit,
)

/**
 * The offer showing, one at a time: a new one takes the place of the last. Offers are for whoever
 * has the app in front ([inFront]); one made while it is away is dropped, and the one showing goes
 * when it leaves. [passes] and [setPasses] keep how often each hint went untaken; [off] holds the
 * kinds the person turned off in Settings, or [HINTS_ALL].
 */
class OfferController(
    private val inFront: StateFlow<Boolean> = MutableStateFlow(true),
    private val passes: () -> Map<String, Int> = { emptyMap() },
    private val setPasses: (String, Int) -> Unit = { _, _ -> },
    private val off: () -> Set<String> = { emptySet() },
    scope: CoroutineScope? = null,
) {
    private val _shown = MutableStateFlow<Offer?>(null)
    val shown: StateFlow<Offer?> = _shown.asStateFlow()

    init {
        scope?.launch { inFront.filter { !it }.collect { _shown.value = null } }
    }

    /**
     * Shows [offer]; while one that answers a tap on the same thing shows, this second ask takes that
     * one. False where nothing showed: the app is away, or the person keeps passing this hint up.
     */
    fun offer(offer: Offer): Boolean {
        val showing = _shown.value
        return when {
            showing != null && takes(offer) -> {
                take(showing)
                true
            }

            !inFront.value -> false

            offer.hint != null && (passes()[offer.hint] ?: 0) >= GIVE_UP -> false

            offer.hint != null && off().let { HINTS_ALL in it || offer.hint in it } -> false

            else -> {
                _shown.value = offer
                true
            }
        }
    }

    /** Whether [offer] would take the one showing rather than show. */
    fun takes(offer: Offer): Boolean = offer.secondTapTakes && _shown.value?.about == offer.about

    fun take(offer: Offer) {
        if (!_shown.compareAndSet(offer, null)) return
        offer.hint?.let { setPasses(it, 0) }
        offer.take()
    }

    /** [offer] went untaken: swiped away, or its time ran out. */
    fun dismiss(offer: Offer) {
        if (!_shown.compareAndSet(offer, null)) return
        offer.hint?.let { setPasses(it, (passes()[it] ?: 0) + 1) }
    }

    companion object {
        const val GIVE_UP = 3
    }
}
