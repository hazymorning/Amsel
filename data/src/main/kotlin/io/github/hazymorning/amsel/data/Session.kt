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

/** Where the app stands with its server, in the words the UI needs. */
sealed interface SessionState {
    /** The stored session is still being read. */
    data object Restoring : SessionState

    /** Nothing stored yet: show Setup. */
    data object NeedsSetup : SessionState

    /** Signed in and remembered, but not connected right now (background, or still connecting). */
    data class Waiting(val serverName: String?) : SessionState

    data class Ready(val server: ServerInfo, val user: MaUser?) : SessionState

    data class Offline(val serverName: String?) : SessionState

    /** The server is older than 2.10. */
    data object Unsupported : SessionState
}

/**
 * The app's connection lifecycle. The socket stays open while the app is in the foreground,
 * something plays or This phone is paused by a call, and closes 60 s after none is true. A command
 * or library read from outside the app (the notification, a headset, the car) [wake]s it: it opens
 * if closed and stays open for 60 s after the last such use. What only the server can say, that a
 * speaker plays or This phone holds music, keeps it open only until the server has been out of
 * reach for two minutes ([stale]); a new network, or the screen coming on ([tryAgain]), tries again.
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

    /** Sign-in on short-lived sockets: Setup probes a server and asks for Home Assistant's page here. */
    val auth = MaAuth(http)

    private val stored = MutableStateFlow<StoredSession?>(null)
    private val restored = MutableStateFlow(false)

    // Called from the main thread (activity, service) and from background work (car, phone).
    private val lock = Any()
    private val _foreground = MutableStateFlow(false)
    private var playing = false
    private var audible = false
    private var inCall = false
    private var holding = false
    private var idleJob: Job? = null
    private var rotation: Job? = null

    /** Forgetting a refused session and remembering a new one, one at a time. */
    private val changing = Mutex()

    /**
     * What the server last said of the speakers is out of date: the socket has been trying for two
     * minutes without connecting, and it stays so, closed too, until it connects.
     */
    val stale: StateFlow<Boolean> = scope.staleness(client.state, staleAfterMs)

    /**
     * Whether the socket is wanted for its own sake. Away from home a speaker that last played would
     * keep it retrying for hours, so the server's word counts only while it is current. Read with
     * [lock] held.
     */
    private val active: Boolean
        get() = _foreground.value || audible || inCall || (!stale.value && (playing || holding))

    /** Whether something would keep the socket if the server answered. Read with [lock] held. */
    private val wanted: Boolean
        get() = _foreground.value || audible || inCall || playing || holding

    /** The app is in front: what only its screens show loads then. */
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    private val _upToDate = MutableStateFlow(false)

    /**
     * What the server last said of the speakers still holds as far as the phone can tell: the
     * socket is connected, or was closed on purpose since, or a [readOnOwnSocket] read them since. False
     * before either, and once the socket or a glance could not reach the server.
     */
    val upToDate: StateFlow<Boolean> = _upToDate.asStateFlow()

    /** The remembered server's address; null while there is none. */
    val address: StateFlow<ServerAddress?> = stored.map { it?.address }.stateIn(scope, SharingStarted.Eagerly, null)

    private val _user = MutableStateFlow<MaUser?>(null)

    /** Who is signed in, as the server last said: kept while offline, forgotten on Sign out. */
    val user: StateFlow<MaUser?> = _user.asStateFlow()

    /**
     * The phone is on the network where the server last answered with its own id: there a home
     * address leads to the household's machines, so This phone's Sendspin may ride out a dropped
     * control socket.
     */
    val checkedHere: StateFlow<Boolean> get() = checked.here

    /** Artwork URLs on the connected address, or null before there is one. */
    val images: ImageUrls? get() = stored.value?.address?.let { ImageUrls(it) }

    val state: StateFlow<SessionState> = combine(restored, stored, client.state) { isRestored, session, conn ->
        when {
            !isRestored -> SessionState.Restoring

            session == null -> SessionState.NeedsSetup

            else -> when (conn) {
                is ConnectionState.Connected -> SessionState.Ready(conn.server, conn.user)

                ConnectionState.Offline -> SessionState.Offline(session.name)

                // The token was refused, or the server set up anew: it is forgotten (below), and
                // Setup signs in again.
                ConnectionState.SignedOut -> SessionState.NeedsSetup

                ConnectionState.Unsupported -> SessionState.Unsupported

                ConnectionState.Connecting, ConnectionState.Idle -> SessionState.Waiting(session.name)
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, SessionState.Restoring)

    init {
        // The server names the user on every connect, and its own name may have changed meanwhile.
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
                        // Shown for this run; the next connect stores it again.
                    }
                }
            }
        }
        // A refused token never works again: without it a restart or the idle close shows Setup,
        // not the old server, and the dead token is not sent again.
        scope.launch {
            client.state.filter { it == ConnectionState.SignedOut }.collect {
                changing.withLock {
                    // A sign-in that came first has replaced the refused session and started anew.
                    if (client.state.value != ConnectionState.SignedOut) return@withLock
                    try {
                        dropSession()
                    } catch (e: IOException) {
                        // Forgotten for this run; the next start is refused and forgets it again.
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
        // A socket that gave up, or waits out its backoff, may reach the server from the new network.
        scope.launch { network.current.drop(1).filterNotNull().collect { tryAgain() } }
    }

    /** The address and token of the current session, for the Sendspin socket ("This phone"). */
    fun credentials(): Pair<ServerAddress, String>? = stored.value?.let { it.address to it.token }

    /** Loads the remembered server and token. Call once at start-up. */
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

    /** The session's speaker plays, or is about to, as the server last said. */
    fun setPlaying(value: Boolean) = synchronized(lock) {
        playing = value
        reconcile()
    }

    /** This phone's own audio plays, or is about to, whichever speaker the session holds. */
    fun setAudible(value: Boolean) = synchronized(lock) {
        audible = value
        reconcile()
    }

    /**
     * This phone holds music, paused: its player stays on the server, as the official app keeps it.
     * Once the phone's Sendspin client leaves, the server drops its queue and later brings it back
     * from a save without playback progress (2.10.9 and 2.11 live): a book started over.
     */
    fun setHolding(value: Boolean) = synchronized(lock) {
        holding = value
        reconcile()
    }

    /** This phone was paused by a call: its music resumes after, so its player must stay on the server. */
    fun setInCall(value: Boolean) = synchronized(lock) {
        inCall = value
        reconcile()
    }

    /**
     * Something outside the app needs the server now (a transport command from the notification
     * or a headset, a car library read): open the socket if it is closed, and close it 60 s after
     * the last such call unless the app is in front or something plays. Before the stored session
     * is restored (a cold start by the car), it waits for the restore.
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
     * Retries an unreachable server at once instead of waiting out the backoff (Retry, a granted
     * permission, the network coming back), or one that was too old and may have been updated. A
     * socket that closed while the server was out of reach ([stale]) opens again too: the phone may
     * be home, and what the speakers do is known once it connects.
     */
    fun reconnect() {
        synchronized(lock) {
            val session = stored.value ?: return
            val state = client.state.value
            val closedStale = state == ConnectionState.Idle && stale.value
            if (state != ConnectionState.Offline && state != ConnectionState.Unsupported && !closedStale) return
            client.start(session.address, session.token, session.serverId)
            // A network that keeps coming and going must not put off the close for ever.
            if (!active && idleJob == null) armIdleClose()
        }
    }

    /**
     * A look at the server from outside the app while its socket is closed, for a widget: on a
     * socket of its own, so nothing the app does on a connect (the library, Home, a new token) comes
     * with it, closed again after [block]. Null when nobody is signed in or the server does not
     * answer in time, which [upToDate] then says.
     */
    suspend fun <T : Any> readOnOwnSocket(block: suspend (MaApi) -> T): T? {
        restored.first { it }
        val session = stored.value ?: return null
        val own = MaClient(http, scope)
        own.start(session.address, session.token, session.serverId)
        val result = try {
            // One try: a glance does not wait out the backoff of a server that is not there.
            val answer = withTimeoutOrNull(GLANCE_WAIT_MS) {
                own.state.first { it != ConnectionState.Idle && it != ConnectionState.Connecting }
            }
            if (answer is ConnectionState.Connected) block(MaApi(own)) else null
        } catch (e: MaException) {
            null
        } finally {
            own.stop()
        }
        // The app's own socket, connected meanwhile, knows better.
        if (client.state.value !is ConnectionState.Connected) _upToDate.value = result != null
        return result
    }

    /**
     * The screen came on or a network came: a socket something wants (a speaker heard playing, This
     * phone's music) that gave up or waits out its backoff tries at once. Wanted by nothing, it
     * stays closed, away from home too; a widget's look finds out what plays.
     */
    fun tryAgain() {
        if (synchronized(lock) { wanted }) reconnect()
    }

    /**
     * Whether plain http reaches the household's network from where the phone is now: the server
     * answered as itself over plain http on this network. Where that is not known yet, the server
     * is asked for its first frame, which tells whoever answers instead nothing.
     */
    suspend fun reachesHome(): Boolean {
        val session = stored.value ?: return false
        if (session.address.baseUrl.isHttps) return false
        return checked.confirm { orNullOnFailure { auth.serverAt(session.address) }?.serverId == session.serverId }
    }

    /** Signs in with username and password and remembers the server. */
    suspend fun signIn(address: ServerAddress, info: ServerInfo, username: String, password: String): SignInResult {
        val result = auth.signIn(address, info.serverId, username, password, deviceName)
        if (result is SignInResult.Success) remember(address, info, result.token)
        return result
    }

    /** Finishes "Sign in with Home Assistant" with the session token from the callback URL. */
    suspend fun completeHomeAssistant(address: ServerAddress, info: ServerInfo, code: String): SignInResult {
        val result = auth.exchangeToken(address, info.serverId, code, deviceName)
        if (result is SignInResult.Success) remember(address, info, result.token)
        return result
    }

    /**
     * Forgets the session at once (the app shows Setup immediately, even offline), then tells the
     * server best effort: `auth/logout` revokes the token only when a connection is already up. A
     * token that cannot be forgotten (an IOException) leaves the person signed in.
     */
    suspend fun signOut() {
        val wasConnected = client.state.value is ConnectionState.Connected
        dropSession()
        try {
            if (wasConnected) {
                try {
                    api.logout(3.seconds)
                } catch (e: MaException) {
                    // Signed out here all the same; the server keeps the token until it expires.
                }
            }
        } finally {
            synchronized(lock) { client.stop() }
        }
    }

    /** Forgets the stored token, once a token rotation on its way has settled, then the session. */
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
            // A connection that stopped as signed out or unsupported starts over with the new token.
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

    /** (Re)starts the 60 s countdown to closing the socket. Call with [lock] held. */
    private fun armIdleClose() {
        idleJob?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(idleCloseMs)
            synchronized(lock) {
                // A newer countdown, or the app coming back, has taken over meanwhile.
                if (idleJob != coroutineContext[Job] || active) return@synchronized
                client.stop()
                idleJob = null
            }
        }
        idleJob = job
        job.start()
    }

    /** Long-lived tokens last 365 days and never renew: replace one that has under 30 days left. */
    private suspend fun rotateIfNeeded(session: StoredSession) {
        val claims = TokenClaims.parse(session.token) ?: return
        if (!claims.isLongLived || !claims.expiresWithin(ROTATE_WITHIN_S, System.currentTimeMillis() / 1000)) return
        // A failure leaves the old token in place, and the next connect tries again.
        try {
            val fresh = api.createToken(deviceName) ?: return
            if (!keep(fresh)) {
                // Never to be used: revoked rather than left live for a year.
                api.revokeToken(fresh)
                return
            }
            // Matched by the token alone: a renamed server updates the stored session meanwhile.
            var swapped = false
            stored.update { current ->
                swapped = current?.token == session.token
                if (swapped) current?.copy(token = fresh) else current
            }
            if (!swapped) return
            // Revoking closes every socket on the old token, so reconnect with the new one first.
            synchronized(lock) { client.start(session.address, fresh, session.serverId) }
            // Not revoked, the old token lapses by itself within the 30 days it had left.
            api.revokeToken(session.token)
        } catch (e: MaException) {
            return
        }
    }

    /** Seals and stores [token]; false when it could not be. */
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
        /** The server the token belongs to, which alone is sent it. */
        val serverId: String,
    )

    private companion object {
        const val IDLE_CLOSE_MS = 60_000L

        /** Well past a Wi-Fi blip, through which This phone's Sendspin keeps its server. */
        const val STALE_AFTER_MS = 120_000L

        /** A server at home answers within a second; one that is away is not waited for long. */
        const val GLANCE_WAIT_MS = 10_000L
        const val ROTATE_WITHIN_S = 30L * 24 * 3600
    }
}
