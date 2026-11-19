package io.github.hazymorning.amsel.widget

import android.app.PendingIntent
import android.content.Context
import android.widget.RemoteViews
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.navigation.Opening
import kotlin.math.roundToInt

/**
 * One widget's views at each of its sizes. The frame, the cells and the gaps are the layouts'; here
 * only what shows and in which tone. Every view a form uses is set each time, as a launcher keeps
 * the last ones' state.
 */
internal class WidgetDrawing(
    private val context: Context,
    private val text: WidgetText,
    private val slots: WidgetSlots,
    private val id: Int,
    private val setup: WidgetSetup,
    private val look: WidgetLook,
    private val pictures: WidgetPictures,
    clearInk: Boolean,
    private val taps: Boolean,
) {
    private val playing = look.signedIn && look.title != null
    private val ground = WidgetGround.of(setup.style, pictures.cover.takeIf { playing }, clearInk)
    private val tone = ground.tone
    private val density = context.resources.displayMetrics.density

    fun views(fit: WidgetFit): RemoteViews {
        val views = RemoteViews(context.packageName, fit.form.layout)
        when (fit.form) {
            WidgetForm.Tile -> views.tile(fit)
            WidgetForm.Row -> views.row(fit)
            WidgetForm.Card -> views.card(fit)
            WidgetForm.Bar -> views.bar(fit)
            WidgetForm.Player -> views.player(fit)
        }
        return views
    }

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
        if (taps) {
            setOnClickPendingIntent(
                android.R.id.background,
                open(context, if (look.signedIn) Opening.NowPlaying else null),
            )
        }
    }

    private fun RemoteViews.card(fit: WidgetFit) {
        ground()
        words(fit, centred = false, width = fit.width - 2 * WidgetSizes.PADDING)
        cells(fit)
    }

    private fun RemoteViews.player(fit: WidgetFit) {
        ground()
        art(fit, cap = true)
        val column = fit.width - 2 * WidgetSizes.PADDING - fit.art - WidgetSizes.ART_TO_WORDS
        words(fit, centred = false, width = column)
        cells(fit)
    }

    private fun RemoteViews.bar(fit: WidgetFit) {
        ground()
        art(fit, cap = false)
        val shown = buttonsShown(fit)
        val cells = if (shown == 0) 0f else shown * WidgetSizes.BAR_CELL - WidgetSizes.GAP + WidgetSizes.WORDS_TO_CELLS
        val column = fit.width - 2 * WidgetSizes.PADDING - fit.art - WidgetSizes.ART_TO_WORDS - cells
        words(fit, centred = true, width = column)
        cells(fit)
    }

    private fun RemoteViews.row(fit: WidgetFit) {
        ground()
        if (buttonsShown(fit) == 0) {
            words(fit, centred = true, width = fit.width - 2 * WidgetSizes.PADDING)
        } else {
            shows(R.id.widget_words, false)
        }
        cells(fit)
    }

    private fun buttonsShown(fit: WidgetFit): Int = if (look.signedIn) fit.buttons else 0

    private fun RemoteViews.words(fit: WidgetFit, centred: Boolean, width: Float) =
        WidgetWordsViews(context, text, look, setup.slots, tone, ::px).draw(this, fit.words, centred, width)

    /**
     * A bar's or a player's cover, as high as its place in the layout; empty while nothing plays. A
     * player's is [cap]ped at half its width, so the words keep their room in a tall one.
     */
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
        val most = if (cap) px((fit.width - 2 * WidgetSizes.PADDING) * WidgetSizes.ART_SHARE) else Int.MAX_VALUE
        for (view in listOf(
            R.id.widget_art,
            R.id.widget_art_night,
            R.id.widget_art_blank,
        )) {
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
            centre(glyphView, text.glyph(R.drawable.ic_track, size), ART_GLYPH_ROOM)
        }
    }

    /** The first [WidgetFit.buttons] buttons as cells; the layout gives them equal widths and gaps. */
    private fun RemoteViews.cells(fit: WidgetFit) {
        val shown = buttonsShown(fit)
        shows(R.id.widget_row, shown > 0)
        for (index in 0 until MAX_BUTTONS) {
            val ids = SlotIds.of(index)
            val slot = setup.slots.getOrNull(index)?.takeIf { index < shown }
            shows(ids.slot, slot != null)
            if (slot != null) cell(index, slot)
        }
    }

    /** Button [index]: a cell in the tone's shade, play and pause filled; its glyph's ink in the middle. */
    private fun RemoteViews.cell(index: Int, slot: WidgetSlot) {
        val ids = SlotIds.of(index)
        val play = slot.button == WidgetButton.PlayPause
        setInt(ids.slot, "setBackgroundResource", if (play) tone.playCell else tone.cell)
        val face = pictures.faces[slot.item?.art]?.takeIf { slot.button == WidgetButton.PlayItem }
        shows(ids.face, face != null)
        shows(ids.glyph, face == null)
        if (face != null) {
            setImageViewBitmap(ids.face, face)
            setViewPadding(ids.slot, 0, 0, 0, 0)
        } else {
            val glyph = slot.button.glyphFor(look)
            setImageViewResource(ids.glyph, glyph)
            glyphColor(context, ids.glyph, if (play) tone.onDisc else tone.glyph)
            // The play triangle keeps its own optical middle, as on the dock's disc.
            val drawn = text.glyph(glyph, WidgetSizes.GLYPH).takeUnless { play }
            centre(ids.slot, drawn, room = 0f)
        }
        setContentDescription(ids.slot, slots.label(slot, look))
        if (taps) setOnClickPendingIntent(ids.slot, press(index, slot))
    }

    /**
     * Moves what [view] holds so [drawn]'s ink, not its square, stands in the middle; [room] is the
     * padding the view keeps on every side anyway. A padding moves its content by half its difference.
     */
    private fun RemoteViews.centre(view: Int, drawn: GlyphPlace?, room: Float) {
        val dx = drawn?.dx ?: 0f
        val dy = drawn?.dy ?: 0f
        if (room > 0f) {
            setViewPadding(view, px(room - dx), px(room - dy), px(room + dx), px(room + dy))
        } else {
            setViewPadding(
                view,
                px(maxOf(0f, -2 * dx)),
                px(maxOf(0f, -2 * dy)),
                px(maxOf(0f, 2 * dx)),
                px(
                    maxOf(
                        0f,
                        2 * dy,
                    ),
                ),
            )
        }
    }

    /**
     * One cell: the whole tile is the button, its cell in the frame as every form's. A Play … button
     * is its item's cover, a tile without buttons the cover of what plays.
     */
    private fun RemoteViews.tile(fit: WidgetFit) {
        val slot = setup.slots.firstOrNull()?.takeIf { buttonsShown(fit) > 0 }
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
            tileCell(slot)
        }
        val says = when {
            !look.signedIn -> context.getString(R.string.widget_sign_in)
            slot == null -> context.getString(R.string.open_now_playing)
            else -> slots.label(slot, look)
        }
        setContentDescription(android.R.id.background, says)
        if (!taps) return
        val press = when {
            !look.signedIn -> open(context, null)
            slot == null -> open(context, Opening.NowPlaying)
            else -> press(0, slot)
        }
        setOnClickPendingIntent(android.R.id.background, press)
    }

    /** A tile's cell: filled for play and pause; the speaker's glyph where there is no button. */
    private fun RemoteViews.tileCell(slot: WidgetSlot?) {
        val play = slot?.button == WidgetButton.PlayPause
        val glyph = slot?.button?.glyphFor(look) ?: R.drawable.ic_speaker
        setInt(R.id.widget_tile_cell, "setBackgroundResource", if (play) tone.playCell else tone.cell)
        setImageViewResource(R.id.widget_tile_glyph, glyph)
        glyphColor(context, R.id.widget_tile_glyph, if (play) tone.onDisc else tone.glyph)
        centre(R.id.widget_tile_cell, text.glyph(glyph, WidgetSizes.GLYPH).takeUnless { play }, room = 0f)
    }

    private fun press(index: Int, slot: WidgetSlot): PendingIntent = when {
        !look.signedIn -> open(context, null)
        slot.button == WidgetButton.Speakers -> open(context, Opening.Speakers)
        else -> WidgetTaps.pending(context, id, index, shownPlaying = look.playing)
    }

    private fun px(dp: Float): Int = (dp * density).roundToInt().coerceAtLeast(0)

    private companion object {
        /** From here an empty cover's glyph is the large one, as in Amsel. */
        const val LARGE_GLYPH_FROM = 128f
        const val LARGE_GLYPH = 32f

        /** The room around an empty cover's glyph in its view (res/layout/widget_art.xml). */
        const val ART_GLYPH_ROOM = 4f
    }
}

private val WidgetForm.layout: Int
    get() = when (this) {
        WidgetForm.Tile -> R.layout.widget_tile
        WidgetForm.Row -> R.layout.widget_row
        WidgetForm.Card -> R.layout.widget_card
        WidgetForm.Bar -> R.layout.widget_bar
        WidgetForm.Player -> R.layout.widget_player
    }
