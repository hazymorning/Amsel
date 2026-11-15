package io.github.hazymorning.amsel.api

/**
 * The picture that can stand as a square cover: the first thumb, else a fanart or landscape cropped
 * to its middle, else any other but a banner, a clearart, a disc or a cutout, which the crop would
 * spoil. A logo stays: it is many a station's and podcast's only picture.
 */
internal fun List<MediaItemImage>?.coverOf(): MediaItemImage? {
    val images = this ?: return null
    return images.firstOrNull { it.type == THUMB } ?: images.firstOrNull { it.type in WIDE }
        ?: images.firstOrNull { it.type !in NOT_COVERS }
}

private const val THUMB = "thumb"

private val WIDE = setOf("fanart", "landscape")

private val NOT_COVERS = setOf("banner", "clearart", "discart", "cutout")
