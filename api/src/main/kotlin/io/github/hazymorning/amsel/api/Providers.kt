package io.github.hazymorning.amsel.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A music service, a source of metadata, a kind of speaker or a plugin the server has loaded.
 * Any signed-in user may list them (`providers.read`), a guest too.
 */
@Serializable
public data class Provider(
    val domain: String,
    val type: String = "",
    val available: Boolean = true,
    @SerialName("supported_features") val supportedFeatures: List<String> = emptyList(),
) {
    /** A plugin whose commands the server answers. */
    public val isLoadedPlugin: Boolean get() = type == "plugin" && available
}

/** Only what has finished loading: a plugin listed here has its commands. */
public suspend fun MaApi.providers(): List<Provider> = list("providers", Provider.serializer())
