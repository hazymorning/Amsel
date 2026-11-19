package io.github.hazymorning.amsel.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.SizeF
import android.widget.RemoteViews
import coil3.SingletonImageLoader
import kotlinx.coroutines.runBlocking

/** Widgets as they stand on a home screen: what plays, a book, nothing, signed out; at a usual phone's sizes. */
object WidgetSample {
    val blueHour = WidgetItem("library://album/1", "Blue Hour", "sample:album/1")

    val music = WidgetLook(
        speakerId = "kitchen",
        speaker = "Kitchen",
        volume = 40,
        title = "Lanterns",
        subtitle = "Marlene Vogt",
        art = "sample:album/1",
        artLarge = "sample:album/1",
        playing = true,
    )

    val book = WidgetLook(
        speakerId = "living",
        speaker = "Living room",
        volume = 35,
        title = "Moby-Dick",
        subtitle = "Herman Melville",
        art = "sample:audiobook/1",
        artLarge = "sample:audiobook/1",
        playing = false,
        spoken = true,
    )

    val idle = WidgetLook(speakerId = "bedroom", speaker = "Bedroom")

    val signedOut = WidgetLook(signedIn = false)

    /** Quieter, Louder, Continue listening, and Blue Hour. */
    val ownButtons = listOf(
        WidgetSlot(WidgetButton.Quieter),
        WidgetSlot(WidgetButton.Louder),
        WidgetSlot(WidgetButton.ContinueListening),
        WidgetSlot(WidgetButton.PlayItem, blueHour),
    )

    /** On a phone 411 dp wide with four columns: cells 92 dp apart, a widget 12 dp less than its cells. */
    val tile = SizeF(80f, 96f)
    val row = SizeF(172f, 96f)
    val card = SizeF(172f, 204f)
    val bar = SizeF(356f, 96f)
    val player = SizeF(356f, 204f)

    fun cover(name: String): Bitmap =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("covers/$name.jpg")?.use(BitmapFactory::decodeStream))

    /** [setup] showing [look] at [size], with its pictures, as Amsel draws it; nothing on it acts. */
    fun drawn(
        context: Context,
        setup: WidgetSetup,
        look: WidgetLook,
        size: SizeF,
        clearInk: Boolean = false,
    ): RemoteViews = runBlocking {
        WidgetRender(context, SingletonImageLoader.get(context))
            .draw(1, setup, look, listOf(size), taps = false, clearInk = clearInk)
    }

    /** [setup] showing [look] at [size] without covers (an empty one where a cover stands), for what the views say. */
    fun plain(context: Context, setup: WidgetSetup, look: WidgetLook, size: SizeF = card): RemoteViews {
        val views = WidgetViews(context)
        val fit = views.fit(size.width, size.height, setup, look, emptySet())
        val side = views.px(fit.art)
        val blanks = if (side >
            0
        ) {
            mapOf(side to WidgetArt.blank(side, context.resources.displayMetrics.density))
        } else {
            emptyMap()
        }
        return views.build(1, setup, look, listOf(fit), WidgetPictures(blanks = blanks), clearInk = false, taps = false)
    }
}
