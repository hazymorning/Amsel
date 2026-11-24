package io.github.hazymorning.amsel.data

import io.github.hazymorning.amsel.api.FakeMaServer
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.withFakeApi
import kotlinx.serialization.json.JsonObject

/** A command with neither an override nor a recorded fixture answers null. */
fun withRecordedServer(
    version: String = "2.10",
    overrides: (String, JsonObject?) -> List<JsonObject>? = { _, _ -> null },
    block: suspend (MaApi, FakeMaServer) -> Unit,
) = withFakeApi(version, { command, args -> overrides(command, args) ?: listOf(recordedFor(version, command)) }, block)

private fun recordedFor(version: String, command: String): JsonObject {
    val name = command.replace('/', '_')
    return runCatching { FakeMaServer.recorded(version, name) }.getOrElse { FakeMaServer.result(null) }
}
