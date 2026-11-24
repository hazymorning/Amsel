package io.github.hazymorning.amsel.data

import io.github.hazymorning.amsel.api.ConnectionState
import io.github.hazymorning.amsel.api.ImageUrls
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MaAuth
import io.github.hazymorning.amsel.api.MaClient
import io.github.hazymorning.amsel.api.MaException
import io.github.hazymorning.amsel.api.MaUser
import io.github.hazymorning.amsel.api.ServerAddress
import io.github.hazymorning.amsel.api.ServerInfo
import io.github.hazymorning.amsel.api.SignInResult
import io.github.hazymorning.amsel.api.TokenClaims
import io.github.hazymorning.amsel.api.orNullOnFailure
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

sealed interface SessionState {
    data object Restoring : SessionState

    data object NeedsSetup : SessionState

    /** Signed in; the socket is closed while unused, or still connecting. */
    data class Waiting(val serverName: String?) : SessionState

    data class Ready(val server: ServerInfo, val user: MaUser?) : SessionState

    data class Offline(val serverName: String?) : SessionState

    data object Unsupported : SessionState
}

/**
 * The socket stays open while the app is in the foreground, This phone is audible or paused by a call, or, until
 * [stale], a speaker plays or This phone holds paused music; it closes 60 s after none of these holds.
 */
