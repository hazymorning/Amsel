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

class Offer(
    val about: String,
    val title: String,
    @StringRes val button: Int,
    val art: ArtRef? = null,
    val kind: MediaKind = MediaKind.Track,
    @DrawableRes val glyph: Int? = null,
    /** Null shows the speaker it plays on, where a tap picks another. */
    @StringRes val line: Int? = null,
    val lineArg: String? = null,
    val buttonArg: Int? = null,
    /** Set only on unasked offers: a kind passed up [GIVE_UP] times in a row is not offered again. */
    val hint: String? = null,
    /** Answers a tap on [about]: a second tap there takes it instead of showing it again. */
    val secondTapTakes: Boolean = false,
    val take: () -> Unit,
)

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

    /** False where nothing showed: the app is away, or this kind of hint is passed up or turned off. */
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

    fun dismiss(offer: Offer) {
        if (!_shown.compareAndSet(offer, null)) return
        offer.hint?.let { setPasses(it, (passes()[it] ?: 0) + 1) }
    }

    companion object {
        const val GIVE_UP = 3
    }
}
