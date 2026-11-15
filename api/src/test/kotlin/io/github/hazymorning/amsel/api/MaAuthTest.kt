package io.github.hazymorning.amsel.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaAuthTest {
    private val auth = MaAuth(OkHttpClient())

    @Test fun `password sign in ends with a long-lived token named after the device`() = runBlocking {
        FakeMaServer { command, _ ->
            when (command) {
                "auth/login" -> listOf(FakeMaServer.recorded("2.10", "auth_login"))
                "auth" -> listOf(FakeMaServer.recorded("2.10", "auth"))
                "auth/token/create" -> listOf(FakeMaServer.result(JsonPrimitive("long-lived")))
                else -> emptyList()
            }
        }.use { fake ->
            val result = auth.signIn(fake.address, fake.serverId, "amsel", "amsel-test-pw", "Pixel 9")
            assertEquals(SignInResult.Success("long-lived"), result)
            val create = fake.received.first { it["command"]!!.jsonPrimitive.content == "auth/token/create" }
            assertEquals("Pixel 9", create["args"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `wrong password is reported plainly`() = runBlocking {
        FakeMaServer { _, _ -> listOf(FakeMaServer.recorded("2.10", "auth_login__wrong_password")) }.use { fake ->
            val result = auth.signIn(fake.address, fake.serverId, "amsel", "nope", "Pixel 9")
            assertEquals(SignInResult.WrongPassword, result)
        }
    }

    @Test fun `a locked account is not called a wrong password`() = runBlocking {
        FakeMaServer { _, _ ->
            listOf(
                FakeMaServer.result(
                    buildJsonObject {
                        put("success", false)
                        put("error", "Too many failed attempts. Please try again in 60 seconds.")
                        put("translation_key", "rate_limited")
                    },
                ),
            )
        }.use { fake ->
            assertEquals(
                SignInResult.TooManyAttempts,
                auth.signIn(fake.address, fake.serverId, "amsel", "right", "Pixel 9"),
            )
        }
    }

    @Test fun `probe lists sign-in methods`() = runBlocking {
        FakeMaServer { _, _ -> listOf(FakeMaServer.recorded("2.10", "auth_providers")) }.use { fake ->
            val probe = auth.probe(fake.address)
            assertEquals(65, probe.info.schemaVersion)
            assertTrue(probe.providers.any { it.providerType == "builtin" })
            assertEquals(false, probe.offersHomeAssistant)
        }
    }

    @Test fun `unreachable server fails without throwing`() = runBlocking {
        val result = auth.signIn(ServerAddress.parse("127.0.0.1:1")!!, "s1", "a", "b", "c")
        assertTrue(result is SignInResult.Failed)
    }

    @Test fun `the password goes only to the server that was probed`() = runBlocking {
        FakeMaServer { _, _ -> listOf(FakeMaServer.recorded("2.10", "auth_login")) }.use { fake ->
            // Another server now answers where the probed one did: it is told nothing.
            assertEquals(SignInResult.Failed, auth.signIn(fake.address, "probed", "amsel", "pw", "Pixel 9"))
            assertEquals(SignInResult.Failed, auth.exchangeToken(fake.address, "probed", "session", "Pixel 9"))
            assertTrue(fake.received.toString(), fake.received.isEmpty())
        }
    }

    @Test fun `who answers is read without sending anything`() = runBlocking {
        FakeMaServer { _, _ -> emptyList() }.use { fake ->
            assertEquals(fake.serverId, auth.serverAt(fake.address).serverId)
            assertTrue(fake.received.isEmpty())
        }
    }
}
