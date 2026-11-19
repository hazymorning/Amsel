package io.github.hazymorning.amsel.widget

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.widget.RemoteViews
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.navigation.Opening

/**
 * A widget's buttons: each in its place in a row of equal cells, or a tile's one, shaded as the
 * person wants, play and pause filled in the cover's colour where it has one, each glyph's ink in
 * the middle of its cell. Taps keep each button's place in the setup.
 */
internal class WidgetCellsViews(
    private val context: Context,
    private val text: WidgetText,
    private val slots: WidgetSlots,
    private val id: Int,
    private val setup: WidgetSetup,
    private val look: WidgetLook,
    private val pictures: WidgetPictures,
    private val tone: Tone,
    cover: WidgetCover?,
    private val taps: Boolean,
    private val px: (Float) -> Int,
) {
    /** The cover's colour for play and pause, under the Cover look from Android 12. */
    private val accent: WidgetAccent? = cover?.accent?.takeIf {
        setup.style == WidgetStyle.Cover && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }

    /** The first [shown] of the buttons that have something to act on; the layout gives them equal widths and gaps. */
    fun draw(views: RemoteViews, fit: WidgetFit, shown: Int) = with(views) {
        val buttons = setup.slots.withIndex().filter { it.value.button.availableIn(look) }.take(shown)
        shows(R.id.widget_row, buttons.isNotEmpty())
        // In one row the cells are as high as under words, in the middle, not a tall capsule.
        val oneRow = fit.form == WidgetForm.Row || fit.form == WidgetForm.Bar
        val spare = if (oneRow) (fit.height - 2 * WidgetSizes.PADDING - WidgetSizes.CELL_HEIGHT) / 2 else 0f
        setViewPadding(R.id.widget_row, 0, px(spare), 0, px(spare))
        for (place in 0 until MAX_BUTTONS) {
            val button = buttons.getOrNull(place)
            shows(SlotIds.of(place).slot, button != null)
            if (button != null) cell(this, place, button.index, button.value)
        }
    }

    /** The button at [place] in the row, [index] in the setup. */
    private fun cell(views: RemoteViews, place: Int, index: Int, slot: WidgetSlot) = with(views) {
        val ids = SlotIds.of(place)
        val face = pictures.faces[slot.item?.art]?.takeIf { slot.button == WidgetButton.PlayItem }
        shows(ids.face, face != null)
        shows(ids.glyph, face == null)
        if (face != null) {
            setInt(ids.slot, "setBackgroundResource", if (setup.cells == WidgetCells.All) tone.cell else tone.bare)
            cellArgb(ids.slot, null)
            setImageViewBitmap(ids.face, face)
            setViewPadding(ids.slot, 0, 0, 0, 0)
        } else {
            dress(this, slot.button, ids.slot, ids.glyph, shadeAll = setup.cells == WidgetCells.All)
        }
        setContentDescription(ids.slot, slots.label(slot, look))
        if (taps) setOnClickPendingIntent(ids.slot, press(index, slot))
    }

    /** A tile's cell, shaded unless the person wants none; the speaker's glyph where there is no button. */
    fun tileCell(views: RemoteViews, slot: WidgetSlot?) = with(views) {
        dress(
            this,
            slot?.button,
            R.id.widget_tile_cell,
            R.id.widget_tile_glyph,
            shadeAll =
                setup.cells != WidgetCells.None,
        )
    }

    /** [button]'s cell and glyph in [cell] and [glyph]: play and pause filled, others shaded where [shadeAll]. */
    private fun dress(views: RemoteViews, button: WidgetButton?, cell: Int, glyph: Int, shadeAll: Boolean) =
        with(views) {
            val play = button == WidgetButton.PlayPause
            val filled = play && setup.cells != WidgetCells.None
            val background = when {
                filled -> tone.playCell
                shadeAll -> tone.cell
                else -> tone.bare
            }
            setInt(cell, "setBackgroundResource", background)
            val drawn = button?.glyphFor(look) ?: R.drawable.ic_speaker
            setImageViewResource(glyph, drawn)
            val accent = accent.takeIf { filled }
            cellArgb(cell, accent?.cell)
            when {
                accent != null -> glyphArgb(glyph, accent.glyph)
                filled -> glyphColor(context, glyph, tone.onDisc)
                button?.isOff(look) == true -> glyphColor(context, glyph, tone.quiet)
                else -> glyphColor(context, glyph, tone.glyph)
            }
            // The play triangle keeps its own optical middle, as on the dock's disc.
            centre(cell, text.glyph(drawn, WidgetSizes.GLYPH).takeUnless { play }, room = 0f, px)
        }

    fun press(index: Int, slot: WidgetSlot): PendingIntent = when {
        !look.signedIn -> open(context, null)
        slot.button == WidgetButton.Speakers -> open(context, Opening.Speakers)
        else -> WidgetTaps.pending(context, id, index, shownPlaying = look.playing)
    }
}

/**
 * Moves what [view] holds so [drawn]'s ink, not its square, stands in the middle; [room] is the
 * padding the view keeps on every side anyway. A padding moves its content by half its difference.
 */
internal fun RemoteViews.centre(view: Int, drawn: GlyphPlace?, room: Float, px: (Float) -> Int) {
    val dx = drawn?.dx ?: 0f
    val dy = drawn?.dy ?: 0f
    if (room > 0f) {
        setViewPadding(view, px(room - dx), px(room - dy), px(room + dx), px(room + dy))
    } else {
        // px() takes a side below none as none: only one side of each pair pads.
        setViewPadding(view, px(-2 * dx), px(-2 * dy), px(2 * dx), px(2 * dy))
    }
}
