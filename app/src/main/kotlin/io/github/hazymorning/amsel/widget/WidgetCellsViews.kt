package io.github.hazymorning.amsel.widget

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.view.Gravity
import android.widget.RemoteViews
import androidx.annotation.LayoutRes
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.navigation.DeepLink

/** A button of the setup: its place there, for its tap, and what it does. */
internal typealias PlacedSlot = IndexedValue<WidgetSlot>

/** A cell's width and height in px, which a Play … button's cover fills. */
internal typealias CellSize = Pair<Int, Int>

/** The buttons a widget with [this] setup shows where [room] fit: of those with something to act on in [look], as [standing] keeps them. */
internal fun WidgetSetup.shown(look: WidgetContent, room: Int): List<PlacedSlot> =
    standing(slots.withIndex().filter { it.value.button.availableIn(look) }, room) { it.value.button }

/**
 * A widget's buttons: each a cell of equal width in its row, or a tile's one, shaded as the person
 * wants, play and pause filled in the cover's colour where it has one, each glyph's ink in the middle
 * of its cell. Taps keep each button's place in the setup.
 */
internal class WidgetCellsViews(
    private val context: Context,
    private val text: WidgetTextMetrics,
    private val slots: WidgetSlots,
    private val id: Int,
    private val setup: WidgetSetup,
    private val look: WidgetContent,
    private val pictures: WidgetBitmaps,
    private val tone: WidgetColors,
    cover: WidgetCover?,
    private val taps: Boolean,
    private val px: (Float) -> Int,
) {
    /** The cover's colour for play and pause, under a cover's looks from Android 12. */
    private val accent: WidgetAccent? = cover?.accent?.takeIf {
        setup.style.covered && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }

    /** The buttons [fit] holds. */
    fun shown(fit: WidgetFit): List<PlacedSlot> = setup.shown(look, fit.buttons)

    /**
     * [buttons] side by side in [row], as cells of [layout] [inset] px in from their targets above and
     * below, each [face] px large.
     */
    fun row(
        views: RemoteViews,
        row: Int,
        buttons: List<PlacedSlot>,
        @LayoutRes layout: Int,
        inset: Int,
        face: CellSize?,
    ) {
        views.removeAllViews(row)
        for ((index, slot) in buttons) views.addView(row, cell(index, slot, layout, inset, face))
    }

    /**
     * [buttons] beside what plays, in rows of [columns] in [rows], a gap apart; a last row with fewer
     * keeps the cells' width, in its middle.
     */
    fun rows(views: RemoteViews, rows: Int, buttons: List<PlacedSlot>, columns: Int, face: CellSize?) {
        views.removeAllViews(rows)
        buttons.chunked(columns.coerceAtLeast(1)).forEachIndexed { i, chunk ->
            if (i > 0) views.addView(rows, RemoteViews(context.packageName, R.layout.widget_cells_gap))
            val row = RemoteViews(context.packageName, R.layout.widget_cells_row)
            fewer(row, R.id.widget_cells_row, chunk.size, columns)
            for ((index, slot) in chunk) {
                row.addView(R.id.widget_cells_row, cell(index, slot, R.layout.widget_cell, inset = 0, face))
            }
            views.addView(rows, row)
        }
    }

    /** [buttons] in rows of [columns] in [pad]; a last row with fewer keeps the cells' width, in its middle. */
    fun pad(views: RemoteViews, pad: Int, buttons: List<PlacedSlot>, columns: Int, inset: Int, face: CellSize?) {
        views.removeAllViews(pad)
        for (chunk in buttons.chunked(columns)) {
            val row = RemoteViews(context.packageName, R.layout.widget_pad_row)
            fewer(row, R.id.widget_pad_row, chunk.size, columns)
            for ((index, slot) in chunk) {
                row.addView(R.id.widget_pad_row, cell(index, slot, R.layout.widget_cell, inset, face))
            }
            views.addView(pad, row)
        }
    }

    /** A row of [count] cells where others hold [columns]: each as wide as theirs, in its middle. */
    private fun fewer(row: RemoteViews, id: Int, count: Int, columns: Int) {
        if (count >= columns) return
        row.setFloat(id, "setWeightSum", columns.toFloat())
        row.setInt(id, "setGravity", Gravity.CENTER_HORIZONTAL)
    }

    /**
     * Button [index] of the setup as a cell of [layout], [inset] px in from its target above and
     * below; a Play … button is its item's cover, as large as the cell, [face] px.
     */
    private fun cell(index: Int, slot: WidgetSlot, @LayoutRes layout: Int, inset: Int, face: CellSize?) =
        RemoteViews(context.packageName, layout).apply {
            val art = slot.item?.art?.takeIf { slot.button == WidgetButton.PlayItem }
            val picture = art?.let { face?.let { (width, height) -> pictures.faces[faceKey(art, width, height)] } }
            shows(R.id.widget_face, picture != null)
            shows(R.id.widget_glyph, picture == null)
            if (picture != null) {
                // The cover is the cell: the cell's own shade shows only while pressed, the cover's under it.
                setInt(R.id.widget_cell_shape, "setBackgroundResource", tone.bare)
                setImageViewBitmap(R.id.widget_face, picture)
            } else {
                val all = setup.cells == WidgetCells.All
                dress(this, slot.button, R.id.widget_cell_shape, R.id.widget_glyph, shadeAll = all)
            }
            setViewPadding(R.id.widget_cell, 0, inset, 0, inset)
            setContentDescription(R.id.widget_cell, slots.label(slot, look))
            if (taps) setOnClickPendingIntent(R.id.widget_cell, press(index, slot))
        }

    /** A tile's cell, shaded unless the person wants none; the speaker's glyph where there is no button. */
    fun tileCell(views: RemoteViews, slot: WidgetSlot?) {
        val shaded = setup.cells != WidgetCells.None
        dress(views, slot?.button, R.id.widget_tile_cell, R.id.widget_tile_glyph, shadeAll = shaded)
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
        slot.button == WidgetButton.Speakers -> open(context, DeepLink.Speakers)
        slot.button == WidgetButton.UpNext -> open(context, DeepLink.UpNext)
        slot.button == WidgetButton.Group -> open(context, DeepLink.Group)
        else -> WidgetTapReceiver.pending(context, id, index, shownPlaying = look.playing)
    }
}

/**
 * Moves what [view] holds so [drawn]'s ink, not its square, stands in the middle; [room] is the
 * padding the view keeps on every side anyway. A padding moves its content by half its difference.
 */
internal fun RemoteViews.centre(view: Int, drawn: GlyphPosition?, room: Float, px: (Float) -> Int) {
    val dx = drawn?.dx ?: 0f
    val dy = drawn?.dy ?: 0f
    if (room > 0f) {
        setViewPadding(view, px(room - dx), px(room - dy), px(room + dx), px(room + dy))
    } else {
        // px() takes a side below none as none: only one side of each pair pads.
        setViewPadding(view, px(-2 * dx), px(-2 * dy), px(2 * dx), px(2 * dy))
    }
}
