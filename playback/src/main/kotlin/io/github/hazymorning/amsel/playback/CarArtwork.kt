package io.github.hazymorning.amsel.playback

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The car loads artwork only from content:// URIs of the browsing app's own provider
 * (developer.android.com/training/cars/media/create-media-browser/media-artwork): the app's
 * ArtworkProvider serves `content://<package>.artwork/<proxy_id>`.
 */
class CarArtwork(private val context: Context) {
    private val root = Uri.Builder().scheme(
        ContentResolver.SCHEME_CONTENT,
    ).authority("${context.packageName}.artwork").build()

    fun uri(proxyId: String): Uri = root.buildUpon().appendPath(proxyId).build()

    /** A prefix grant for every artwork URI, as the provider is not exported. */
    fun allow(packageName: String) = context.grantUriPermission(
        packageName,
        root,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
    )

    companion object {
        /** The car's tiles are at most this wide. */
        const val SIZE = 256

        fun proxyId(uri: Uri): String? = uri.pathSegments.singleOrNull()
    }
}
