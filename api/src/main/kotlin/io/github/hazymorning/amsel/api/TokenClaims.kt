package io.github.hazymorning.amsel.api

import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class TokenClaims(
    @SerialName("jti") val tokenId: String? = null,
    /** Epoch seconds. */
    val exp: Long? = null,
    @SerialName("is_long_lived") val isLongLived: Boolean = false,
) {
    public fun expiresWithin(seconds: Long, nowEpochSeconds: Long): Boolean =
        exp != null && exp - nowEpochSeconds < seconds

    public companion object {
        public fun parse(token: String): TokenClaims? {
            val payload = token.split('.').getOrNull(1) ?: return null
            // Bad base64 and JSON that is not the claims both throw IllegalArgumentException.
            return try {
                val json = String(Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '=')))
                MaJson.decodeFromString(serializer(), json)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}
