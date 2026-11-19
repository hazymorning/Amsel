package io.github.hazymorning.amsel.widget

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WidgetStoreTest {
    private val dir: File = Files.createTempDirectory("widget").toFile()
    private val store = WidgetStore(dir)
    private val album = WidgetItem("library://album/1", "Blue Hour", "sample:album/1")

    @Test fun aNewWidgetHasNoSetupYet() = runTest {
        assertNull(store.setup(7))
    }

    @Test fun eachWidgetKeepsItsOwnSetup() = runTest {
        val chosen = WidgetSetup(
            style = WidgetStyle.Clear,
            speaker = "kitchen",
            slots = listOf(WidgetSlot(WidgetButton.Quieter), WidgetSlot(WidgetButton.PlayItem, album)),
            cells = WidgetCells.None,
        )
        store.setSetup(7, chosen)
        store.setSetup(8, WidgetSetup(slots = emptyList()))
        assertEquals(chosen, WidgetStore(dir).setup(7))
        assertEquals(WidgetSetup(slots = emptyList()), WidgetStore(dir).setup(8))
    }

    @Test fun aRemovedWidgetIsForgotten() = runTest {
        store.setSetup(7, WidgetSetup())
        store.forget(listOf(7))
        assertNull(store.setup(7))
    }

    /** As Amsel 2026.11.50 kept them: four buttons, no look, no speaker. */
    @Test fun aButtonALaterAmselDroppedIsLeftOut() = runTest {
        File(dir, "setups.json").writeText(
            """[{"id":3,"slots":[{"button":"gone"},{"button":"next"},{"button":"item"},{"button":"louder"}]}]""",
        )
        assertEquals(
            WidgetSetup(slots = listOf(WidgetSlot(WidgetButton.Next), WidgetSlot(WidgetButton.Louder))),
            store.setup(3),
        )
    }

    /** Saved as they came before, untouched: the buttons come as they come now; chosen ones stay. */
    @Test fun theEarlierButtonsAsTheyCameComeAsNow() = runTest {
        File(dir, "setups.json").writeText(
            """[{"id":3,"slots":[{"button":"previous"},{"button":"play"},{"button":"next"},""" +
                """{"button":"favourite"},{"button":"shuffle"}]},""" +
                """{"id":4,"slots":[{"button":"previous"},{"button":"play"},{"button":"next"}]}]""",
        )
        assertEquals(DefaultSlots, store.setup(3)?.slots)
        assertEquals(
            listOf(WidgetButton.Previous, WidgetButton.PlayPause, WidgetButton.Next),
            store.setup(4)?.slots?.map { it.button },
        )
    }

    @Test fun aBrokenFileStartsOver() = runTest {
        File(dir, "setups.json").writeText("{ not json")
        assertNull(store.setup(3))
    }

    @Test fun theLooksOutlastTheRun() = runTest {
        val looks = mapOf(
            WidgetStore.AMSELS to WidgetLook(speaker = "Kitchen", title = "Lanterns", playing = true),
            "bedroom" to WidgetLook(speakerId = "bedroom", speaker = "Bedroom"),
        )
        store.setLooks(looks)
        val again = WidgetStore(dir)
        assertEquals(looks, again.looks())
        again.forgetLooks()
        assertEquals(emptyMap<String, WidgetLook>(), again.looks())
    }

    /** Amsel 2026.11.50 kept one look and its pictures: the look is read once, the pictures go. */
    @Test fun theEarlierLookIsReadAndItsPicturesGo() = runTest {
        File(dir, "look.json").writeText("""{"speaker":"Kitchen","title":"Lanterns"}""")
        File(dir, "cover.png").writeText("png")
        File(dir, "thumbs").mkdirs()
        assertEquals("Lanterns", store.looks()[WidgetStore.AMSELS]?.title)
        store.setLooks(store.looks())
        assertFalse(File(dir, "cover.png").exists())
        assertFalse(File(dir, "thumbs").exists())
    }
}
