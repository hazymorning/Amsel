package io.github.hazymorning.amsel.data.home

import io.github.hazymorning.amsel.api.MaJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

enum class SectionSource {
    Pinned,
    RecentlyPlayed,
    Playlists,
    Albums,
    Artists,
    Radio,
    Audiobooks,
    Podcasts,
    Search,
}

@Serializable
enum class SectionOption(val source: SectionSource) {
    Pinned(SectionSource.Pinned),
    Everything(SectionSource.RecentlyPlayed),

    /** Recently played on the speaker in use. */
    PlayedHere(SectionSource.RecentlyPlayed),

    ContinueListening(SectionSource.RecentlyPlayed),
    SmartPlaylists(SectionSource.Playlists),
    YourPlaylists(SectionSource.Playlists),

    @SerialName("FavouritePlaylists")
    FavoritePlaylists(SectionSource.Playlists),
    RecentPlaylists(SectionSource.Playlists),
    NewAlbums(SectionSource.Albums),
    RecentAlbums(SectionSource.Albums),

    @SerialName("FavouriteAlbums")
    FavoriteAlbums(SectionSource.Albums),
    RandomAlbums(SectionSource.Albums),
    TopArtists(SectionSource.Artists),

    @SerialName("FavouriteArtists")
    FavoriteArtists(SectionSource.Artists),
    RecentArtists(SectionSource.Artists),
    AllStations(SectionSource.Radio),

    @SerialName("FavouriteStations")
    FavoriteStations(SectionSource.Radio),
    RecentStations(SectionSource.Radio),
    NewAudiobooks(SectionSource.Audiobooks),

    /** The newest episode of every podcast in the library. */
    NewEpisodes(SectionSource.Podcasts),
    YourPodcasts(SectionSource.Podcasts),

    /** A field that opens Search with the keyboard up; it holds no items and is never empty. */
    Search(SectionSource.Search),
}

@Serializable
enum class SectionLayout(val maxItems: Int) {
    Grid(8),
    Small(12),
    Row(12),
    Big(8),
    List(5),
    ;

    // From four on, a grid shows whole rows (4, 6 or 8), never a lone card; pins all show, as each was chosen.
    fun shown(available: Int, option: SectionOption): Int {
        val fits = minOf(available, maxItems)
        if (this != Grid || option == SectionOption.Pinned || fits < GRID_ROWS_FROM) return fits
        return fits - fits % 2
    }

    private companion object {
        const val GRID_ROWS_FROM = 4
    }
}

/** A null [title] uses the option's own name; a null [titled] leaves the heading to the option ([showsTitle]). */
@Serializable
data class HomeSection(
    val id: String,
    val option: SectionOption,
    val layout: SectionLayout,
    val title: String? = null,
    val titled: Boolean? = null,
) {
    val showsTitle: Boolean get() = titled ?: (option != SectionOption.Search)
}

/**
 * One server's Home; [pinned] holds uris, newest first. [listeningOffered] and [stationsWidened] are false only on
 * a Home stored before Continue listening or all stations were defaults, until it is migrated once.
 */
