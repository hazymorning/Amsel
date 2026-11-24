package io.github.hazymorning.amsel.widget

import android.content.Context
import android.text.format.DateUtils
import android.widget.RemoteViews
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.widget.WidgetSizes.LEAD

/** Top: the cap top at the box's top; Bottom: the last baseline at its bottom; Middle: the visible extent centred. */
internal enum class WordsAlign { Top, Bottom, Middle }

internal class WidgetWordsViews(
    private val context: Context,
    private val type: WidgetTextMetrics,
    private val look: WidgetContent,
    private val tone: WidgetColors,
    private val px: (Float) -> Int,
) {
    /**
     * The view stands LEAD outside its box on both sides; each line is padded back by LEAD less its first
     * glyph's side bearing, so visible edges meet the box. The box reaches LEAD past the frame on its
     * aligned side (WidgetDrawing), so the cap top or the last baseline meets the frame.
     */
    fun views(words: WidgetWords, size: WordSize, align: WordsAlign, width: Float): RemoteViews? {
        if (words == WidgetWords.None) return null
        val text = type.sized(size)
        val views = RemoteViews(context.packageName, size.layout)
        val playing = look.signedIn && look.title != null
        val name = look.speaker.takeIf { look.signedIn }?.ifBlank { null } ?: context.getString(R.string.app_name)
        val title = when {
            !look.signedIn -> context.getString(R.string.widget_sign_in)
            else -> look.title ?: context.getString(R.string.player_not_playing)
        }
        val subtitle = (look.failure ?: look.subtitle).takeIf { playing && words.subtitle }
        val time = playing && words.time && look.hasTime
        val (glyphStart, glyphEnd) = text.speakerSides()
        val nameStart = text.sideBearing(name, WidgetLine.Speaker)
        val glyphGap = text.speakerGlyph * GLYPH_GAP - glyphEnd - nameStart
        // The level stays whole: when the row is short, the speaker glyph hides first, then the name is cut.
        val level = level()?.takeIf { words.speaker }
        val nameRoom = text.speakerGlyph - glyphStart + glyphGap + text.width(name, WidgetLine.Speaker)
        val glyph = look.signedIn && (level == null || nameRoom + levelRoom(text, level) <= width)
        val afterGlyph = if (glyph) glyphGap else 0f
        with(views) {
            setTextViewText(R.id.widget_speaker, name)
            setTextViewText(R.id.widget_level, level.orEmpty())
            setTextViewText(R.id.widget_title, title)
            setTextViewText(R.id.widget_subtitle, subtitle.orEmpty())
            setInt(R.id.widget_title, "setMaxLines", words.titleLines)
            shows(R.id.widget_speaker_row, words.speaker)
            shows(R.id.widget_level, level != null)
            shows(R.id.widget_level_glyph, level != null)
            shows(R.id.widget_subtitle, subtitle != null)
            shows(R.id.widget_time, time && look.clockMs != null)
            shows(R.id.widget_time_still, time && look.clockMs == null)
            if (time) time(this)
            textColor(context, R.id.widget_time, tone.quiet)
            textColor(context, R.id.widget_time_still, tone.quiet)
            textColor(context, R.id.widget_speaker, tone.words)
            textColor(context, R.id.widget_level, tone.words)
            textColor(context, R.id.widget_title, tone.words)
            textColor(context, R.id.widget_subtitle, tone.quiet)
            shows(R.id.widget_speaker_glyph, glyph)
            setImageViewResource(R.id.widget_speaker_glyph, text.speakerCut)
            glyphColor(context, R.id.widget_speaker_glyph, tone.glyph)
            setImageViewResource(R.id.widget_level_glyph, text.levelCut)
            glyphColor(context, R.id.widget_level_glyph, tone.glyph)

            val inkTop = text.wordsTop(words)
            val lines = text.lines(title, WidgetLine.Title, width, words.titleLines)
            val (box, baseline) = text.wordsStack(words.speaker, lines, listOf(subtitle != null, time).count { it })
            when (align) {
                WordsAlign.Top -> setViewPadding(R.id.widget_words, 0, px(LEAD - inkTop), 0, 0)

                // The top too: a poster's cover stands above the text.
                WordsAlign.Bottom -> {
                    setViewPadding(R.id.widget_words, 0, px(LEAD - inkTop), 0, px(LEAD - (box - baseline)))
                }

                // Unequal padding moves the content by half the difference; px() drops the negative side.
                WordsAlign.Middle -> {
                    val shift = box / 2 - (inkTop + baseline) / 2
                    setViewPadding(R.id.widget_words, 0, px(2 * shift), 0, px(-2 * shift))
                }
            }
            val speakerStart = if (glyph) glyphStart else nameStart
            // When the row is full, the level's last glyph meets the frame at the end.
            val levelEnd = level?.let { text.endBearing(it, WidgetLine.Speaker) } ?: 0f
            setViewPadding(R.id.widget_speaker_row, px(LEAD - speakerStart), 0, px(LEAD - levelEnd), 0)
            setViewPadding(R.id.widget_speaker, px(afterGlyph), 0, 0, 0)
            setViewPadding(R.id.widget_level, px(level?.let { afterLevelGlyph(text, it) } ?: 0f), 0, 0, 0)
            val titleStart = text.sideBearing(title, WidgetLine.Title)
            val titleTop = if (words.speaker) size.speakerToTitle else 0f
            setViewPadding(R.id.widget_title, px(LEAD - titleStart), px(titleTop), px(LEAD), 0)
            val subtitleStart = subtitle?.let { text.sideBearing(it, WidgetLine.Subtitle) } ?: 0f
            val below = px(WidgetSizes.TITLE_TO_SUBTITLE)
            setViewPadding(R.id.widget_subtitle, px(LEAD - subtitleStart), below, px(LEAD), 0)
            val timeStart = text.sideBearing("0", WidgetLine.Subtitle)
            for (view in listOf(R.id.widget_time, R.id.widget_time_still)) {
                setViewPadding(view, px(LEAD - timeStart), below, px(LEAD), 0)
            }
        }
        return views
    }

    private fun time(views: RemoteViews) = with(views) {
        val format = if (look.spoken) {
            context.getString(R.string.widget_time_left, "%s")
        } else {
            "%s / " + DateUtils.formatElapsedTime(look.durationMs / SECOND_MS)
        }
        val clock = look.clockMs
        if (clock == null) {
            val rest = DateUtils.formatElapsedTime((look.restMs ?: 0) / SECOND_MS)
            setTextViewText(R.id.widget_time_still, format.replace("%s", rest))
            return@with
        }
        setChronometer(R.id.widget_time, clock, format, true)
        setChronometerCountDown(R.id.widget_time, look.spoken)
    }

    /** The same visible gap as between the speaker glyph and its name. */
    private fun afterLevelGlyph(text: WidgetTextMetrics, level: String): Float =
        text.speakerGlyph * GLYPH_GAP - text.levelSides().second - text.sideBearing(level, WidgetLine.Speaker)

    private fun levelRoom(text: WidgetTextMetrics, level: String): Float =
        text.dp(LEVEL_GAP * text.size.scale) + text.speakerGlyph + afterLevelGlyph(text, level) +
            text.width(level, WidgetLine.Speaker) - text.endBearing(level, WidgetLine.Speaker)

    private fun level(): String? {
        val volume = look.volume?.takeIf { look.signedIn } ?: return null
        return if (volume == 0) context.getString(R.string.player_volume_still) else volume.toString()
    }

    private companion object {
        const val SECOND_MS = 1000L

        /** The visible gap from the speaker glyph to its name, as a share of the text size. */
        const val GLYPH_GAP = 0.45f

        /** The level glyph's marginStart in widget_words.xml, in sp at Regular size. */
        const val LEVEL_GAP = 8f
    }
}

private val WordSize.layout: Int
    get() = when (this) {
        WordSize.Small -> R.layout.widget_words_small
        WordSize.Regular -> R.layout.widget_words
        WordSize.Large -> R.layout.widget_words_large
    }
