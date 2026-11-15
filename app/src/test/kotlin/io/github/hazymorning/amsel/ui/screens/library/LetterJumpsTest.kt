package io.github.hazymorning.amsel.ui.screens.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LetterJumpsTest {
    @Test fun shortListsGetNoScrubber() {
        assertEquals(emptyList<LetterJump>(), letterJumps(List(SCRUBBER_MIN_ITEMS - 1) { "Name $it" }))
    }

    @Test fun firstIndexOfEachLetterInListOrder() {
        val names = listOf("4AM Radio", "Ada", "Anouk", "Basil", "Élodie", "Eve", "zadie") + List(30) { "Zed $it" }
        val jumps = letterJumps(names)
        assertEquals(
            listOf(LetterJump("#", 0), LetterJump("A", 1), LetterJump("B", 3), LetterJump("E", 4), LetterJump("Z", 6)),
            jumps,
        )
    }

    @Test fun oneLetterIsNoIndex() {
        assertEquals(emptyList<LetterJump>(), letterJumps(List(40) { "Anna $it" }))
    }

    @Test fun lettersFoldAccentsAndCase() {
        assertEquals("E", indexLetter("  élan"))
        assertEquals("Ø", indexLetter("Ølstykke"))
        assertEquals("#", indexLetter(""))
        assertEquals("#", indexLetter("(What's the Story)"))
    }
}
