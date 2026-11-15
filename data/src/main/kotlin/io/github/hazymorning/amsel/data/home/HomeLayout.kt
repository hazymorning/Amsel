package io.github.hazymorning.amsel.data.home

import io.github.hazymorning.amsel.api.MaJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

enum class SectionSource { Pinned, RecentlyPlayed, Playlists, Albums, Artists, Radio, Audiobooks, Podcasts, Speakers }

/**
 * The first chip of a section's sentence, "Show [option] as [layout]": one of its source's
 * options. The section's title says the option in plain words.
 */
@Serializable
enum class SectionOption(val source: SectionSource) {
    Pinned(SectionSource.Pinned),
    Everything(SectionSource.RecentlyPlayed),

    /** What last played on the speaker in use: "Recently played · Kitchen". */
    PlayedHere(SectionSource.RecentlyPlayed),

    /** Books and episodes in progress. */
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

    /**
     * Every speaker in reach, This phone first, with what it plays and how loud: the app's own
     * speakers, not the server's items, and only while a speaker other than This phone is in reach.
     */
    Speakers(SectionSource.Speakers),
}

/** The second chip: how a section draws its items. Five layouts, one visual language. */
@Serializable
enum class SectionLayout(val maxItems: Int) {
    /** Compact two-column cards: shortcuts. */
    Grid(8),

    /** A sideways shelf of small square covers: three and a third per screen. */
    Small(12),

    /** A sideways shelf of square covers: two and a third per screen. */
    Row(12),

    /** Larger square covers: two per screen and a sliver of the third. */
    Big(8),

    /** Rows with a thumbnail. */
    List(5),
    ;

    /**
     * How many of [available] items this layout shows. From four on, a grid shows whole rows (4, 6
     * or 8) so it never ends on a lone card, except for pins: each one was chosen, so none is held back.
     */
    fun shown(available: Int, option: SectionOption): Int {
        val fits = minOf(available, maxItems)
        if (this != Grid || option == SectionOption.Pinned || fits < GRID_ROWS_FROM) return fits
        return fits - fits % 2
    }

    private companion object {
        const val GRID_ROWS_FROM = 4
    }
}

/**
 * [title] is the person's own name for the section; null says the option in plain words. [titled]
 * shows or hides its heading; null leaves it to the option ([showsTitle]).
 */
@Serializable
data class HomeSection(
    val id: String,
    val option: SectionOption,
    val layout: SectionLayout,
    val title: String? = null,
    val titled: Boolean? = null,
) {
    /** Whether its heading shows: as the person set it, else for every option but Speakers, whose cards say what they are. */
    val showsTitle: Boolean get() = titled ?: (option != SectionOption.Speakers)
}

/**
 * The person's Home on one server: its sentences in order, as many as they like, and the items
 * they pinned (uris, newest first). Every edit is a pure function that returns the new layout.
 * [listeningOffered] is false on a Home made before Continue listening existed, until it is offered.
 */
@Serializable
data class HomeLayout(
    val sections: List<HomeSection> = DEFAULT_SECTIONS,
    val pinned: List<String> = emptyList(),
    val listeningOffered: Boolean = true,
) {
    fun isPinned(uri: String): Boolean = uri in pinned

    /**
     * A new section at the end, as a row, showing the first useful option not on Home yet; with
     * all of those there, the first of them once more.
     */
    fun add(): HomeLayout {
        val used = sections.map { it.option }.toSet()
        val option = SUGGESTED.firstOrNull { it !in used } ?: SUGGESTED.first()
        return copy(sections = sections + HomeSection(nextId(), option, SectionLayout.Row))
    }

    fun remove(id: String): HomeLayout = copy(sections = sections.filterNot { it.id == id })

    /** Moves section [id] to [index] in the order (clamped to the list). */
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

    /**
     * A new option brings its own title, shown or not as that option is: a name given to the old one
     * would no longer fit. Speakers is a shelf and nothing else.
     */
    fun setOption(id: String, option: SectionOption): HomeLayout = update(id) {
        val layout = if (option == SectionOption.Speakers) SectionLayout.Row else it.layout
        it.copy(option = option, title = null, titled = null, layout = layout)
    }

    /** Shows or hides section [id]'s heading. */
    fun setTitled(id: String, titled: Boolean): HomeLayout = update(id) { it.copy(titled = titled) }

    /** Names section [id]; a blank name gives back the option's own. */
    fun setTitle(id: String, title: String?): HomeLayout =
        update(id) { it.copy(title = title?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_TITLE)) }

    fun setLayout(id: String, layout: SectionLayout): HomeLayout = update(id) { it.copy(layout = layout) }

    /**
     * Pins [uri] first. Without a Pinned section, one comes in at the top as a grid: a pin should
     * show where the person looks.
     */
    fun pin(uri: String): HomeLayout {
        val pins = listOf(uri) + (pinned - uri)
        if (sections.any { it.option == SectionOption.Pinned }) return copy(pinned = pins)
        val section = HomeSection(nextId(), SectionOption.Pinned, SectionLayout.Grid)
        return copy(sections = listOf(section) + sections, pinned = pins)
    }

    fun unpin(uri: String): HomeLayout = copy(pinned = pinned - uri)

    /** The pins in [order] take that order, in the places they held; other pins stay where they are. */
    fun arrangePins(order: List<String>): HomeLayout {
        val moving = order.distinct().filter { it in pinned }
        val next = moving.iterator()
        return copy(pinned = pinned.map { if (it in moving) next.next() else it })
    }

    /**
     * Continue listening at the top of a Home made before it existed, once, when the library first
     * holds audiobooks or podcasts. Removed, it stays away.
     */
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
        /** Long enough for any heading, short enough to stay one. */
        const val MAX_TITLE = 60

        /**
         * A new person's Home: Continue listening and Pinned (each hidden while empty), then three
         * rows. Continue listening keeps the id before the others', so theirs stay as they were.
         */
        val DEFAULT_SECTIONS = listOf(
            HomeSection("s0", SectionOption.ContinueListening, SectionLayout.Big),
            HomeSection("s1", SectionOption.Pinned, SectionLayout.Grid),
            HomeSection("s2", SectionOption.Everything, SectionLayout.Row),
            HomeSection("s3", SectionOption.SmartPlaylists, SectionLayout.Row),
            HomeSection("s4", SectionOption.NewAlbums, SectionLayout.Row),
        )

        /** What "Add section" offers, most useful first. */
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
         * The stored layout, or the defaults when nothing is stored yet or it cannot be read. One
         * section this version does not know (a newer app wrote it) is dropped, not the whole Home.
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
            return HomeLayout(sections, pinned.orEmpty(), offered).sanitized()
        }
    }

    /** Unique ids and unique pins. */
    private fun sanitized(): HomeLayout = HomeLayout(
        sections = sections.distinctBy { it.id },
        pinned = pinned.distinct(),
        listeningOffered = listeningOffered,
    )
}
