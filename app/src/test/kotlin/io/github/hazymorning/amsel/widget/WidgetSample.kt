package io.github.hazymorning.amsel.widget

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Widgets as they stand on a home screen: what plays, a book with buttons of its own, nothing, signed out. */
object WidgetSample {
    val blueHour = WidgetItem("library://album/1", "Blue Hour", "sample:album/1")

    val music = WidgetLook(
        speaker = "Kitchen",
        volume = 40,
        title = "Lanterns",
        subtitle = "Marlene Vogt",
        art = "sample:album/1",
        playing = true,
    )

    val book = WidgetLook(
        speaker = "Living room",
        volume = 35,
        title = "Moby-Dick",
        subtitle = "Herman Melville",
        art = "sample:audiobook/1",
        playing = false,
        spoken = true,
    )

    val idle = WidgetLook(speaker = "Bedroom")

    val signedOut = WidgetLook(signedIn = false)

    /** Quieter, Louder, Continue listening, and Blue Hour. */
    val ownButtons = listOf(
        WidgetSlot(WidgetButton.Quieter),
        WidgetSlot(WidgetButton.Louder),
        WidgetSlot(WidgetButton.ContinueListening),
        WidgetSlot(WidgetButton.PlayItem, blueHour),
    )

    fun cover(name: String): Bitmap =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("covers/$name.jpg")?.use(BitmapFactory::decodeStream))

    /** The faces of [slots]' Play … buttons at the widget's size, at [density]. */
    fun thumbs(slots: List<WidgetSlot>, density: Float): Map<String, Bitmap> = slots
        .mapNotNull { it.item?.art }
        .associateWith { art ->
            val name = art.removePrefix("sample:").replace('/', '_')
            WidgetArt.thumb(cover(name), (28 * density).toInt(), density, 28f)
        }
}
