package io.github.hazymorning.amsel.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class WordsTest {
    @Test fun aFullStopGoesWithTheCutSoThereAreNeverFourDots() {
        assertEquals("Gehört · 30. Sept. 2025 · 1 Std…", "Gehört · 30. Sept. 2025 · 1 Std. ".cutShort())
        assertEquals("Noch 34 Min…", "Noch 34 Min.".cutShort())
    }

    @Test fun aSeparatorACommaAndAColonGoWithTheCut() {
        assertEquals("Download wartet…", "Download wartet · ".cutShort())
        assertEquals("Field Recordings…", "Field Recordings, ".cutShort())
        assertEquals("Bach…", "Bach – ".cutShort())
        assertEquals("Kapitel 3…", "Kapitel 3: ".cutShort())
    }

    @Test fun aWordKeepsItsLetters() {
        assertEquals("Lanterns…", "Lanterns".cutShort())
    }
}
