package io.github.hazymorning.amsel.api

/**
 * A square crop spoils banners, clearart, disc art and cutouts. A logo is allowed: it is often a
 * station's or podcast's only picture.
 */
internal fun List<MediaItemImage>?.coverOf(): MediaItemImage? {
    val images = this ?: return null
    return images.firstOrNull { it.type == THUMB } ?: images.firstOrNull { it.type in WIDE }
        ?: images.firstOrNull { it.type !in NOT_COVERS }
}

private const val THUMB = "thumb"

private val WIDE = setOf("fanart", "landscape")

private val NOT_COVERS = setOf("banner", "clearart", "discart", "cutout")
