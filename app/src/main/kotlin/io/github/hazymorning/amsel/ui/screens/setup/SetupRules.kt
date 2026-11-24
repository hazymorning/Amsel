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

    /** The server has no users yet: it must be set up in its web UI first. */
    data object NeedsSetup : ServerCheck
}

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

/** Keystore and storage errors while keeping the token count as a failed sign-in. */
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
 * A name typed without a scheme went over https, as off the home network any DNS may answer for it;
 * plain http only when typed so, which [ServerError.HttpsFailed] asks for.
 */
internal fun ServerError.of(target: ServerCheckRequest): ServerError =
    if (this == ServerError.Unreachable && "://" !in target.field && target.address.baseUrl.isHttps) {
        ServerError.HttpsFailed
    } else {
        this
    }

internal fun hostOf(address: ServerAddress): String = address.baseUrl.host

/**
 * The host alone when [ServerAddress.parse] gives [address] back from it, else the whole address, so
 * editing it loses nothing.
 */
internal fun typedForm(address: ServerAddress): String {
    val host = hostOf(address)
    return if (ServerAddress.parse(host) == address) host else address.toString()
}

internal fun FoundServer.toUi(): FoundServerUi = FoundServerUi(info.serverId, info.name, hostOf(address))

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

/** Survives process death; never holds the password. */
@Serializable
internal data class SavedSetupState(
    val step: SetupStep,
    val serverStep: ServerStep,
    val address: String,
    val notFound: Boolean,
    val chosen: SelectedServer?,
    val username: String,
)

/** [connectingTo] shows progress meanwhile; [field] is the address field's text if the check fails. */
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

/** Null for the server search: it died with the process and starts again. */
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
