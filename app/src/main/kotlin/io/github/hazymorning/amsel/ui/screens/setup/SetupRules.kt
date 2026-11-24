package io.github.hazymorning.amsel.ui.screens.setup

import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.api.Schema
import io.github.hazymorning.amsel.api.ServerAddress
import io.github.hazymorning.amsel.api.ServerInfo
import io.github.hazymorning.amsel.api.ServerProbe
import io.github.hazymorning.amsel.api.SignInResult
import io.github.hazymorning.amsel.api.isCertificateError
import io.github.hazymorning.amsel.data.FoundServer
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

internal sealed interface ServerCheck {
    data class Ready(val probe: ServerProbe) : ServerCheck

    data class Failed(val error: ServerError) : ServerCheck

    /** Music Assistant has no users yet: it must be set up in its web UI first. */
    data object NeedsSetup : ServerCheck
}

/**
 * Reads the server's info and sign-in methods. Unreachable covers anything that is not Music
 * Assistant answering, but for a certificate this phone does not trust; a server older than
 * [Schema.MIN_SUPPORTED] is too old.
 */
internal suspend fun checkServer(address: ServerAddress, probe: suspend (ServerAddress) -> ServerProbe): ServerCheck =
    try {
        val result = probe(address)
        if (Schema.isSupported(result.info.schemaVersion)) {
            ServerCheck.Ready(result)
        } else {
            ServerCheck.Failed(ServerError.TooOld)
        }
    } catch (e: MaCommandException) {
        if (e.isSetupRequired) ServerCheck.NeedsSetup else ServerCheck.Failed(ServerError.Unreachable)
    } catch (e: MaConnectionException) {
        ServerCheck.Failed(if (e.isCertificateError()) ServerError.Certificate else ServerError.Unreachable)
    } catch (e: SerializationException) {
        // Something answered, but not as Music Assistant.
        ServerCheck.Failed(ServerError.Unreachable)
    }

/** Runs a sign-in; a failure to keep the token (Keystore, storage) counts as not getting there. */
internal suspend fun attempt(block: suspend () -> SignInResult): SignInResult = try {
    block()
} catch (e: GeneralSecurityException) {
    SignInResult.Failed
} catch (e: ProviderException) {
    SignInResult.Failed
} catch (e: IOException) {
    SignInResult.Failed
}

/**
 * Why [target] was not reached. A name typed without a scheme went to https, as one off the home
 * network may answer any DNS; plain http to it only when typed so.
 */
internal fun ServerError.of(target: ServerCheckRequest): ServerError =
    if (this == ServerError.Unreachable && "://" !in target.field && target.address.baseUrl.isHttps) {
        ServerError.HttpsFailed
    } else {
        this
    }

/** For display: the host alone, never a scheme or port (`192.168.1.20`, `music.example.com`). */
internal fun hostOf(address: ServerAddress): String = address.baseUrl.host

/**
 * What a person would type for [address]: the host alone when the app's defaults (`http://`,
 * port 8095) lead back to it, otherwise the whole address, so editing it loses nothing.
 */
internal fun typedForm(address: ServerAddress): String {
    val host = hostOf(address)
    return if (ServerAddress.parse(host) == address) host else address.toString()
}

internal fun FoundServer.toUi(): FoundServerUi = FoundServerUi(info.serverId, info.name, hostOf(address))

/** A server address kept as the text it parses back from. */
private object ServerAddressSerializer : KSerializer<ServerAddress> {
    override val descriptor = PrimitiveSerialDescriptor("ServerAddress", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ServerAddress) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): ServerAddress =
        ServerAddress.fromBaseUrl(decoder.decodeString()) ?: throw SerializationException("Not a server address")
}

@Serializable
internal data class SelectedServer(
    @Serializable(with = ServerAddressSerializer::class) val address: ServerAddress,
    val info: ServerInfo,
    val fromList: Boolean,
    val offersHomeAssistant: Boolean,
) {
    fun signInState() = SignInUiState(info.name.orEmpty(), hostOf(address), offersHomeAssistant)
}

/** What outlives the process: the page, what was typed (never the password) and the chosen server. */
@Serializable
internal data class SavedSetupState(
    val step: SetupStep,
    val serverStep: ServerStep,
    val address: String,
    val notFound: Boolean,
    val chosen: SelectedServer?,
    val username: String,
)

/** What to check: the address, what shows progress meanwhile, and the field's text if it fails. */
internal data class ServerCheckRequest(
    val address: ServerAddress,
    val connectingTo: String,
    val field: String,
    val fromList: Boolean,
)

internal fun FoundServer.target(fromList: Boolean) =
    ServerCheckRequest(address, connectingTo = info.serverId, field = typedForm(address), fromList = fromList)

internal fun SetupUiState.kept(chosen: SelectedServer?) =
    SavedSetupState(step, server.step, server.address, server.notFound, chosen, signIn?.username.orEmpty())

/** The state [SavedSetupState] brings back; null for the server search, which ended with the process. */
internal fun SavedSetupState.restored(): SetupUiState? {
    val server = ServerUiState(serverStep, address = address, notFound = notFound)
    val signIn = chosen?.signInState()?.copy(username = username)
    return when {
        step == SetupStep.SignIn && signIn != null -> SetupUiState(step, server, signIn)
        step == SetupStep.Server && serverStep == ServerStep.Address -> SetupUiState(step, server)
        step == SetupStep.Server -> null
        else -> SetupUiState()
    }
}

internal val SignInResult.error: SignInError
    get() = when (this) {
        SignInResult.WrongPassword -> SignInError.WrongPassword
        SignInResult.TooManyAttempts -> SignInError.TooManyAttempts
        SignInResult.AccountDisabled -> SignInError.AccountDisabled
        else -> SignInError.Unreachable
    }
