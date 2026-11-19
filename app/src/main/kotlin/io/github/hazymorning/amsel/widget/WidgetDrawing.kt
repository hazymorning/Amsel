package io.github.hazymorning.amsel.widget

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
    private val buttons =
        WidgetCellsViews(context, text, slots, id, setup, look, pictures, tone, ground.cover, taps, ::px)

    fun views(fit: WidgetFit): RemoteViews {
        val views = RemoteViews(context.packageName, fit.layout)
        when (fit.form) {
            WidgetForm.Tile -> views.tile(fit)
            WidgetForm.Row -> views.row(fit)
            WidgetForm.Card -> views.card(fit)
            WidgetForm.Bar -> views.bar(fit)
            WidgetForm.Player -> views.player(fit)
            WidgetForm.Poster -> views.poster(fit)
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
        setContentDescription(android.R.id.background, slots.summary(look))
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
        buttons.draw(this, fit, buttonsShown(fit))
    }

    private fun RemoteViews.player(fit: WidgetFit) {
        ground()
        art(fit, cap = true)
        val cells = if (buttonsShown(fit) > 0) WidgetSizes.CELL_HEIGHT + WidgetSizes.WORDS_TO_CELLS else 0f
        val top = fit.height - 2 * WidgetSizes.PADDING - cells
        setViewPadding(R.id.widget_top, 0, 0, 0, px(top - fit.art))
        val column = fit.width - 2 * WidgetSizes.PADDING - fit.art - WidgetSizes.ART_TO_WORDS
        words(fit, centred = true, width = column)
        buttons.draw(this, fit, buttonsShown(fit))
    }

    private fun RemoteViews.poster(fit: WidgetFit) {
        ground()
        art(fit, cap = false)
        words(fit, centred = false, width = fit.width - 2 * WidgetSizes.PADDING)
        buttons.draw(this, fit, buttonsShown(fit))
    }

    private fun RemoteViews.bar(fit: WidgetFit) {
        ground()
        art(fit, cap = false)
        val shown = buttonsShown(fit)
        val cells = if (shown == 0) 0f else shown * WidgetSizes.BAR_CELL - WidgetSizes.GAP + WidgetSizes.WORDS_TO_CELLS
        val column = fit.width - 2 * WidgetSizes.PADDING - fit.art - WidgetSizes.ART_TO_WORDS - cells
        words(fit, centred = true, width = column)
        buttons.draw(this, fit, buttonsShown(fit))
    }

    private fun RemoteViews.row(fit: WidgetFit) {
        ground()
        if (buttonsShown(fit) == 0) {
            words(fit, centred = true, width = fit.width - 2 * WidgetSizes.PADDING)
        } else {
            shows(R.id.widget_words, false)
        }
        buttons.draw(this, fit, buttonsShown(fit))
    }

    private fun buttonsShown(fit: WidgetFit): Int = if (look.signedIn) fit.buttons else 0

    private fun RemoteViews.words(fit: WidgetFit, centred: Boolean, width: Float) {
        val type = if (fit.large) text.scaled(WidgetSizes.LARGE) else text
        WidgetWordsViews(context, type, look, setup.slots, tone, ::px).draw(this, fit.words, centred, width)
    }

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
            centre(glyphView, text.glyph(R.drawable.ic_track, size), ART_GLYPH_ROOM, ::px)
        }
    }

    /**
     * One cell: the whole tile is the button, its cell in the frame as every form's. A Play … button
     * is its item's cover, a tile without buttons the cover of what plays.
     */
    private fun RemoteViews.tile(fit: WidgetFit) {
        val first = setup.slots.withIndex().firstOrNull { it.value.button.availableIn(look) }
        val slot = first?.value?.takeIf { buttonsShown(fit) > 0 }
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
            buttons.tileCell(this, slot)
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
            else -> buttons.press(first.index, slot)
        }
        setOnClickPendingIntent(android.R.id.background, press)
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

private val WidgetFit.layout: Int
    get() = when (form) {
        WidgetForm.Tile -> R.layout.widget_tile
        WidgetForm.Row -> R.layout.widget_row
        WidgetForm.Card -> if (large) R.layout.widget_card_large else R.layout.widget_card
        WidgetForm.Bar -> R.layout.widget_bar
        WidgetForm.Player -> if (large) R.layout.widget_player_large else R.layout.widget_player
        WidgetForm.Poster -> R.layout.widget_poster
    }
