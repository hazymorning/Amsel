package io.github.hazymorning.amsel.data.home

import io.github.hazymorning.amsel.data.home.SectionLayout.Big
import io.github.hazymorning.amsel.data.home.SectionLayout.Grid
import io.github.hazymorning.amsel.data.home.SectionLayout.List
import io.github.hazymorning.amsel.data.home.SectionLayout.Row
import io.github.hazymorning.amsel.data.home.SectionLayout.Small
import io.github.hazymorning.amsel.data.home.SectionOption.AllStations
import io.github.hazymorning.amsel.data.home.SectionOption.ContinueListening
import io.github.hazymorning.amsel.data.home.SectionOption.Everything
import io.github.hazymorning.amsel.data.home.SectionOption.FavoriteStations
import io.github.hazymorning.amsel.data.home.SectionOption.NewAlbums
import io.github.hazymorning.amsel.data.home.SectionOption.Pinned
import io.github.hazymorning.amsel.data.home.SectionOption.Search
import io.github.hazymorning.amsel.data.home.SectionOption.SmartPlaylists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {
    /** The four sections every edit below works on: an earlier default without Continue listening. */
    private val home = HomeLayout(
        listOf(
            HomeSection("s1", Pinned, Grid),
            HomeSection("s2", Everything, Row),
            HomeSection("s3", SmartPlaylists, Row),
            HomeSection("s4", NewAlbums, Row),
        ),
    )

    private fun HomeLayout.options() = sections.map { it.option }

    private fun HomeLayout.ids() = sections.map { it.id }

    @Test fun aNewPersonGetsSearchThenContinueListeningPinnedAndRows() {
        val fresh = HomeLayout()
        assertEquals(
            listOf(
                Search,
                ContinueListening,
                Pinned,
                Everything,
                SmartPlaylists,
                NewAlbums,
                AllStations,
            ),
            fresh.options(),
        )
        assertEquals(listOf(Row, Big, Grid, Row, Row, Row, Small), fresh.sections.map { it.layout })
        // An earlier default's sections keep their ids, so their snapshots still paint.
        assertEquals(listOf("s5", "s0", "s1", "s2", "s3", "s4", "s7"), fresh.ids())
        assertTrue(fresh.pinned.isEmpty())
        assertSame(fresh, fresh.offerListening())
    }

    @Test fun theEarlierDefaultsFavouriteStationsReadAsEveryStation() {
        val earlier = HomeLayout(
            HomeLayout.DEFAULT_SECTIONS.map { if (it.id == "s7") it.copy(option = FavoriteStations) else it },
        )
        assertEquals(AllStations, HomeLayout.decode(earlier.encode()).sections.last().option)
        // In another size or under the person's own name, favourites stay favourites.
        val resized = earlier.setLayout("s7", Row)
        assertEquals(FavoriteStations, HomeLayout.decode(resized.encode()).sections.last().option)
        val named = earlier.setTitle("s7", "Sender")
        assertEquals(FavoriteStations, HomeLayout.decode(named.encode()).sections.last().option)
    }

    @Test fun aResetBringsBackANewPersonsSectionsAndKeepsThePins() {
        val mine = home.pin("library://album/9").remove("s2")
        val reset = mine.reset()
        assertEquals(HomeLayout().sections, reset.sections)
        assertEquals(listOf("library://album/9"), reset.pinned)
    }

    @Test fun aHomeMadeBeforeContinueListeningGetsItOnceAtTheTop() {
        val stored = HomeLayout.decode(home.encode().replace("\"listeningOffered\":true", "\"listeningOffered\":false"))
        assertFalse(stored.listeningOffered)
        val offered = stored.offerListening()
        assertEquals(listOf(ContinueListening, Pinned, Everything, SmartPlaylists, NewAlbums), offered.options())
        assertEquals(Big, offered.sections.first().layout)
        assertTrue(offered.listeningOffered)
        // Removed, it stays away.
        val removed = offered.remove(offered.sections.first().id)
        assertSame(removed, removed.offerListening())
        // An old stored layout never had the flag: it reads as not offered yet.
        assertFalse(HomeLayout.decode("""{"sections": [], "pinned": []}""").listeningOffered)
    }

    @Test fun continueListeningIsOfferedToAHomeOfAnyLength() {
        val long = generateSequence(home) { it.add() }.elementAt(8).copy(listeningOffered = false)
        val offered = long.offerListening()
        assertEquals(ContinueListening, offered.sections.first().option)
        assertEquals(long.sections, offered.sections.drop(1))
        assertTrue(offered.listeningOffered)
    }

    @Test fun everyOptionHasItsSource() {
        assertEquals(SectionSource.RecentlyPlayed, ContinueListening.source)
        assertEquals(SectionSource.Playlists, SmartPlaylists.source)
        assertEquals(SectionSource.RecentlyPlayed, Everything.source)
    }

    @Test fun addingOffersSomethingNotOnHomeYetAsARow() {
        val added = home.add()
        val section = added.sections.last()
        assertEquals(SectionOption.YourPlaylists, section.option)
        assertEquals(Row, section.layout)
        assertEquals("s5", section.id)
        assertEquals(SectionOption.FavoriteAlbums, added.add().sections.last().option)
    }

    @Test fun homeTakesAsManySectionsAsThePersonAdds() {
        val long = generateSequence(home) { it.add() }.elementAt(12)
        assertEquals(home.sections.size + 12, long.sections.size)
        assertEquals(long.ids().distinct(), long.ids())
        // Every suggestion is on Home by then: the first of them comes again.
        assertEquals(SectionOption.Everything, long.sections.last().option)
    }

    @Test fun newIdsNeverRepeatOneInUse() {
        val gap = home.remove("s2").add()
        assertEquals(listOf("s1", "s3", "s4", "s2"), gap.ids())
    }

    @Test fun removeTakesOnlyThatSection() {
        assertEquals(listOf("s1", "s3", "s4"), home.remove("s2").ids())
        assertSame(home.pinned, home.remove("s1").pinned)
        assertEquals(home, home.remove("nope"))
    }

    @Test fun moveReordersAndClampsToTheEnds() {
        assertEquals(listOf("s1", "s3", "s4", "s2"), home.move("s2", 3).ids())
        assertEquals(listOf("s4", "s1", "s2", "s3"), home.move("s4", -5).ids())
        assertEquals(listOf("s1", "s3", "s4", "s2"), home.move("s2", 99).ids())
        assertEquals(home, home.move("nope", 0))
    }

    @Test fun moveUpAndDownStepOnePlaceAndStopAtTheEnds() {
        assertEquals(listOf("s1", "s3", "s2", "s4"), home.moveDown("s2").ids())
        assertEquals(listOf("s2", "s1", "s3", "s4"), home.moveUp("s2").ids())
        assertEquals(home, home.moveUp("s1"))
        assertEquals(home, home.moveDown("s4"))
    }

    @Test fun choosingAnOptionKeepsTheLayout() {
        val changed = home.setOption("s3", SectionOption.TopArtists).sections[2]
        assertEquals(SectionOption.TopArtists, changed.option)
        assertEquals(Row, changed.layout)
    }

    @Test fun choosingALayoutKeepsTheOption() {
        val changed = home.setLayout("s2", Big).sections[1]
        assertEquals(Big, changed.layout)
        assertEquals(Everything, changed.option)
    }

    @Test fun everySectionButSearchShowsItsTitleUntilThePersonSaysOtherwise() {
        assertTrue(home.sections.all { it.showsTitle })
        val search = home.setOption("s3", Search)
        assertFalse(search.sections[2].showsTitle)
        assertTrue(search.setTitled("s3", true).sections[2].showsTitle)
        assertFalse(home.setTitled("s2", false).sections[1].showsTitle)
        // Another option brings its own way: Search hides its title, the rest show theirs.
        assertTrue(home.setTitled("s2", false).setOption("s2", SectionOption.TopArtists).sections[1].showsTitle)
    }

    @Test fun aHiddenTitleIsKeptAndAHomeStoredBeforeItStillReads() {
        val hidden = home.setTitled("s2", false).setOption("s3", Search).setTitled("s3", true)
        assertEquals(hidden, HomeLayout.decode(hidden.encode()))
        val older = """{"sections": [{"id": "s1", "option": "Search", "layout": "Row"},
            {"id": "s2", "option": "NewAlbums", "layout": "Row"}]}"""
        assertEquals(listOf(false, true), HomeLayout.decode(older).sections.map { it.showsTitle })
    }

    @Test fun aHomeStoredWithTheSpeakersRowReadsWithoutIt() {
        val older = """{"sections": [{"id": "s5", "option": "Search", "layout": "Row"},
            {"id": "s6", "option": "Speakers", "layout": "Row"},
            {"id": "s2", "option": "Everything", "layout": "Row"}], "pinned": ["library://album/1"]}"""
        val read = HomeLayout.decode(older)
        assertEquals(listOf("s5", "s2"), read.ids())
        assertEquals(listOf("library://album/1"), read.pinned)
    }

    @Test fun pinsGoFirstAndOnlyOnce() {
        val pinned = home.pin("library://album/1").pin("library://playlist/2").pin("library://album/1")
        assertEquals(listOf("library://album/1", "library://playlist/2"), pinned.pinned)
        assertTrue(pinned.isPinned("library://playlist/2"))
        assertEquals(home.sections, pinned.sections)
    }

    @Test fun arrangingPinsKeepsThoseHomeCouldNotShowInPlace() {
        val pins = home.pin("d").pin("c").pin("b").pin("a")
        // "b" did not load, so Home showed a, c, d; the person moved d to the top.
        val arranged = pins.arrangePins(listOf("d", "a", "c", "gone"))
        assertEquals(listOf("d", "b", "a", "c"), arranged.pinned)
        assertEquals(pins.sections, arranged.sections)
    }

    @Test fun unpinForgetsThePinButKeepsTheSection() {
        val unpinned = home.pin("library://album/1").unpin("library://album/1")
        assertTrue(unpinned.pinned.isEmpty())
        assertEquals(Pinned, unpinned.sections.first().option)
    }

    @Test fun theFirstPinBringsBackAPinnedGridAtTheTop() {
        val withoutPins = home.remove("s1")
        val pinned = withoutPins.pin("library://album/1")
        assertEquals(listOf(Pinned, Everything, SmartPlaylists, NewAlbums), pinned.options())
        assertEquals(Grid, pinned.sections.first().layout)
        assertEquals("s1", pinned.sections.first().id)
    }

    @Test fun aNewPinnedSectionTakesNoSectionAway() {
        val long = home.remove("s1").add().add().add()
        val pinned = long.pin("library://album/1")
        assertEquals(Pinned, pinned.sections.first().option)
        assertEquals(long.sections, pinned.sections.drop(1))
    }

    @Test fun aGridShowsWholeRowsButEveryPin() {
        assertEquals(listOf(1, 2, 3, 4, 4, 6, 6, 8, 8), (1..9).map { Grid.shown(it, Everything) })
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 8), (1..9).map { Grid.shown(it, Pinned) })
        assertEquals(12, Row.shown(13, Everything))
        assertEquals(8, Big.shown(13, Everything))
        assertEquals(5, List.shown(13, Pinned))
        assertEquals(3, List.shown(3, Everything))
        assertEquals(0, Row.shown(0, Everything))
    }

    @Test fun storedLayoutsReadBackTheSame() {
        val edited = home.add().setLayout("s3", List).move("s4", 0).pin("library://radio/1")
        assertEquals(edited, HomeLayout.decode(edited.encode()))
    }

    @Test fun nothingStoredMeansTheDefaults() {
        assertEquals(HomeLayout(), HomeLayout.decode(null))
        assertEquals(HomeLayout(), HomeLayout.decode(""))
        assertEquals(HomeLayout(), HomeLayout.decode("not json"))
        assertEquals(HomeLayout(), HomeLayout.decode("[1, 2]"))
        assertEquals(HomeLayout(), HomeLayout.decode("""{"pinned": []}"""))
    }

    @Test fun anEmptiedHomeStaysEmpty() {
        val empty = HomeLayout.decode("""{"sections": [], "pinned": []}""")
        assertTrue(empty.sections.isEmpty())
    }

    @Test fun anUnknownSectionIsDroppedNotTheWholeHome() {
        val json = """
            {"sections": [
                {"id": "s1", "source": "Albums", "option": "NewAlbums", "layout": "Grid"},
                {"id": "s2", "source": "Genres", "option": "NewGenres", "layout": "Row"},
                {"id": "s3", "source": "Radio", "option": "AllStations", "layout": "Carousel"}
            ], "pinned": ["library://album/1", "library://album/1"]}
        """.trimIndent()
        val layout = HomeLayout.decode(json)
        assertEquals(listOf("s1"), layout.ids())
        assertEquals(listOf("library://album/1"), layout.pinned)
    }

    @Test fun aLayoutStoredWithItsSourcesStillReads() {
        val json = """{"sections": [{"id": "s1", "source": "Radio", "option": "TopArtists", "layout": "List"}]}"""
        assertEquals(SectionOption.TopArtists, HomeLayout.decode(json).sections.single().option)
    }

    @Test fun aFavoriteSectionStoredUnderItsFirstNameStillReads() {
        val json = """{"sections": [{"id": "s1", "option": "FavouriteAlbums", "layout": "Row"}]}"""
        assertEquals(SectionOption.FavoriteAlbums, HomeLayout.decode(json).sections.single().option)
    }

    @Test fun aLongStoredHomeReadsWholeWithEachIdOnce() {
        val many = (1..10).joinToString(",") {
            """{"id": "s${minOf(it, 9)}", "option": "NewAlbums", "source": "Albums", "layout": "Row"}"""
        }
        val layout = HomeLayout.decode("""{"sections": [$many]}""")
        assertEquals((1..9).map { "s$it" }, layout.ids())
    }
}
