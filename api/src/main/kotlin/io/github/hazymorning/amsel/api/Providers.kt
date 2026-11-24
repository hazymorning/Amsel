package io.github.hazymorning.amsel.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class Provider(
    val domain: String,
    val type: String = "",
    val available: Boolean = true,
    @SerialName("supported_features") val supportedFeatures: List<String> = emptyList(),
) {
    public val isLoadedPlugin: Boolean get() = type == "plugin" && available
}

/** Only what has finished loading, so a listed plugin has its commands. A guest may list them too. */
public suspend fun MaApi.providers(): List<Provider> = list("providers", Provider.serializer())
