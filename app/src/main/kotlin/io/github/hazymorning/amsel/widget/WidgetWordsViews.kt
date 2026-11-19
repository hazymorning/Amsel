package io.github.hazymorning.amsel.widget

import android.content.Context
import android.text.format.DateUtils
import android.widget.RemoteViews
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.widget.WidgetSizes.LEAD

/** Where the words stand in their box: their first ink at its top, their last baseline at its foot, or their ink in its middle. */
internal enum class WordsAlign { Top, Bottom, Middle }

/** A widget's words: the speaker, the title and the line under it, each line's first ink on the frame. */
internal class WidgetWordsViews(
    private val context: Context,
    private val type: WidgetText,
    private val look: WidgetLook,
    private val tone: Tone,
    private val px: (Float) -> Int,
) {
    /**
     * [words] of what plays at [size], [width] dp wide, for a box whose start stands on the frame;
     * null for none. The view stands [LEAD] dp outside the box at either side, and each line moves
     * back in by as much as its first letter or glyph leaves, at the end the level by what its last
     * letter leaves. Aligned to the
     * [Top][WordsAlign.Top] or the [Bottom][WordsAlign.Bottom], the box reaches [LEAD] dp past the
     * frame on that side, and the capitals' top or the last baseline meets the frame; in the
     * [Middle][WordsAlign.Middle] their ink, not their box, stands there.
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
        // The level shows whole at the row's end; where the name beside it would be cut, the
        // speaker's glyph gives way first, then the name is cut.
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

                // The top too, for a poster's cover above them.
                WordsAlign.Bottom -> {
                    setViewPadding(R.id.widget_words, 0, px(LEAD - inkTop), 0, px(LEAD - (box - baseline)))
                }

                // Half of the difference of a box's top and bottom padding moves what it holds.
                WordsAlign.Middle -> {
                    val shift = box / 2 - (inkTop + baseline) / 2
                    setViewPadding(R.id.widget_words, 0, px(2 * shift), 0, px(-2 * shift))
                }
            }
            val speakerStart = if (glyph) glyphStart else nameStart
            // The level's number keeps its glyph as far as the name keeps the speaker's, and its last
            // ink stands on the frame at the end.
            val levelEnd = level?.let { text.endBearing(it, WidgetLine.Speaker) } ?: 0f
            setViewPadding(R.id.widget_speaker_row, px(LEAD - speakerStart), 0, px(LEAD - levelEnd), 0)
            setViewPadding(R.id.widget_speaker, px(afterGlyph), 0, 0, 0)
            setViewPadding(R.id.widget_level, px(level?.let { afterLevelGlyph(text, it) } ?: 0f), 0, 0, 0)
            // The other lines end on the frame, the view standing the lead past it.
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

    /**
     * The time, ticking by itself while it plays: where a song stands of its length, or what a book
     * or an episode has left at its speed. At rest it stands still where it stopped.
     */
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

    /** From the level's glyph to its number, as far as from the speaker's glyph to its name, ink to ink. */
    private fun afterLevelGlyph(text: WidgetText, level: String): Float =
        text.speakerGlyph * GLYPH_GAP - text.levelSides().second - text.sideBearing(level, WidgetLine.Speaker)

    /** What [level] takes at the speaker's row's end, from the name to the frame: its gap, its glyph, its number. */
    private fun levelRoom(text: WidgetText, level: String): Float =
        text.dp(LEVEL_GAP * text.size.scale) + text.speakerGlyph + afterLevelGlyph(text, level) +
            text.width(level, WidgetLine.Speaker) - text.endBearing(level, WidgetLine.Speaker)

    /** The speaker's volume, wherever it reports one, as Now Playing shows it beside its glyph. */
    private fun level(): String? {
        val volume = look.volume?.takeIf { look.signedIn } ?: return null
        return if (volume == 0) context.getString(R.string.player_volume_still) else volume.toString()
    }

    private companion object {
        const val SECOND_MS = 1000L

        /** From the speaker's glyph's ink to its name's, in the name's size. */
        const val GLYPH_GAP = 0.45f

        /** Before the level's glyph, in sp at the words' usual size (widget_words). */
        const val LEVEL_GAP = 8f
    }
}

/** The words' layout at [WordSize]: res/layout/widget_words.xml, an eighth smaller or a fifth larger. */
private val WordSize.layout: Int
    get() = when (this) {
        WordSize.Small -> R.layout.widget_words_small
        WordSize.Regular -> R.layout.widget_words
        WordSize.Large -> R.layout.widget_words_large
    }