class Session(
    private val prefs: PrefsStore,
    private val vault: TokenCipher,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val deviceName: String,
    network: PhoneNetwork,
    private val idleCloseMs: Long = IDLE_CLOSE_MS,
    staleAfterMs: Long = STALE_AFTER_MS,
) {
    private val checked = CheckedNetwork(network, prefs, scope)
    val client = MaClient(http, scope, sameNetwork = checked::isHere)
    val api = MaApi(client)

    val auth = MaAuth(http)

    private val stored = MutableStateFlow<StoredSession?>(null)
    private val restored = MutableStateFlow(false)

    // The setters run on the main thread (activity, service) and on background threads (Android Auto, This phone).
    private val lock = Any()
    private val _foreground = MutableStateFlow(false)
    private var playing = false
    private var audible = false
    private var inCall = false
    private var holding = false
    private var idleJob: Job? = null
    private var rotation: Job? = null

    // Serialises dropping a refused session and remembering a new one.
    private val changing = Mutex()

    /** True after two minutes of failed connecting; stays true while the socket is closed, until it connects. */
    val stale: StateFlow<Boolean> = scope.staleness(client.state, staleAfterMs)

    // Away from home a speaker last reported playing would keep the socket retrying for hours, so the server's
    // report counts only until [stale]. Read with [lock] held.
    private val active: Boolean
        get() = _foreground.value || audible || inCall || (!stale.value && (playing || holding))

    // [active] without the [stale] check. Read with [lock] held.
    private val wanted: Boolean
        get() = _foreground.value || audible || inCall || playing || holding

    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    private val _upToDate = MutableStateFlow(false)

    /**
     * True while connected, after a deliberate close, and after a [readOnOwnSocket] that got an answer; false
     * before any of these and after a failed connect or read.
     */
    val upToDate: StateFlow<Boolean> = _upToDate.asStateFlow()

    val address: StateFlow<ServerAddress?> = stored.map { it?.address }.stateIn(scope, SharingStarted.Eagerly, null)

    private val _user = MutableStateFlow<MaUser?>(null)

    /** Kept while offline; cleared on sign-out. */
    val user: StateFlow<MaUser?> = _user.asStateFlow()

    /**
     * On the network where the server last passed the `server_id` check: only there does This phone's Sendspin
     * reconnect over plain http by itself. See docs/API-NOTES.md, "HTTP routes the app uses".
     */
    val checkedHere: StateFlow<Boolean> get() = checked.here

    val images: ImageUrls? get() = stored.value?.address?.let { ImageUrls(it) }

    val state: StateFlow<SessionState> = combine(restored, stored, client.state) { isRestored, session, conn ->
        when {
            !isRestored -> SessionState.Restoring

            session == null -> SessionState.NeedsSetup

            else -> when (conn) {
                is ConnectionState.Connected -> SessionState.Ready(conn.server, conn.user)

                ConnectionState.Offline -> SessionState.Offline(session.name)

                // A refused token or a server set up anew: init drops the session, and Setup signs in again.
                ConnectionState.SignedOut -> SessionState.NeedsSetup

                ConnectionState.Unsupported -> SessionState.Unsupported

                ConnectionState.Connecting, ConnectionState.Idle -> SessionState.Waiting(session.name)
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, SessionState.Restoring)

    init {
        scope.launch {
            client.state.filterIsInstance<ConnectionState.Connected>().collect { connected ->
                _user.value = connected.user
                val session = stored.value ?: return@collect
                checked.passed()
                if (connected.server.name != session.name) {
                    stored.value = session.copy(name = connected.server.name)
                    try {
                        prefs.saveServerName(connected.server.name)
                    } catch (e: IOException) {
                        // Kept in memory for this run; the next connect saves it again.
                    }
                }
            }
        }
        // A refused token never works again: once it is dropped, a restart or the idle close shows Setup, and
        // it is not sent again.
        scope.launch {
            client.state.filter { it == ConnectionState.SignedOut }.collect {
                changing.withLock {
                    // A sign-in that got the lock first has already replaced the refused session.
                    if (client.state.value != ConnectionState.SignedOut) return@withLock
                    try {
                        dropSession()
                    } catch (e: IOException) {
                        // Dropped in memory only; the next start is refused again and drops it again.
                        forget()
                    }
                    synchronized(lock) { client.stop() }
                }
            }
        }
        scope.launch { stale.collect { synchronized(lock) { reconcile() } } }
        scope.launch {
            client.state.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> _upToDate.value = true

                    ConnectionState.Offline, ConnectionState.SignedOut, ConnectionState.Unsupported ->
                        _upToDate.value = false

                    ConnectionState.Idle, ConnectionState.Connecting -> Unit
                }
            }
        }
        // A socket that gave up or waits out its backoff may reach the server from the new network.
        scope.launch { network.current.drop(1).filterNotNull().collect { tryAgain() } }
    }

    fun credentials(): Pair<ServerAddress, String>? = stored.value?.let { it.address to it.token }

    /** Call once, at start-up. */
    suspend fun restore() {
        val p = prefs.current()
        val address = p.serverUrl?.let { ServerAddress.fromBaseUrl(it) }
        val token = p.sealedToken?.let { withContext(Dispatchers.IO) { vault.open(it) } }
        if (address != null && token != null && p.serverId != null) {
            stored.value = StoredSession(address, p.serverName, token, p.serverId)
            checked.restore(prefs.checkedNetwork())
        }
        restored.value = true
        synchronized(lock) { reconcile() }
    }

    fun setForeground(value: Boolean) = synchronized(lock) {
        _foreground.value = value
        reconcile()
    }

    /** The session's speaker plays or is about to, as the server reports; counts only until [stale]. */
    fun setPlaying(value: Boolean) = synchronized(lock) {
        playing = value
        reconcile()
    }

    /** This phone's own audio plays or is about to, whichever speaker the session controls. */
    fun setAudible(value: Boolean) = synchronized(lock) {
        audible = value
        reconcile()
    }

    /**
     * This phone holds paused music: its player must stay on the server, which drops a departed Sendspin client's
     * queue and restores it later at an older position. See docs/API-NOTES.md, "This phone (Sendspin)".
     */
    fun setHolding(value: Boolean) = synchronized(lock) {
        holding = value
        reconcile()
    }

    /** Paused by a call: the music resumes after it, so This phone's player must stay on the server. */
    fun setInCall(value: Boolean) = synchronized(lock) {
        inCall = value
        reconcile()
    }

    /**
     * For a caller outside the app (notification, headset, Android Auto): opens the socket, which closes 60 s
     * after the last call unless something else keeps it. Called before [restore] (a cold start), it waits for it.
     */
    fun wake() {
        if (!restored.value) {
            scope.launch {
                restored.first { it }
                wake()
            }
            return
        }
        synchronized(lock) {
            val session = stored.value ?: return
            startIfIdle(session)
            if (!active) armIdleClose()
        }
    }

    /**
     * Retries now instead of after the backoff: an Offline socket, an Unsupported one (the server may have been
     * updated), or one closed while [stale], as the phone may be home again.
     */
    fun reconnect() {
        synchronized(lock) {
            val session = stored.value ?: return
            val state = client.state.value
            val closedStale = state == ConnectionState.Idle && stale.value
            if (state != ConnectionState.Offline && state != ConnectionState.Unsupported && !closedStale) return
            client.start(session.address, session.token, session.serverId)
            // Not re-armed while armed: a network that keeps coming and going must not postpone the close for ever.
            if (!active && idleJob == null) armIdleClose()
        }
    }

    /**
     * Runs [block] on a socket of its own, so none of the app's connect work (library, Home, token rotation)
     * comes with it. Null when nobody is signed in or the server does not answer in time; sets [upToDate].
     */
    suspend fun <T : Any> readOnOwnSocket(block: suspend (MaApi) -> T): T? {
        restored.first { it }
        val session = stored.value ?: return null
        val own = MaClient(http, scope)
        own.start(session.address, session.token, session.serverId)
        val result = try {
            // One attempt: a read for the widget does not wait out the backoff of an absent server.
            val answer = withTimeoutOrNull(GLANCE_WAIT_MS) {
                own.state.first { it != ConnectionState.Idle && it != ConnectionState.Connecting }
            }
            if (answer is ConnectionState.Connected) block(MaApi(own)) else null
        } catch (e: MaException) {
            null
        } finally {
            own.stop()
        }
        // If the app's own socket connected meanwhile, its state is newer.
        if (client.state.value !is ConnectionState.Connected) _upToDate.value = result != null
        return result
    }

    /**
     * On screen-on or a new network: reconnects a socket that something would keep, even when [stale]. Otherwise
     * it stays closed; the widget's [readOnOwnSocket] finds out what plays.
     */
    fun tryAgain() {
        if (synchronized(lock) { wanted }) reconnect()
    }

    /**
     * Whether plain http reaches the household's network from here: the server passed the `server_id` check over
     * plain http on this network. Where that is unknown, only the server's first frame is read, which sends no secret.
     */
    suspend fun reachesHome(): Boolean {
        val session = stored.value ?: return false
        if (session.address.baseUrl.isHttps) return false
        return checked.confirm { orNullOnFailure { auth.serverAt(session.address) }?.serverId == session.serverId }
    }

    suspend fun signIn(address: ServerAddress, info: ServerInfo, username: String, password: String): SignInResult {
        val result = auth.signIn(address, info.serverId, username, password, deviceName)
        if (result is SignInResult.Success) remember(address, info, result.token)
        return result
    }

    /** [code] is the Music Assistant session token from the callback URL, not an OAuth code. */
    suspend fun completeHomeAssistant(address: ServerAddress, info: ServerInfo, code: String): SignInResult {
        val result = auth.exchangeToken(address, info.serverId, code, deviceName)
        if (result is SignInResult.Success) remember(address, info, result.token)
        return result
    }

    /**
     * Drops the session at once, even offline, then sends `auth/logout` only over a connection already up.
     * Throws IOException when the stored token cannot be cleared; the user stays signed in then.
     */
    suspend fun signOut() {
        val wasConnected = client.state.value is ConnectionState.Connected
        dropSession()
        try {
            if (wasConnected) {
                try {
                    api.logout(3.seconds)
                } catch (e: MaException) {
                    // Signed out locally all the same; the server keeps the token until it expires.
                }
            }
        } finally {
            synchronized(lock) { client.stop() }
        }
    }

    // Cancels a running token rotation first, so it cannot store a new token after the clear.
    private suspend fun dropSession() {
        synchronized(lock) { rotation.also { rotation = null } }?.cancelAndJoin()
        prefs.clearToken()
        forget()
        checked.forget()
    }

    private fun forget() {
        stored.value = null
        _user.value = null
    }

    private suspend fun remember(address: ServerAddress, info: ServerInfo, token: String) = changing.withLock {
        val sealed = withContext(Dispatchers.IO) { vault.seal(token) }
        checked.forget()
        prefs.saveServer(address.toString(), info.serverId, info.name, sealed)
        stored.value = StoredSession(address, info.name, token, info.serverId)
        synchronized(lock) {
            // A client stopped as SignedOut or Unsupported starts over with the new token.
            client.stop()
            reconcile()
        }
    }

    /** Call with [lock] held. */
    private fun reconcile() {
        val session = stored.value ?: return
        if (active) {
            idleJob?.cancel()
            idleJob = null
            startIfIdle(session)
        } else if (idleJob == null && client.state.value != ConnectionState.Idle) {
            armIdleClose()
        }
    }

    /** Call with [lock] held. */
    private fun startIfIdle(session: StoredSession) {
        if (client.state.value != ConnectionState.Idle) return
        client.start(session.address, session.token, session.serverId)
        rotation = scope.launch { rotateIfNeeded(session) }
    }

    /** Call with [lock] held. */
    private fun armIdleClose() {
        idleJob?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(idleCloseMs)
            synchronized(lock) {
                // A newer countdown replaced this one, or the socket became active meanwhile.
                if (idleJob != coroutineContext[Job] || active) return@synchronized
                client.stop()
                idleJob = null
            }
        }
        idleJob = job
        job.start()
    }

    // Long-lived tokens last 365 days and never renew: replace one with under 30 days left.
    // See docs/API-NOTES.md, "Tokens".
    private suspend fun rotateIfNeeded(session: StoredSession) {
        val claims = TokenClaims.parse(session.token) ?: return
        if (!claims.isLongLived || !claims.expiresWithin(ROTATE_WITHIN_S, System.currentTimeMillis() / 1000)) return
        // A failure leaves the old token in place, and the next connect tries again.
        try {
            val fresh = api.createToken(deviceName) ?: return
            if (!keep(fresh)) {
                // Not stored, so never used: revoke it rather than leave it valid for a year.
                api.revokeToken(fresh)
                return
            }
            // Matched by the token alone: a server rename may have updated the stored session meanwhile.
            var swapped = false
            stored.update { current ->
                swapped = current?.token == session.token
                if (swapped) current?.copy(token = fresh) else current
            }
            if (!swapped) return
            // Revoking closes every socket on the old token, so reconnect with the new one first.
            synchronized(lock) { client.start(session.address, fresh, session.serverId) }
            // If this fails, the old token expires within the 30 days it had left.
            api.revokeToken(session.token)
        } catch (e: MaException) {
            return
        }
    }

    private suspend fun keep(token: String): Boolean = try {
        prefs.saveToken(withContext(Dispatchers.IO) { vault.seal(token) })
        true
    } catch (e: GeneralSecurityException) {
        false
    } catch (e: ProviderException) {
        false
    } catch (e: IOException) {
        false
    }

    private data class StoredSession(
        val address: ServerAddress,
        val name: String?,
        val token: String,
        // The token is sent only to the server with this id.
        val serverId: String,
    )

    private companion object {
        const val IDLE_CLOSE_MS = 60_000L

        // Well past a short Wi-Fi drop, which This phone's Sendspin survives.
        const val STALE_AFTER_MS = 120_000L

        // A reachable server answers within a second; an unreachable one is not waited for long.
        const val GLANCE_WAIT_MS = 10_000L
        const val ROTATE_WITHIN_S = 30L * 24 * 3600
    }
}
