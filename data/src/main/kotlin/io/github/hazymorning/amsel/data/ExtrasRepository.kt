package io.github.hazymorning.amsel.data

import io.github.hazymorning.amsel.api.DJ_PLUGIN
import io.github.hazymorning.amsel.api.DjHost
import io.github.hazymorning.amsel.api.MIX_PLUGIN
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaEvent
import io.github.hazymorning.amsel.api.QueueDj
import io.github.hazymorning.amsel.api.djHosts
import io.github.hazymorning.amsel.api.providers
import io.github.hazymorning.amsel.api.queueDjs
import io.github.hazymorning.amsel.api.setQueueDj
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ServerExtras(
    /** An endless mix of a song, album, artist or playlist. */
    val mix: Boolean = false,
    val similarSongs: Boolean = false,
    val similarArtists: Boolean = false,
    val djHosts: List<DjHost> = emptyList(),
)

class ExtrasRepository(private val api: MaApi) {
    private val _offered = MutableStateFlow(ServerExtras())
    val offered: StateFlow<ServerExtras> = _offered.asStateFlow()

    private val _djs = MutableStateFlow<Map<String, QueueDj>>(emptyMap())

    /** By queue id. */
    val djs: StateFlow<Map<String, QueueDj>> = _djs.asStateFlow()

    suspend fun load() {
        val loaded = api.providers().filter { it.available }
        val similarSongs = loaded.any { SIMILAR_SONGS in it.supportedFeatures }
        val hosts = if (loaded.any { it.domain == DJ_PLUGIN && it.isLoadedPlugin }) hosts() else emptyList()
        _offered.value = ServerExtras(
            // As in Music Assistant's own app: a mix needs a provider of similar songs.
            mix = similarSongs && loaded.any { it.domain == MIX_PLUGIN && it.isLoadedPlugin },
            similarSongs = similarSongs,
            similarArtists = loaded.any { SIMILAR_ARTISTS in it.supportedFeatures },
            djHosts = hosts,
        )
        _djs.value = if (hosts.isEmpty()) emptyMap() else api.queueDjs()
    }

    private suspend fun hosts(): List<DjHost> = try {
        api.djHosts()
    } catch (e: MaCommandException) {
        // A guest may not read the plugin's settings, where its hosts are kept.
        emptyList()
    }

    /**
     * Call before showing the DJ: 2.10 sends no event when a host or a speaker's DJ changes.
     * See docs/API-NOTES.md, "DJ changes".
     */
    suspend fun refreshDj() {
        if (_offered.value.djHosts.isEmpty()) return
        val hosts = hosts()
        _offered.value = _offered.value.copy(djHosts = hosts)
        _djs.value = if (hosts.isEmpty()) emptyMap() else api.queueDjs()
    }

    suspend fun setDj(queueId: String, hostId: String?) {
        _djs.value = api.setQueueDj(queueId, hostId)
    }

    fun reloadsOn(event: MaEvent): Boolean =
        event.name == "providers_updated" || (event.name == "provider_event" && event.objectId == DJ_PLUGIN)

    fun clear() {
        _offered.value = ServerExtras()
        _djs.value = emptyMap()
    }

    private companion object {
        const val SIMILAR_SONGS = "similar_tracks"
        const val SIMILAR_ARTISTS = "similar_artists"
    }
}
