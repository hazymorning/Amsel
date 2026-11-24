package io.github.hazymorning.amsel.ui.screens.pages

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.api.orNullOnFailure
import io.github.hazymorning.amsel.playback.failureText
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import io.github.hazymorning.amsel.ui.model.TrackUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** [playing] is the URI playing on the current speaker, or starting there. */
class SimilarSongsViewModel(
    private val key: String,
    private val source: PageSource,
    private val mapper: UiMapper,
    playing: Flow<String?>,
) : ViewModel() {
    private val page = MutableStateFlow(SimilarSongsUiState(loading = true))
    private var loading: Job? = null

    val state: StateFlow<SimilarSongsUiState> = combine(page, playing) { page, uri ->
        page.copy(currentTrackKey = uri)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SimilarSongsUiState(loading = true))

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        loading?.cancel()
        page.value = SimilarSongsUiState(loading = true)
        loading = viewModelScope.launch {
            val seed = async { orNullOnFailure { source.item(key) } }
            val found = try {
                source.similarSongs(key)
            } catch (e: MaCommandException) {
                // No music service knows the song: nothing to show or retry (docs/API-NOTES.md, "Extras").
                emptyList()
            } catch (e: MaConnectionException) {
                page.value = SimilarSongsUiState(failure = failureText(e))
                // Like every page, it fills in once the server reconnects.
                source.connectedAgain()
                return@launch retry()
            }
            page.value = SimilarSongsUiState(found.similarTo(key, seed.await()).map(mapper::track))
        }
    }
}

@Immutable
data class SimilarSongsUiState(
    val songs: List<TrackUi> = emptyList(),
    val currentTrackKey: String? = null,
    val loading: Boolean = false,
    @StringRes val failure: Int? = null,
) {
    /** The song URIs from [track] on, which a tap plays. */
    fun from(track: TrackUi): List<String> = songs.drop(songs.indexOf(track).coerceAtLeast(0)).map { it.key }
}
