package io.github.hazymorning.amsel.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TypeCoverTest {
    @Test fun theMonogramIsTheFirstLetterOrFigureAsACapital() {
        assertEquals("F", monogramOf("Flitz&Suppe"))
        assertEquals("4", monogramOf("4AM Radio"))
        assertEquals("É", monogramOf("élodie"))
        assertEquals("K", monogramOf("  (kestrel & finch)"))
    }

    @Test fun aLetterWhoseCapitalIsTwoStaysOne() {
        assertEquals("S", monogramOf("ßtraße"))
    }

    @Test fun aNameWithoutLettersOrFiguresHasNoMonogram() {
        assertNull(monogramOf(""))
        assertNull(monogramOf("  "))
        assertNull(monogramOf("!!!"))
    }
}
