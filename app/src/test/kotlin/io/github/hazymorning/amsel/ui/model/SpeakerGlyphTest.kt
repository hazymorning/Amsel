package io.github.hazymorning.amsel.ui.model

import io.github.hazymorning.amsel.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** A speaker others play along with is still a speaker: the link glyph is Group's alone. */
class SpeakerGlyphTest {
    @Test fun aGroupKeepsTheSpeakersGlyph() {
        assertEquals(R.drawable.ic_speaker, SpeakerUi("kitchen", "Kitchen", others = 2).glyph)
        assertEquals(R.drawable.ic_speaker, SpeakerUi("kitchen", "Kitchen").glyph)
    }

    @Test fun thisPhoneKeepsThePhones() {
        assertEquals(R.drawable.ic_phone, SpeakerUi("phone", "This phone", isPhone = true).glyph)
    }
}
