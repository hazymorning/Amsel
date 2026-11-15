package io.github.hazymorning.amsel.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImagesTest {
    private val images = Images(ServerAddress.parse("192.168.1.20")!!)

    @Test fun `builds proxy urls on the connection address`() {
        assertEquals("http://192.168.1.20:8095/imageproxy/abc", images.url(MediaItemImage(proxyId = "abc")))
        assertNull(images.url(MediaItemImage(proxyId = null)))
    }

    @Test fun `rebases current media urls`() {
        assertEquals(
            "http://192.168.1.20:8095/imageproxy/cba0",
            images.rebase("http://172.17.0.4:8095/imageproxy/cba0?size=512&fmt=jpg"),
        )
        // An outside source's own artwork would be fetched from its host, not the server.
        assertNull(images.rebase("https://i.scdn.co/image/ab67616d0000b273"))
    }

    @Test fun `rounds sizes up to the ones the proxy serves`() {
        assertEquals("u?size=80", Images.sized("u", 48))
        assertEquals("u?size=160", Images.sized("u", 144))
        assertEquals("u?size=1024", Images.sized("u", 1000))
        assertEquals("u?size=0", Images.sized("u", 2000))
    }
}
