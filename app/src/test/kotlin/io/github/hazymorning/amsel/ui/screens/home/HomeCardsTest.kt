package io.github.hazymorning.amsel.ui.screens.home

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCardsTest {
    @Test fun aNarrowPhoneKeepsTwoColumns() {
        assertEquals(2, cardColumns(PHONE_411, CAPTION))
        assertEquals(2, cardColumns(PHONE_360, CAPTION))
    }

    @Test fun largeTextTakesOneColumnSoATitleStaysWords() {
        assertEquals(1, cardColumns(PHONE_411, CAPTION * 2))
        assertEquals(1, cardColumns(PHONE_360, CAPTION * 2))
    }

    @Test fun aPhoneTooNarrowForNineLettersTakesOneColumn() {
        assertEquals(1, cardColumns(320.dp - MARGINS, CAPTION))
    }

    private companion object {
        val MARGINS = 48.dp
        val PHONE_411 = 411.dp - MARGINS
        val PHONE_360 = 360.dp - MARGINS

        // titleSmall's font size.
        val CAPTION = 14.dp
    }
}
