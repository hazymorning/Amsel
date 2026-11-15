package io.github.hazymorning.amsel.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Space
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Where a message's action stands: beside its sentence, or under it once the sentence would be squeezed. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MessageBarTest {
    @get:Rule val compose = createComposeRule()

    private fun show(sentence: String, action: String, fontScale: Float = 1f) = compose.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            AmselTheme { MessageBar(sentence, action = action) }
        }
    }

    /** A text's own node: its letters, not the touch target an action spreads around them. */
    private fun bounds(text: String): DpRect = compose.onNodeWithText(text, useUnmergedTree = true).getBoundsInRoot()

    @Test fun aShortActionStandsBesideTheSentenceOnItsLine() {
        show("Music Assistant ist nicht erreichbar", "Nochmal")
        val sentence = bounds("Music Assistant ist nicht erreichbar")
        val action = bounds("Nochmal")
        assertTrue("beside", action.left > sentence.right)
        assertTrue("on the sentence's height", action.top < sentence.bottom && action.bottom > sentence.top)
    }

    @Test fun anActionThatLeavesTheSentenceUnderHalfTheBarGoesUnderItAtTheEnd() {
        show("Music Assistant ist nicht erreichbar", "Später noch einmal versuchen")
        val sentence = bounds("Music Assistant ist nicht erreichbar")
        val action = bounds("Später noch einmal versuchen")
        assertTrue("under", action.top > sentence.bottom)
        assertEquals("at the end", (WIDTH - Keyline.Margin - Space.Sibling).value, action.right.value, BEARING)
    }

    @Test fun inLargeTypeAWordIsNotBrokenToKeepTheActionBeside() {
        show("Die Lautsprechergruppe reagiert nicht", "Nochmal", fontScale = 2f)
        val sentence = bounds("Die Lautsprechergruppe reagiert nicht")
        assertTrue("under", bounds("Nochmal").top > sentence.bottom)
        assertTrue("the sentence has the bar's width", sentence.right - sentence.left > WIDTH / 2)
    }

    @Test fun inLargeTypeAnActionStaysBesideWordsThatFit() {
        show("Die Wiedergabe ist fehlgeschlagen", "Nochmal", fontScale = 2f)
        val sentence = bounds("Die Wiedergabe ist fehlgeschlagen")
        assertTrue("beside", bounds("Nochmal").left > sentence.right)
    }

    @Test fun inLargeTypeALongSentenceTakesTheBarsWidthRatherThanTwoLinesMore() {
        val sentence = "Der Download konnte nicht gespeichert werden. Ist auf dem Handy noch Platz?"
        show(sentence, "Nochmal", fontScale = 2f)
        assertTrue("under", bounds("Nochmal").top > bounds(sentence).bottom)
    }

    @Test fun theActionStaysBesideOnlyWithHalfTheBarWholeWordsAndOneLineMore() {
        fun beside(room: Int = 250, longestWord: Int = 108, linesBeside: Int = 44) =
            actionBeside(bar = 363, room, longestWord, linesBeside, linesUnder = 22 + 24 + 11)
        assertTrue(beside())
        assertFalse("under half the bar", beside(room = 180))
        assertFalse("a word would break", beside(room = 185, longestWord = 216))
        assertFalse("two lines more", beside(linesBeside = 66))
    }

    private companion object {
        val WIDTH = 411.dp

        /** A text's box ends a letter's side bearing past its ink. */
        const val BEARING = 2f
    }
}
