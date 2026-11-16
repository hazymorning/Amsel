package io.github.hazymorning.amsel.sample

import io.github.hazymorning.amsel.data.home.HomeLayout
import io.github.hazymorning.amsel.data.home.SectionLayout
import io.github.hazymorning.amsel.data.home.SectionOption
import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.model.SpokenWord
import io.github.hazymorning.amsel.ui.screens.home.HomeUiState
import io.github.hazymorning.amsel.ui.screens.home.SectionUi

object HomeSample {
    private fun playlist(n: Int, title: String, owner: String) =
        MediaUi("library://playlist/$n", MediaKind.Playlist, title, owner, ArtRef("sample:playlist/$n"))

    private fun station(n: Int, title: String) =
        MediaUi("library://radio/$n", MediaKind.Radio, title, art = ArtRef("sample:radio/$n"))

    /** The person's playlists with their owners, as the server sends them. */
    private val playlists = SampleData.playlists.mapIndexed { i, playlist ->
        playlist.copy(subtitle = if (i == 2) "Jonas" else "Anna")
    }

    /** Music Assistant's own playlists. */
    val smartPlaylists = listOf(
        playlist(20, "All favourited tracks", "Music Assistant"),
        playlist(21, "Recently added tracks", "Music Assistant"),
        playlist(22, "Random Album (from library)", "Music Assistant"),
        playlist(23, "500 Random tracks (from library)", "Music Assistant"),
        playlist(24, "Infinite Mix (favorites)", "Music Assistant"),
    )

    val stations = listOf(
        station(1, "Radio Paradise"),
        station(2, "FIP"),
        station(3, "BBC Radio 6 Music"),
        station(4, "KEXP"),
    )

    /** Albums newest first. */
    val recentlyAdded = SampleData.albums.reversed()

    private fun section(
        id: String,
        option: SectionOption,
        layout: SectionLayout,
        items: List<MediaUi>,
        more: Boolean = false,
    ) = SectionUi(id, option, layout, items, more = more)

    private val played = listOf(
        SampleData.albums[0],
        playlists[0],
        SampleData.artists[1],
        SampleData.albums[2],
        playlists[1],
        SampleData.albums[3],
    )
    private val recent = section("s2", SectionOption.Everything, SectionLayout.Row, played, more = true)
    private val smart = section("s3", SectionOption.SmartPlaylists, SectionLayout.Row, smartPlaylists, more = true)
    private val newAlbums = section("s4", SectionOption.NewAlbums, SectionLayout.Row, recentlyAdded, more = true)
    private val noPins = section("s1", SectionOption.Pinned, SectionLayout.Grid, emptyList())
    private val pins = section(
        "s1",
        SectionOption.Pinned,
        SectionLayout.Grid,
        listOf(playlists[0], SampleData.albums[2], stations[0], SampleData.artists[1]) +
            SampleData.albums.take(2),
    )

    /** A new person's Home: Pinned (hidden until the first pin), then three rows. */
    val home = HomeUiState(listOf(noPins, recent, smart, newAlbums))

    /** A household that listens to books and podcasts: Continue listening opens Home. */
    val listening = HomeUiState(
        listOf(
            section("s0", SectionOption.ContinueListening, SectionLayout.Big, SpokenSample.continueListening),
            noPins,
            recent,
            smart,
        ),
        spoken = SpokenWord(books = true, podcasts = true),
    )

    val grid = HomeUiState(listOf(pins, recent, smart))

    /** Home as a new person finds it ([HomeLayout.DEFAULT_SECTIONS]), with music to show. */
    val asItComes = HomeUiState(
        HomeLayout.DEFAULT_SECTIONS.map { section ->
            val items = when (section.option) {
                SectionOption.ContinueListening -> SpokenSample.continueListening
                SectionOption.Everything -> played
                SectionOption.YourPlaylists -> playlists
                SectionOption.NewAlbums -> recentlyAdded
                SectionOption.TopArtists -> SampleData.artists
                SectionOption.SmartPlaylists -> smartPlaylists
                SectionOption.AllStations -> stations
                else -> emptyList()
            }
            SectionUi(section.id, section.option, section.layout, items, more = true, titled = section.showsTitle)
        },
        spoken = SpokenWord(books = true, podcasts = true),
    )

    /** What last played on the speaker in use, named after the title. */
    val playedHere = HomeUiState(
        listOf(
            noPins,
            recent.copy(option = SectionOption.PlayedHere, heardOn = SpeakerUi("living-room", "Wohnzimmer")),
            smart,
            newAlbums,
        ),
    )

    /** The other layouts: big covers, small covers of every kind, and a list. */
    val layouts = HomeUiState(
        listOf(
            section("s5", SectionOption.NewAlbums, SectionLayout.Big, recentlyAdded, more = true),
            section("s6", SectionOption.Everything, SectionLayout.Small, played + stations, more = true),
            section(
                "s7",
                SectionOption.Pinned,
                SectionLayout.List,
                listOf(stations[0], playlists[0], SampleData.artists[1]),
            ),
        ),
    )

    val editing = HomeUiState(listOf(pins, recent, smart, newAlbums), editing = true)

    val empty = HomeUiState(
        home.sections.map { it.copy(items = emptyList(), more = false) },
        libraryEmpty = true,
    )

    /** Only Pinned, with nothing pinned, after Done: the library has music, Home has none of it. */
    val nothingShown = HomeUiState(listOf(noPins), libraryEmpty = false)

    val noSections = HomeUiState()

    val offline = empty.copy(offline = true)
}
