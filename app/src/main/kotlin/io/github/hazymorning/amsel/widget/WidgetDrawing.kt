package io.github.hazymorning.amsel.widget

import android.content.Context
import android.view.Gravity
import android.widget.RemoteViews
import androidx.annotation.LayoutRes
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.navigation.DeepLink
import io.github.hazymorning.amsel.widget.WidgetSizes.ART_TO_WORDS
import io.github.hazymorning.amsel.widget.WidgetSizes.BAR_CELL
import io.github.hazymorning.amsel.widget.WidgetSizes.CELL_HEIGHT
import io.github.hazymorning.amsel.widget.WidgetSizes.GAP
import io.github.hazymorning.amsel.widget.WidgetSizes.LEAD
import io.github.hazymorning.amsel.widget.WidgetSizes.PADDING
import io.github.hazymorning.amsel.widget.WidgetSizes.REACH
import io.github.hazymorning.amsel.widget.WidgetSizes.WORDS_TO_CELLS
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Sets every view a form uses, each time: a launcher can reapply onto views that keep their earlier state. */
internal class WidgetDrawing(
    private val context: Context,
    private val text: WidgetTextMetrics,
    private val slots: WidgetSlots,
    private val id: Int,
    private val setup: WidgetSetup,
    private val look: WidgetContent,
    private val pictures: WidgetBitmaps,
    clearInk: Boolean,
    private val taps: Boolean,
) {
    private val playing = look.signedIn && look.title != null
    private val ground = WidgetBackground.of(setup.style, pictures.cover.takeIf { playing }, clearInk)
    private val tone = ground.tone
    private val density = context.resources.displayMetrics.density
    private val cells =
        WidgetCellsViews(context, text, slots, id, setup, look, pictures, tone, ground.cover, taps, ::px)
    private val words = WidgetWordsViews(context, text, look, tone, ::px)

    /** Bare buttons draw no cell, so their row may reach into the frame. */
    private val side = if (setup.cells == WidgetCells.None) REACH else PADDING - GAP / 2

    fun views(fit: WidgetFit): RemoteViews = when (fit.form) {
        WidgetForm.Tile -> tile(fit)
        WidgetForm.Pad -> pad(fit)
        WidgetForm.Line, WidgetForm.Card, WidgetForm.Poster -> column(fit)
        WidgetForm.Bar -> bar(fit)
        WidgetForm.Player -> player(fit)
    }

    private fun after(shown: List<IndexedSlot>) = setup.words == WidgetWordsPosition.After && shown.isNotEmpty()

    private fun column(fit: WidgetFit) = grounded(R.layout.widget_column).apply {
        val shown = cells.shown(fit)
        val after = after(shown)
        val poster = fit.form == WidgetForm.Poster
        val align = when {
            fit.form == WidgetForm.Line -> WordsAlign.Middle
            after -> WordsAlign.Bottom
            else -> WordsAlign.Top
        }
        // The text's visible edge, not its box, meets the frame: the box reaches LEAD past it on the aligned
        // side, LEAD / 2 above and below when centred. A poster's cover meets the frame itself.
        val top = when {
            align == WordsAlign.Top && !poster -> PADDING - LEAD
            align == WordsAlign.Middle -> PADDING - LEAD / 2
            else -> PADDING
        }
        val bottom = when (align) {
            WordsAlign.Bottom -> PADDING - LEAD
            WordsAlign.Middle -> PADDING - LEAD / 2
            else -> PADDING
        }
        setViewPadding(R.id.widget_body, px(PADDING), px(top), px(PADDING), px(bottom))
        val gap = if (shown.isEmpty()) 0 else px(WORDS_TO_CELLS)
        setViewPadding(R.id.widget_part, 0, if (after) gap else 0, 0, if (after) 0 else gap)
        setInt(R.id.widget_part, "setGravity", align.gravity)
        shows(R.id.widget_art_box, poster)
        if (poster) art(fit, cap = false)
        words(fit, align, fit.width - 2 * PADDING)
        cellRows(shown, after, fit)
    }

    private fun player(fit: WidgetFit) = grounded(R.layout.widget_player).apply {
        val shown = cells.shown(fit)
        val after = after(shown)
        art(fit, cap = true)
        val spare = px(fit.height - 2 * PADDING - rowsHeight(buttonRows(fit)) - fit.art)
        setViewPadding(R.id.widget_top, 0, if (after) spare else 0, 0, if (after) 0 else spare)
        words(fit, WordsAlign.Middle, fit.width - 2 * PADDING - fit.art - ART_TO_WORDS)
        cellRows(shown, after, fit)
    }

    private fun bar(fit: WidgetFit) = grounded(R.layout.widget_bar).apply {
        val shown = cells.shown(fit)
        val after = after(shown)
        art(fit, cap = false)
        val cell = minOf(fit.height - 2 * PADDING, CELL_HEIGHT)
        val target = target(cell, fit.height)
        // The rows span to REACH from the top and bottom edges (widget_bar.xml); this centres the targets.
        val upright = px((fit.height - 2 * REACH - target) / 2)
        // WORDS_TO_CELLS from the cells, less the half GAP their targets reach past them.
        val gap = px(WORDS_TO_CELLS - GAP / 2)
        setViewPadding(R.id.widget_cells_start, 0, upright, gap, upright)
        setViewPadding(R.id.widget_cells_end, 0, upright, 0, upright)
        setViewPadding(R.id.widget_words_box, px(ART_TO_WORDS), 0, if (shown.isEmpty() || after) 0 else gap, 0)
        val cellsWidth = if (shown.isEmpty()) 0f else shown.size * BAR_CELL - GAP + WORDS_TO_CELLS
        words(fit, WordsAlign.Middle, fit.width - 2 * PADDING - fit.art - ART_TO_WORDS - cellsWidth)
        val (row, other) = rowsBy(after)
        clear(other)
        shows(row, shown.isNotEmpty())
        cells.row(this, row, shown, R.layout.widget_cell_bar, inset = px((target - cell) / 2), cellSizePx(fit))
    }

    private fun pad(fit: WidgetFit) = grounded(R.layout.widget_pad).apply {
        val shown = cells.shown(fit)
        val rows = ceil(shown.size.toFloat() / fit.columns).toInt()
        val target = target(fit.cell, fit.height)
        // Each row pads its targets by GAP / 2 above and below (widget_pad_row.xml).
        val upright = px((fit.height - rows * (target + GAP)) / 2)
        setViewPadding(R.id.widget_pad, px(side), upright, px(side), upright)
        cells.pad(this, R.id.widget_pad, shown, fit.columns, inset = px((target - fit.cell) / 2), cellSizePx(fit))
    }

    private fun grounded(@LayoutRes layout: Int) = RemoteViews(context.packageName, layout).apply { ground() }

    private fun RemoteViews.ground(clear: Boolean = false) {
        val background = if (clear) R.drawable.widget_background_clear else ground.background
        setInt(android.R.id.background, "setBackgroundResource", background)
        val cover = ground.cover?.takeUnless { clear }
        if (cover != null) {
            setImageViewBitmap(R.id.widget_cover, cover.bitmap)
            setInt(R.id.widget_veil, "setBackgroundResource", ground.veil)
        }
        shows(R.id.widget_cover, cover != null)
        shows(R.id.widget_veil, cover != null)
        setContentDescription(android.R.id.background, slots.summary(look))
        if (taps) {
            setOnClickPendingIntent(
                android.R.id.background,
                open(context, if (look.signedIn) DeepLink.NowPlaying else null),
            )
        }
    }

    private fun RemoteViews.words(fit: WidgetFit, align: WordsAlign, width: Float) {
        removeAllViews(R.id.widget_words_box)
        words.views(fit.words, fit.size, align, width)?.let { addView(R.id.widget_words_box, it) }
    }

    private fun RemoteViews.cellRows(shown: List<IndexedSlot>, after: Boolean, fit: WidgetFit) {
        val (rows, other) = rowsBy(after)
        clear(other)
        shows(rows, shown.isNotEmpty())
        setViewPadding(rows, px(side), 0, px(side), 0)
        cells.rows(this, rows, shown, fit.columns, cellSizePx(fit))
    }

    private fun cellSizePx(fit: WidgetFit): CellSize? =
        cellSize(fit, setup.cells == WidgetCells.None)?.let { (width, height) -> px(width) to px(height) }

    private fun rowsBy(after: Boolean): Pair<Int, Int> {
        val (start, end) = R.id.widget_cells_start to R.id.widget_cells_end
        return if (after) start to end else end to start
    }

    private fun RemoteViews.clear(place: Int) {
        removeAllViews(place)
        shows(place, false)
    }

    /** [cap]: a player's cover is limited to ART_SHARE of the width, so a tall player keeps space for the text. */
    private fun RemoteViews.art(fit: WidgetFit, cap: Boolean) {
        val side = px(fit.art)
        val picture = pictures.art[side]?.takeIf { playing }
        val blank = pictures.blanks[side]
        shows(R.id.widget_art, picture != null)
        shows(R.id.widget_art_night, picture != null && blank != null)
        shows(R.id.widget_art_blank, picture == null)
        if (picture != null) {
            setImageViewBitmap(R.id.widget_art, picture)
            blank?.let { setImageViewBitmap(R.id.widget_art_night, it) }
        } else {
            blank?.let { setImageViewBitmap(R.id.widget_art_blank, it) }
            glyphColor(context, R.id.widget_art_blank, tone.cellColor)
        }
        val most = if (cap) px((fit.width - 2 * PADDING) * WidgetSizes.ART_SHARE) else Int.MAX_VALUE
        for (view in listOf(R.id.widget_art, R.id.widget_art_night, R.id.widget_art_blank)) {
            setInt(view, "setMaxWidth", most)
        }
        val large = fit.art >= LARGE_GLYPH_FROM
        val glyphView = if (large) R.id.widget_art_glyph_large else R.id.widget_art_glyph
        shows(R.id.widget_art_glyph, picture == null && !large)
        shows(R.id.widget_art_glyph_large, picture == null && large)
        if (picture == null) {
            glyphColor(context, glyphView, tone.quiet)
            val size = if (large) LARGE_GLYPH else WidgetSizes.GLYPH
            setImageViewResource(glyphView, text.cut(R.drawable.ic_track, size))
            centre(glyphView, text.glyph(R.drawable.ic_track, size), ART_GLYPH_ROOM, ::px)
        }
    }

    private fun tile(fit: WidgetFit) = RemoteViews(context.packageName, R.layout.widget_tile).apply {
        val first = setup.shown(look, 1).firstOrNull()
        val slot = first?.value?.takeIf { fit.buttons > 0 }
        val side = px(minOf(fit.width, fit.height))
        val face = when {
            slot?.button == WidgetButton.PlayItem -> pictures.tiles[side]
            slot == null && playing -> pictures.art[side]
            else -> null
        }
        val night = pictures.blanks[side]?.takeIf { face != null }
        ground(clear = face != null)
        shows(R.id.widget_face, face != null)
        shows(R.id.widget_face_night, night != null)
        shows(R.id.widget_tile_cell, face == null)
        if (face != null) {
            setImageViewBitmap(R.id.widget_face, face)
            night?.let { setImageViewBitmap(R.id.widget_face_night, it) }
        } else {
            cells.tileCell(this, slot)
        }
        val says = when {
            !look.signedIn -> context.getString(R.string.widget_sign_in)
            slot == null -> context.getString(R.string.open_now_playing)
            else -> slots.label(slot, look)
        }
        setContentDescription(android.R.id.background, says)
        if (!taps) return@apply
        val press = when {
            !look.signedIn -> open(context, null)
            slot == null -> open(context, DeepLink.NowPlaying)
            else -> cells.press(first.index, slot)
        }
        setOnClickPendingIntent(android.R.id.background, press)
    }

    private fun px(dp: Float): Int = (dp * density).roundToInt().coerceAtLeast(0)

    private companion object {
        const val LARGE_GLYPH_FROM = 128f
        const val LARGE_GLYPH = 32f

        /** The padding of the empty cover's glyph views in widget_art.xml and widget_column.xml. */
        const val ART_GLYPH_ROOM = 4f
    }
}

private val WordsAlign.gravity: Int
    get() = when (this) {
        WordsAlign.Top -> Gravity.TOP
        WordsAlign.Bottom -> Gravity.BOTTOM
        WordsAlign.Middle -> Gravity.CENTER_VERTICAL
    }
