package io.github.hazymorning.amsel.playback

import android.content.Context
import android.os.Build
import android.provider.Settings
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import io.github.hazymorning.amsel.api.ConnectionState
import io.github.hazymorning.amsel.api.Player
import io.github.hazymorning.amsel.api.ServerAddress
import io.github.hazymorning.amsel.api.ServerInfo
import io.github.hazymorning.amsel.api.localCleartext
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.music_assistant.sendspin.android.AndroidDecoderFactory
import io.music_assistant.sendspin.android.AudioTrackSink
import io.music_assistant.sendspin.android.ElapsedRealtimeClock
import io.music_assistant.sendspin.android.SharedPreferencesSendspinKeyStore
import io.music_assistant.sendspin.android.connectivityOnline
import io.music_assistant.sendspin.android.sendspinAudioDispatcher
import io.music_assistant.sendspin.api.AudioCodec
import io.music_assistant.sendspin.api.AudioPhase
import io.music_assistant.sendspin.api.DeviceInfo
import io.music_assistant.sendspin.api.Endpoint
import io.music_assistant.sendspin.api.FailureCause
import io.music_assistant.sendspin.api.LocalPlayerConfig
import io.music_assistant.sendspin.api.PlayerState
import io.music_assistant.sendspin.api.SendspinPlayer
import io.music_assistant.sendspin.api.WireVersion
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

internal fun sendspinPlayer(
    app: Context,
    okHttp: OkHttpClient,
    config: StateFlow<LocalPlayerConfig?>,
    approvePairing: suspend (String) -> Unit,
    versionName: String,
    debug: Boolean,
    scope: CoroutineScope,
): SendspinPlayer {
    if (!debug) Logger.setMinSeverity(Severity.Warn)
    val overTls = { (config.value?.endpoint as? Endpoint.WebSocket)?.url?.startsWith("wss:") == true }
    return io.music_assistant.sendspin.SendspinPlayer(
        config = config,
        deps = io.music_assistant.sendspin.api.SendspinDeps(
            sink = AudioTrackSink(app, ElapsedRealtimeClock),
            decoders = AndroidDecoderFactory(),
            keyStore = SharedPreferencesSendspinKeyStore(app),
            // Its own engine, so closing it cannot shut down the shared OkHttp dispatcher. The server
            // changes with the session, so whether a connection is plain http is read for each one.
            httpClient = HttpClient(
                OkHttp.create {
                    preconfigured = okHttp.newBuilder().pingInterval(5, TimeUnit.SECONDS).build()
                        .localCleartext { !overTls() }
                },
            ),
            online = connectivityOnline(app, scope),
            approvePairing = approvePairing,
            audioDispatcher = sendspinAudioDispatcher(),
            // Passes the server's built-in app-player check (silent pairing) without claiming to
            // be the official app.
            deviceInfo = DeviceInfo(
                productName = "Mobile Application",
                manufacturer = "Amsel",
                softwareVersion = versionName,
            ),
            clock = ElapsedRealtimeClock,
        ),
        scope = scope,
    )
}

/** On a [metered] network Opus first: a fraction of FLAC's data. */
internal fun sendspinConfig(
    address: ServerAddress,
    token: String,
    server: ServerInfo,
    deviceName: String,
    metered: Boolean,
) = LocalPlayerConfig(
    endpoint = Endpoint.WebSocket(address.sendspinUrl, wireFor(server), token),
    deviceName = deviceName,
    codecPreference = if (metered) OPUS_FIRST else FLAC_FIRST,
    bitDepth = 16,
    bufferSeconds = 10,
    userDelayMs = 0,
)

private val FLAC_FIRST = listOf(AudioCodec.FLAC, AudioCodec.OPUS)
private val OPUS_FIRST = listOf(AudioCodec.OPUS, AudioCodec.FLAC)

internal fun deviceName(app: Context): String =
    Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
        ?: Build.MODEL

/**
 * While the control socket reconnects, Sendspin keeps the [last] server and reconnects by itself, so a
 * Wi-Fi blip does not stop the audio; only if [retriesSafe], as its first frame is the token, which
 * over plain http on another network could reach whatever answers at a home address.
 */
internal fun sendspinServer(
    wanted: Boolean,
    connection: ConnectionState,
    last: ServerInfo?,
    retriesSafe: Boolean,
): ServerInfo? = when {
    !wanted -> null
    connection is ConnectionState.Connected -> connection.server
    connection is ConnectionState.Connecting || connection is ConnectionState.Offline -> last.takeIf { retriesSafe }
    else -> null
}

internal fun awakeFor(state: PlayerState, was: Boolean): Boolean = when (state) {
    is PlayerState.Connected -> state.audio.phase != AudioPhase.Idle
    is PlayerState.Reconnecting -> state.audio.phase != AudioPhase.Idle && !state.audio.starved
    is PlayerState.Connecting -> was
    else -> false
}

/**
 * The server lists a phone that left as unavailable, refusing commands, until it registers the player
 * again after the client is back (see docs/API-NOTES.md, "This phone (Sendspin)"). A failed client
 * joins only on a new connection, so the wait ends at once.
 */
internal fun joinedId(sendspin: PlayerState, players: Map<String, Player>, gone: Set<String>): String? {
    if (sendspin is PlayerState.Failed) {
        throw PhoneNotReadyException(refused = sendspin.cause != FailureCause.SetupFailed, stopped = true)
    }
    val connected = (sendspin as? PlayerState.Connected)?.playerId
    return connected?.takeIf { players[it]?.available == true && it !in gone }
}

/** The official app's rule. */
internal fun wireFor(server: ServerInfo): WireVersion {
    if (server.schemaVersion >= 96) return WireVersion.SPEC_1_0
    val patch = Regex("""^2\.10\.(\d+)""").find(server.serverVersion)?.groupValues?.get(1)?.toIntOrNull()
    return if (patch != null && patch >= 7) WireVersion.SPEC_1_0 else WireVersion.LEGACY_9
}
