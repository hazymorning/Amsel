package io.github.hazymorning.amsel.widget

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.view.Gravity
import android.widget.RemoteViews
import androidx.annotation.LayoutRes
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.navigation.DeepLink

/** A slot with its index in the setup, which its tap carries; not its position on screen. */
internal typealias IndexedSlot = IndexedValue<WidgetSlot>

/** Width and height in px. */
internal typealias CellSize = Pair<Int, Int>

internal fun WidgetSetup.shown(look: WidgetContent, room: Int): List<IndexedSlot> =
    standing(slots.withIndex().filter { it.value.button.availableIn(look) }, room) { it.value.button }

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
    /** Android 12 and later only: tinting the cell needs RemoteViews.setColorStateList. */
    private val accent: WidgetAccent? = cover?.accent?.takeIf {
        setup.style.covered && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }

    fun shown(fit: WidgetFit): List<IndexedSlot> = setup.shown(look, fit.buttons)

    fun row(
        views: RemoteViews,
        row: Int,
        buttons: List<IndexedSlot>,
        @LayoutRes layout: Int,
        inset: Int,
        cellSize: CellSize?,
    ) {
        views.removeAllViews(row)
        for ((index, slot) in buttons) views.addView(row, cell(index, slot, layout, inset, cellSize))
    }

    fun rows(views: RemoteViews, rows: Int, buttons: List<IndexedSlot>, columns: Int, cellSize: CellSize?) {
        views.removeAllViews(rows)
        buttons.chunked(columns.coerceAtLeast(1)).forEachIndexed { i, chunk ->
            if (i > 0) views.addView(rows, RemoteViews(context.packageName, R.layout.widget_cells_gap))
            val row = RemoteViews(context.packageName, R.layout.widget_cells_row)
            fewer(row, R.id.widget_cells_row, chunk.size, columns)
            for ((index, slot) in chunk) {
                row.addView(R.id.widget_cells_row, cell(index, slot, R.layout.widget_cell, inset = 0, cellSize))
            }
            views.addView(rows, row)
        }
    }

    fun pad(views: RemoteViews, pad: Int, buttons: List<IndexedSlot>, columns: Int, inset: Int, cellSize: CellSize?) {
        views.removeAllViews(pad)
        for (chunk in buttons.chunked(columns)) {
            val row = RemoteViews(context.packageName, R.layout.widget_pad_row)
            fewer(row, R.id.widget_pad_row, chunk.size, columns)
            for ((index, slot) in chunk) {
                row.addView(R.id.widget_pad_row, cell(index, slot, R.layout.widget_cell, inset, cellSize))
            }
            views.addView(pad, row)
        }
    }

    /** A weightSum of [columns] keeps a short row's cells as wide as a full row's; the gravity centres them. */
    private fun fewer(row: RemoteViews, id: Int, count: Int, columns: Int) {
        if (count >= columns) return
        row.setFloat(id, "setWeightSum", columns.toFloat())
        row.setInt(id, "setGravity", Gravity.CENTER_HORIZONTAL)
    }

    private fun cell(index: Int, slot: WidgetSlot, @LayoutRes layout: Int, inset: Int, cellSize: CellSize?) =
        RemoteViews(context.packageName, layout).apply {
            val art = slot.item?.art?.takeIf { slot.button == WidgetButton.PlayItem }
            val picture = art?.let { cellSize?.let { (width, height) -> pictures.faces[faceKey(art, width, height)] } }
            shows(R.id.widget_face, picture != null)
            shows(R.id.widget_glyph, picture == null)
            if (picture != null) {
                // The cover replaces the shaded cell; the bare drawable shades only while pressed.
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

    fun tileCell(views: RemoteViews, slot: WidgetSlot?) {
        val shaded = setup.cells != WidgetCells.None
        dress(views, slot?.button, R.id.widget_tile_cell, R.id.widget_tile_glyph, shadeAll = shaded)
    }

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
            // Not centred by its bounds: the play triangle's drawable is already optically centred, as on the dock.
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
 * Pads [view] so [drawn]'s visible bounds, not its box, are centred; [room] is the view's base padding on
 * every side. Unequal padding on two opposite sides moves the content by half the difference.
 */
internal fun RemoteViews.centre(view: Int, drawn: GlyphPosition?, room: Float, px: (Float) -> Int) {
    val dx = drawn?.dx ?: 0f
    val dy = drawn?.dy ?: 0f
    if (room > 0f) {
        setViewPadding(view, px(room - dx), px(room - dy), px(room + dx), px(room + dy))
    } else {
        // px() clamps a negative side to 0, so only one side of each pair is padded.
        setViewPadding(view, px(-2 * dx), px(-2 * dy), px(2 * dx), px(2 * dy))
    }
}
