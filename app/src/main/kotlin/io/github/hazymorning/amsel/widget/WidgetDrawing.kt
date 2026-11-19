package io.github.hazymorning.amsel.widget

import android.app.PendingIntent
import android.content.Context
import android.view.Gravity
import android.widget.RemoteViews
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.navigation.Opening
import kotlin.math.roundToInt

/**
 * One widget's views at each of its sizes. Every view a form uses is set each time, as a launcher
 * keeps the last ones' state.
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
    private val inks = inks(text, setup, look, pictures.faces.keys)
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
    }

    /** A tap beside the buttons opens Now Playing; signed out, Amsel. */
    private fun RemoteViews.opens() {
        if (taps) {
            setOnClickPendingIntent(
                android.R.id.background,
                open(context, if (look.signedIn) Opening.NowPlaying else null),
            )
        }
    }

    private fun RemoteViews.card(fit: WidgetFit) {
        ground()
        opens()
        setViewPadding(R.id.widget_body, 0, 0, 0, 0)
        words(fit, start = fit.inset, top = fit.inset - text.wordsTop(fit.words), end = fit.inset, width = fit.width)
        buttons(fit)
    }

    private fun RemoteViews.player(fit: WidgetFit) {
        ground()
        opens()
        setViewPadding(R.id.widget_body, 0, 0, 0, 0)
        art(fit, start = fit.inset, top = fit.inset)
        val column = fit.width - fit.inset - fit.art
        words(
            fit,
            start = WidgetSizes.ART_TO_WORDS,
            top = fit.inset - text.wordsTop(fit.words),
            end = fit.inset,
            width = column,
        )
        buttons(fit)
    }

    private fun RemoteViews.bar(fit: WidgetFit) {
        ground()
        opens()
        art(fit, start = fit.inset, top = 0f)
        // Without buttons the words keep the bar's room at its end.
        val shown = buttonsShown(fit)
        val place = fit.place(inks)
        val buttons = if (shown == 0) 0f else shown * WidgetSizes.TARGET + place.start + place.end
        val end = if (shown == 0) fit.inset else 0f
        words(
            fit,
            start = WidgetSizes.ART_TO_WORDS,
            top = centred(fit),
            end = end,
            width =
                fit.width - fit.inset - fit.art - buttons,
        )
        buttons(fit)
    }

    private fun RemoteViews.row(fit: WidgetFit) {
        ground()
        opens()
        if (buttonsShown(fit) == 0) {
            val inset = WidgetSizes.INSET
            words(fit, start = inset, top = centred(fit), end = inset, width = fit.width)
        } else {
            shows(R.id.widget_words, false)
        }
        buttons(fit)
    }

    /** The words' top padding that sets their ink, from the first to the title's baseline, in the middle. */
    private fun centred(fit: WidgetFit): Float {
        val top = text.wordsTop(fit.words)
        return (fit.height - (text.wordsBaseline(fit.words) - top)) / 2 - top
    }

    private fun buttonsShown(fit: WidgetFit): Int = if (look.signedIn) fit.buttons else 0

    /** The words, their ink [start] dp into their view, which is [width] dp wide and padded [top] and [end]. */
    private fun RemoteViews.words(fit: WidgetFit, start: Float, top: Float, end: Float, width: Float) =
        WidgetWordsViews(context, text, look, setup.slots, tone, ::px).draw(
            this,
            fit.words,
            start,
            top,
            end,
            width - start - end,
        )

    /** A bar's or a player's cover, [start] dp from the widget's start and [top] from its top; empty while nothing plays. */
    private fun RemoteViews.art(fit: WidgetFit, start: Float, top: Float) {
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
            glyphColor(context, R.id.widget_art_blank, tone.shade)
        }
        val large = fit.art >= LARGE_GLYPH_FROM
        val glyphView = if (large) R.id.widget_art_glyph_large else R.id.widget_art_glyph
        shows(R.id.widget_art_glyph, picture == null && !large)
        shows(R.id.widget_art_glyph_large, picture == null && large)
        if (picture == null) {
            glyphColor(context, glyphView, tone.quiet)
            // The glyph's view is a little larger than the glyph, so its ink can move to the middle.
            val size = if (large) LARGE_GLYPH else WidgetSizes.GLYPH
            setImageViewResource(glyphView, text.cut(R.drawable.ic_track, size))
            val drawn = text.glyph(R.drawable.ic_track, size)
            val room = ART_GLYPH_ROOM
            setViewPadding(
                glyphView,
                px(room - drawn.dx),
                px(room - drawn.dy),
                px(room + drawn.dx),
                px(room + drawn.dy),
            )
        }
        setViewPadding(R.id.widget_art_box, px(start), px(top), 0, 0)
    }

    /** The first [WidgetFit.buttons] buttons, spread from the start's ink to the end's, or side by side in a bar. */
    private fun RemoteViews.buttons(fit: WidgetFit) {
        val shown = buttonsShown(fit)
        shows(R.id.widget_row, shown > 0)
        if (shown == 0) return
        val place = fit.place(inks)
        val spread = fit.form != WidgetForm.Bar && shown > 1
        val alone = fit.form == WidgetForm.Row && shown == 1
        setViewPadding(R.id.widget_row, px(place.start), 0, px(place.end), px(place.bottom))
        setInt(R.id.widget_row, "setGravity", if (alone) Gravity.CENTER else Gravity.START or Gravity.CENTER_VERTICAL)
        for (index in 0 until MAX_BUTTONS) {
            val ids = SlotIds.of(index)
            val slot = setup.slots.getOrNull(index)?.takeIf { index < shown }
            shows(ids.slot, slot != null)
            ids.gapBefore?.let { shows(it, slot != null && spread) }
            if (slot == null) continue
            var shift = 0f
            if (index == 0) shift += place.firstShift
            if (index == shown - 1) shift += place.lastShift
            button(index, slot, shift)
        }
    }

    /** Button [index]: its ink in the middle of its target, moved by [shift] dp where the row needs it. */
    private fun RemoteViews.button(index: Int, slot: WidgetSlot, shift: Float) {
        val ids = SlotIds.of(index)
        setInt(ids.slot, "setBackgroundResource", tone.press)
        val face = pictures.faces[slot.item?.art]?.takeIf { slot.button == WidgetButton.PlayItem }
        val disc = slot.button == WidgetButton.PlayPause
        shows(ids.disc, disc)
        shows(ids.face, face != null)
        shows(ids.glyph, !disc && face == null)
        var dx = shift
        var dy = 0f
        val glyph = slot.button.glyphFor(look)
        when {
            disc -> {
                setInt(ids.disc, "setBackgroundResource", tone.disc)
                setImageViewResource(ids.disc, glyph)
                glyphColor(context, ids.disc, tone.onDisc)
            }

            face != null -> setImageViewBitmap(ids.face, face)

            else -> {
                val drawn = text.glyph(glyph, WidgetSizes.GLYPH)
                dx -= drawn.dx
                dy -= drawn.dy
                setImageViewResource(ids.glyph, glyph)
                glyphColor(context, ids.glyph, tone.glyph)
            }
        }
        // The target's padding moves what it holds by half of the difference of its sides.
        setViewPadding(
            ids.slot,
            px(maxOf(0f, 2 * dx)),
            px(maxOf(0f, 2 * dy)),
            px(maxOf(0f, -2 * dx)),
            px(
                maxOf(
                    0f,
                    -2 * dy,
                ),
            ),
        )
        setContentDescription(ids.slot, slots.label(slot, look))
        if (taps) setOnClickPendingIntent(ids.slot, press(index, slot))
    }

    /**
     * One cell: the whole tile is the button. A Play … button is its item's cover, a tile without
     * buttons the cover of what plays; any other button stands on its round ground in the middle.
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
        shows(R.id.widget_tile_button, face == null)
        shows(R.id.widget_tile_press, face == null)
        if (face != null) {
            setImageViewBitmap(R.id.widget_face, face)
            night?.let { setImageViewBitmap(R.id.widget_face_night, it) }
        } else {
            tileButton(slot)
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

    /** A tile's button on its ground: the disc for play and pause, the speaker's glyph where there is no button. */
    private fun RemoteViews.tileButton(slot: WidgetSlot?) {
        val disc = slot?.button == WidgetButton.PlayPause
        val glyph = slot?.button?.glyphFor(look) ?: R.drawable.ic_speaker
        setImageViewResource(R.id.widget_tile_button, glyph)
        setInt(R.id.widget_tile_button, "setBackgroundResource", if (disc) tone.disc else tone.ground)
        glyphColor(context, R.id.widget_tile_button, if (disc) tone.onDisc else tone.glyph)
        setImageViewResource(R.id.widget_tile_press, tone.press)
        val side = if (disc) TILE_DISC_GLYPH else WidgetSizes.GLYPH
        val pad = (WidgetSizes.TILE_GROUND - side) / 2
        // The play triangle keeps its own optical middle, as on the dock's disc; other glyphs centre by their ink.
        val drawn = if (disc) null else text.glyph(glyph, side)
        val dx = drawn?.dx ?: 0f
        val dy = drawn?.dy ?: 0f
        setViewPadding(R.id.widget_tile_button, px(pad - dx), px(pad - dy), px(pad + dx), px(pad + dy))
    }

    private fun press(index: Int, slot: WidgetSlot): PendingIntent = when {
        !look.signedIn -> open(context, null)
        slot.button == WidgetButton.Speakers -> open(context, Opening.Speakers)
        else -> WidgetTaps.pending(context, id, index, shownPlaying = look.playing)
    }

    private fun px(dp: Float): Int = (dp * density).roundToInt().coerceAtLeast(0)

    private companion object {
        /** The glyph on a tile's disc, as on the row's: the disc's 40 to its 18. */
        const val TILE_DISC_GLYPH = 20f

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
