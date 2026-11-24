package io.github.hazymorning.amsel.data

import io.github.hazymorning.amsel.api.MaJson
import io.github.hazymorning.amsel.api.fixture
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserPermissionsTest {
    private val scopes = MaJson.decodeFromJsonElement(
        MapSerializer(String.serializer(), ListSerializer(String.serializer())),
        fixture("2.10", "auth_scopes")["response"]!!.jsonObject["result"]!!,
    )

    @Test fun `admins and users may change the library`() {
        assertTrue(UserPermissions.of("admin", scopes).canWriteLibrary)
        assertTrue(UserPermissions.of("user", scopes).canWriteLibrary)
    }

    @Test fun `guests and unknown roles may not`() {
        assertFalse(UserPermissions.of("guest", scopes).canWriteLibrary)
        assertFalse(UserPermissions.of("custom-role-we-do-not-know", scopes).canWriteLibrary)
        assertFalse(UserPermissions.of(null, scopes).canWriteLibrary)
    }
}
