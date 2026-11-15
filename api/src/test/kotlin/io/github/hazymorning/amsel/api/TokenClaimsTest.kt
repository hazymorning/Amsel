package io.github.hazymorning.amsel.api

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenClaimsTest {
    @Test fun `reads jti and expiry from a JWT`() {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"u","jti":"t1","exp":2000,"is_long_lived":true}""".toByteArray())
        val claims = TokenClaims.parse("h.$payload.s")!!
        assertEquals("t1", claims.tokenId)
        assertEquals(true, claims.expiresWithin(100, 1950))
        assertEquals(false, claims.expiresWithin(10, 1950))
    }
}
