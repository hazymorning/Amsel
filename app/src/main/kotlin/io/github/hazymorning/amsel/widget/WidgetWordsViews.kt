package io.github.hazymorning.amsel.widget

import android.content.Context
import android.widget.RemoteViews
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.model.DOT_SEPARATOR

/** A widget's words: the speaker, the title and the line under it, each line's first ink on the frame. */
internal class WidgetWordsViews(
    private val context: Context,
    private val text: WidgetText,
    private val look: WidgetLook,
    private val slots: List<WidgetSlot>,
    private val tone: Tone,
    private val px: (Float) -> Int,
) {
    /**
     * [words] of what plays in [width] dp, their first ink on the widget's frame: the words' view
     * stands [LEAD] dp outside it (the layouts' widget_words_lead_out), and each line moves back in by
     * as much as its own first letter or glyph leaves. A [centred] view's ink, not its box, stands in
     * the middle of the row; otherwise its capitals meet the frame at the top.
     */
    fun draw(views: RemoteViews, words: WidgetWords, centred: Boolean, width: Float) = with(views) {
        shows(R.id.widget_words, words != WidgetWords.None)
        if (words == WidgetWords.None) return@with
        val playing = look.signedIn && look.title != null
        val name = look.speaker.takeIf { look.signedIn }?.ifBlank { null } ?: context.getString(R.string.app_name)
        val title = when {
            !look.signedIn -> context.getString(R.string.widget_sign_in)
            else -> look.title ?: context.getString(R.string.player_not_playing)
        }
        val subtitle = (look.failure ?: look.subtitle).takeIf { playing && words.subtitle }
        val glyph = look.signedIn
        val (glyphStart, glyphEnd) = text.speakerSides()
        val nameStart = text.sideBearing(name, WidgetLine.Speaker)
        val afterGlyph = if (glyph) text.dp(WidgetLine.Speaker.size) * GLYPH_GAP - glyphEnd - nameStart else 0f
        val glyphRoom = if (glyph) text.speakerGlyph - glyphStart + afterGlyph else 0f
        // The level shows whole or not at all: a long name takes its room before it is cut itself.
        val level = level()?.takeIf {
            words.speaker &&
                glyphRoom + text.width(name, WidgetLine.Speaker) + text.width(it, WidgetLine.Speaker) <= width
        }
        setTextViewText(R.id.widget_speaker, name)
        setTextViewText(R.id.widget_level, level.orEmpty())
        setTextViewText(R.id.widget_title, title)
        setTextViewText(R.id.widget_subtitle, subtitle.orEmpty())
        setInt(R.id.widget_title, "setMaxLines", words.titleLines)
        shows(R.id.widget_speaker_row, words.speaker)
        shows(R.id.widget_level, level != null)
        shows(R.id.widget_subtitle, subtitle != null)
        textColor(context, R.id.widget_speaker, tone.words)
        textColor(context, R.id.widget_level, tone.quiet)
        textColor(context, R.id.widget_title, tone.words)
        textColor(context, R.id.widget_subtitle, tone.quiet)

        shows(R.id.widget_speaker_glyph, glyph)
        setImageViewResource(R.id.widget_speaker_glyph, text.speakerCut)
        glyphColor(context, R.id.widget_speaker_glyph, tone.glyph)
        val speakerStart = if (glyph) glyphStart else nameStart
        val titleStart = text.sideBearing(title, WidgetLine.Title)
        val subtitleStart = subtitle?.let { text.sideBearing(it, WidgetLine.Subtitle) } ?: 0f
        val inkTop = text.wordsTop(words)
        if (centred) {
            // Half of the difference of a box's top and bottom padding moves what it holds.
            val shift = text.wordsBox(words) / 2 - (inkTop + text.wordsBaseline(words)) / 2
            setViewPadding(R.id.widget_words, 0, px(2 * shift), 0, px(-2 * shift))
        } else {
            setViewPadding(R.id.widget_words, 0, px(LEAD - inkTop), 0, 0)
        }
        setViewPadding(R.id.widget_speaker_row, px(LEAD - speakerStart), 0, 0, 0)
        setViewPadding(R.id.widget_speaker, px(afterGlyph), 0, 0, 0)
        val titleTop = if (words.speaker) WidgetSizes.SPEAKER_TO_TITLE else 0f
        setViewPadding(R.id.widget_title, px(LEAD - titleStart), px(titleTop), 0, 0)
        setViewPadding(R.id.widget_subtitle, px(LEAD - subtitleStart), px(WidgetSizes.TITLE_TO_SUBTITLE), 0, 0)
    }

    /** The speaker's level after its name, where Quieter or Louder is among the buttons. */
    private fun level(): String? {
        val volumeButton = slots.any { it.button == WidgetButton.Quieter || it.button == WidgetButton.Louder }
        val level = look.volume?.takeIf { volumeButton && look.signedIn } ?: return null
        return DOT_SEPARATOR + context.getString(R.string.player_volume_percent, level)
    }

    private companion object {
        /** From the speaker's glyph's ink to its name's, in the name's size. */
        const val GLYPH_GAP = 0.45f

        /** How far the words' view stands outside the frame, top and start (res/values/widget.xml). */
        const val LEAD = 6f
    }
}
