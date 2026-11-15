package io.github.hazymorning.amsel.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TypeCoverTest {
    @Test fun initialsAreTheFirstLettersOfTheFirstTwoWords() {
        assertEquals("RT", initialsOf("Road Trip 2026"))
        assertEquals("D", initialsOf("Dunmore"))
        assertEquals("AM", initialsOf("anouk meyer"))
        assertEquals("2M", initialsOf("2026 Mix"))
        assertEquals("KF", initialsOf("Kestrel & Finch"))
    }

    @Test fun aNameWithoutLettersHasNoInitials() {
        assertNull(initialsOf(""))
        assertNull(initialsOf("  "))
        assertNull(initialsOf("!!! ..."))
    }
}
