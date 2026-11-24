package io.github.hazymorning.amsel.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import coil3.SingletonImageLoader
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.RepeatMode
import kotlinx.coroutines.runBlocking

object WidgetSample {
    val blueHour = WidgetItem("library://album/1", "Blue Hour", "sample:album/1")

    val music = WidgetContent(
        speakerId = "kitchen",
        speaker = "Kitchen",
        volume = 40,
        title = "Lanterns",
        subtitle = "Marlene Vogt",
        art = "sample:album/1",
        artLarge = "sample:album/1",
        playing = true,
        key = "library://track/1",
        favourite = true,
        shuffle = false,
        repeat = RepeatMode.Off,
        durationMs = 222_000,
        // A paused position, not a running clock, so screenshots stay the same.
        restMs = 83_000,
    )

    val book = WidgetContent(
        speakerId = "living",
        speaker = "Living room",
        volume = 35,
        title = "Moby-Dick",
        subtitle = "Herman Melville",
        art = "sample:audiobook/1",
        artLarge = "sample:audiobook/1",
        playing = false,
        spoken = true,
        durationMs = 10_800_000,
        restMs = 7_984_000,
    )

    val idle = WidgetContent(speakerId = "bedroom", speaker = "Bedroom")

    val signedOut = WidgetContent(signedIn = false)

    val ownButtons = listOf(
        WidgetSlot(WidgetButton.Quieter),
        WidgetSlot(WidgetButton.Louder),
        WidgetSlot(WidgetButton.ContinueListening),
        WidgetSlot(WidgetButton.PlayItem, blueHour),
    )

    /** A 411 dp phone with four columns: cells 92 dp apart, a widget 12 dp smaller than its cells. */
    val tile = SizeF(80f, 96f)
    val row = SizeF(172f, 96f)
    val card = SizeF(172f, 204f)
    val bar = SizeF(356f, 96f)
    val player = SizeF(356f, 204f)

    /** One row on a phone with low rows, as the owner's (about 65 dp). */
    val strip = SizeF(356f, 66f)

    val tallCard = SizeF(172f, 312f)

    val tallPlayer = SizeF(356f, 312f)

    fun cover(name: String): Bitmap =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("covers/$name.jpg")?.use(BitmapFactory::decodeStream))

    fun drawn(
        context: Context,
        setup: WidgetSetup,
        look: WidgetContent,
        size: SizeF,
        clearInk: Boolean = false,
    ): RemoteViews = runBlocking {
        WidgetRenderer(context, SingletonImageLoader.get(context))
            .draw(1, setup, look, listOf(size), taps = false, clearInk = clearInk)
    }

    /** Without cover images (blank shapes instead), for tests of the views alone. */
    fun plain(context: Context, setup: WidgetSetup, look: WidgetContent, size: SizeF = card): RemoteViews {
        val views = WidgetViews(context)
        val fit = views.fit(size.width, size.height, setup, look)
        val side = views.px(fit.art)
        val density = context.resources.displayMetrics.density
        val blanks = listOf(side).filter { it > 0 }.associateWith { WidgetArt.blank(it, density) }
        return views.build(1, setup, look, listOf(fit), WidgetBitmaps(blanks = blanks), clearInk = false, taps = false)
    }
}

/** Visible button cells in reading order: rows from the top, each from its start. */
fun View.cells(): List<View> = when {
    visibility != View.VISIBLE -> emptyList()
    id == R.id.widget_cell -> listOf(this)
    this is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).cells() }
    else -> emptyList()
}
