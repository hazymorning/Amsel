package io.github.hazymorning.amsel.widget

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WidgetStoreTest {
    private val dir: File = Files.createTempDirectory("widget").toFile()
    private val store = WidgetStore(dir)
    private val album = WidgetItem("library://album/1", "Blue Hour", "sample:album/1")

    @Test fun aNewWidgetHasTheDefaults() = runTest {
        assertEquals(DefaultSlots, store.slots(7))
    }

    @Test fun eachWidgetKeepsItsOwnButtons() = runTest {
        val chosen = listOf(
            WidgetSlot(WidgetButton.Quieter),
            WidgetSlot(WidgetButton.Louder),
            WidgetSlot(WidgetButton.ContinueListening),
            WidgetSlot(WidgetButton.PlayItem, album),
        )
        store.setSlots(7, chosen)
        assertEquals(chosen, WidgetStore(dir).slots(7))
        assertEquals(DefaultSlots, store.slots(8))
    }

    @Test fun aRemovedWidgetIsForgottenWithItsCovers() = runTest {
        store.setSlots(7, DefaultSlots.take(3) + WidgetSlot(WidgetButton.PlayItem, album))
        store.setThumb(album.art.orEmpty(), Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))
        store.forget(listOf(7))
        assertEquals(DefaultSlots, store.slots(7))
        assertNull(store.thumb(album.art.orEmpty()))
    }

    @Test fun aButtonALaterAmselDroppedTakesTheDefaultsPlace() = runTest {
        File(dir, "setups.json").writeText(
            """[{"id":3,"slots":[{"button":"gone"},{"button":"next"},{"button":"item"},{"button":"louder"}]}]""",
        )
        assertEquals(
            listOf(DefaultSlots[0], WidgetSlot(WidgetButton.Next), DefaultSlots[2], WidgetSlot(WidgetButton.Louder)),
            store.slots(3),
        )
    }

    @Test fun aBrokenFileStartsOver() = runTest {
        File(dir, "setups.json").writeText("{ not json")
        assertEquals(DefaultSlots, store.slots(3))
    }

    @Test fun theLookAndItsCoverOutlastTheRun() = runTest {
        val look = WidgetLook(speaker = "Kitchen", title = "Lanterns", playing = true)
        store.setLook(look)
        store.setCover(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) })
        val again = WidgetStore(dir)
        assertEquals(look, again.look())
        assertNotNull(again.cover())
        again.forgetLook()
        assertNull(again.look())
        assertNull(again.cover())
    }
}