@Serializable
data class HomeLayout(
    val sections: List<HomeSection> = DEFAULT_SECTIONS,
    val pinned: List<String> = emptyList(),
    val listeningOffered: Boolean = true,
    val stationsWidened: Boolean = true,
) {
    fun isPinned(uri: String): Boolean = uri in pinned

    fun add(): HomeLayout {
        val used = sections.map { it.option }.toSet()
        val option = SUGGESTED.firstOrNull { it !in used } ?: SUGGESTED.first()
        return copy(sections = sections + HomeSection(nextId(), option, SectionLayout.Row))
    }

    fun remove(id: String): HomeLayout = copy(sections = sections.filterNot { it.id == id })

    /** Pins are kept: the user chose each one. */
    fun reset(): HomeLayout = copy(sections = DEFAULT_SECTIONS, listeningOffered = true)

    fun move(id: String, index: Int): HomeLayout {
        val from = sections.indexOfFirst { it.id == id }
        if (from < 0) return this
        val to = index.coerceIn(0, sections.lastIndex)
        if (from == to) return this
        val moved = sections.toMutableList().apply { add(to, removeAt(from)) }
        return copy(sections = moved)
    }

    fun moveUp(id: String): HomeLayout = move(id, sections.indexOfFirst { it.id == id } - 1)

    fun moveDown(id: String): HomeLayout = move(id, sections.indexOfFirst { it.id == id } + 1)

    // Resets the title and heading: a name given to the old option would no longer fit.
    fun setOption(id: String, option: SectionOption): HomeLayout = update(id) {
        it.copy(option = option, title = null, titled = null)
    }

    fun setTitled(id: String, titled: Boolean): HomeLayout = update(id) { it.copy(titled = titled) }

    fun setTitle(id: String, title: String?): HomeLayout =
        update(id) { it.copy(title = title?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_TITLE)) }

    fun setLayout(id: String, layout: SectionLayout): HomeLayout = update(id) { it.copy(layout = layout) }

    // Without a Pinned section, one is added at the top as a grid, so the new pin is visible at once.
    fun pin(uri: String): HomeLayout {
        val pins = listOf(uri) + (pinned - uri)
        if (sections.any { it.option == SectionOption.Pinned }) return copy(pinned = pins)
        val section = HomeSection(nextId(), SectionOption.Pinned, SectionLayout.Grid)
        return copy(sections = listOf(section) + sections, pinned = pins)
    }

    fun unpin(uri: String): HomeLayout = copy(pinned = pinned - uri)

    /** Reorders the pins in [order] among the places they hold; other pins keep theirs. */
    fun arrangePins(order: List<String>): HomeLayout {
        val moving = order.distinct().filter { it in pinned }
        val next = moving.iterator()
        return copy(pinned = pinned.map { if (it in moving) next.next() else it })
    }

    /** Adds Continue listening at the top of a Home stored before it existed, once; removed, it stays removed. */
    fun offerListening(): HomeLayout {
        if (listeningOffered) return this
        if (sections.any { it.option == SectionOption.ContinueListening }) return copy(listeningOffered = true)
        val section = HomeSection(nextId(), SectionOption.ContinueListening, SectionLayout.Big)
        return copy(sections = listOf(section) + sections, listeningOffered = true)
    }

    fun encode(): String = MaJson.encodeToString(serializer(), this)

    private fun update(id: String, change: (HomeSection) -> HomeSection) =
        copy(sections = sections.map { if (it.id == id) change(it) else it })

    private fun nextId(): String {
        val taken = sections.map { it.id }.toSet()
        return generateSequence(1) { it + 1 }.map { "s$it" }.first { it !in taken }
    }

    companion object {
        // Long enough for any heading, short enough to remain one.
        const val MAX_TITLE = 60

        // Sections from older defaults keep their ids, so a Home never changed keeps its snapshots.
        val DEFAULT_SECTIONS = listOf(
            HomeSection("s5", SectionOption.Search, SectionLayout.Row),
            HomeSection("s0", SectionOption.ContinueListening, SectionLayout.Big, titled = false),
            HomeSection("s1", SectionOption.Pinned, SectionLayout.Grid),
            HomeSection("s2", SectionOption.Everything, SectionLayout.Row),
            HomeSection("s8", SectionOption.YourPlaylists, SectionLayout.List),
            HomeSection("s4", SectionOption.NewAlbums, SectionLayout.Row),
            HomeSection("s6", SectionOption.TopArtists, SectionLayout.Small),
            HomeSection("s3", SectionOption.SmartPlaylists, SectionLayout.Row),
            HomeSection("s7", SectionOption.AllStations, SectionLayout.Small),
        )

        // An earlier default's section, which showed nothing for a library without favourite stations. Left as it
        // came, it is read as all stations.
        private val FAVOURITE_STATIONS_AS_THEY_CAME =
            HomeSection("s7", SectionOption.FavoriteStations, SectionLayout.Small)

        private val SUGGESTED = listOf(
            SectionOption.Everything,
            SectionOption.YourPlaylists,
            SectionOption.FavoriteAlbums,
            SectionOption.TopArtists,
            SectionOption.SmartPlaylists,
            SectionOption.NewAlbums,
            SectionOption.AllStations,
        )

        /**
         * The defaults when nothing is stored or it cannot be read. A section this version does not know (a newer
         * app wrote it, or an older one showed the speakers there) is dropped, not the whole Home.
         */
        fun decode(json: String?): HomeLayout {
            // Text that is not JSON, or JSON of another shape, throws IllegalArgumentException.
            val root = try {
                json?.let { MaJson.parseToJsonElement(it) } as? JsonObject
            } catch (e: IllegalArgumentException) {
                null
            } ?: return HomeLayout()
            val sections = (root["sections"] as? JsonArray)?.mapNotNull { element ->
                try {
                    MaJson.decodeFromJsonElement(HomeSection.serializer(), element)
                } catch (e: IllegalArgumentException) {
                    null
                }
            } ?: return HomeLayout()
            val pinned = (root["pinned"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val offered = (root["listeningOffered"] as? JsonPrimitive)?.booleanOrNull ?: false
            val widened = (root["stationsWidened"] as? JsonPrimitive)?.booleanOrNull ?: false
            return HomeLayout(sections, pinned.orEmpty(), offered, widened).sanitized()
        }
    }

    // Favourite stations chosen since the widening stay: the Home that chose them was stored widened.
    private fun sanitized(): HomeLayout = HomeLayout(
        sections = sections.distinctBy { it.id }.map {
            val earlier = !stationsWidened && it == FAVOURITE_STATIONS_AS_THEY_CAME
            if (earlier) it.copy(option = SectionOption.AllStations) else it
        },
        pinned = pinned.distinct(),
        listeningOffered = listeningOffered,
    )
}
