package io.github.hazymorning.amsel.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Which of the pictures the server sends is an item's cover. */
class CoverTest {
    @Test fun `a queue's song wears the album nested whole in it, as the server's own choice does`() {
        val item = decodeList("2.11", "player_queues_items", QueueItem.serializer()).first()
        val song = item.mediaItem ?: error("no song")
        assertNull(song.metadata?.images)
        assertNotNull(item.image?.proxyId)
        assertEquals(item.image?.proxyId, song.cover?.proxyId)
    }

    @Test fun `an album's artists nested whole bring their own pictures`() = listOf("2.10", "2.11").forEach { v ->
        val artist = decodeOne(v, "music_albums_get", MediaItem.serializer()).artists.first()
        val listed = decodeList(v, "music_artists_library_items", MediaItem.serializer()).first { it.uri == artist.uri }
        assertNull(artist.image)
        assertNotNull(listed.cover)
        assertEquals(v, listed.cover, artist.cover)
    }

    @Test fun `a thumb comes first, a fanart or landscape next, and a banner or a disc never`() {
        assertEquals("thumb", pictured("banner", "fanart", "thumb").cover?.proxyId)
        assertEquals("fanart", pictured("fanart").cover?.proxyId)
        assertEquals("landscape", pictured("clearart", "landscape", "logo").cover?.proxyId)
        assertEquals("logo", pictured("cutout", "logo").cover?.proxyId)
        assertNull(pictured("discart", "banner").cover)
        assertNull(pictured("clearart", "cutout").cover)
    }

    @Test fun `an item without a picture of its own takes its album's`() {
        val album = ItemMapping("First Light", "library://album/4", image = MediaItemImage(proxyId = "album"))
        assertEquals("album", pictured("discart").copy(album = album).cover?.proxyId)
    }

    /** An item whose pictures are of [types], each one's proxy id its type. */
    private fun pictured(vararg types: String) = MediaItem(
        itemId = "1",
        provider = "library",
        name = "Dawn Chorus",
        uri = "library://track/1",
        mediaType = "track",
        metadata = MediaItemMetadata(images = types.map { MediaItemImage(type = it, proxyId = it) }),
    )
}
