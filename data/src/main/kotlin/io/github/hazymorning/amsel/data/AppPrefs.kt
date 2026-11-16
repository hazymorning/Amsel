package io.github.hazymorning.amsel.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.hazymorning.amsel.data.downloads.AbsAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// A corrupt file starts over (Setup again) instead of failing every launch.
private val Context.store: DataStore<Preferences> by preferencesDataStore(
    name = "amsel",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** The Audiobookshelf server and name signed in with last, to sign in there again. */
fun Prefs.lastAbsAccount(): AbsAccount? = absLastUrl?.let { AbsAccount(it, absLastUser.orEmpty()) }

/** The handful of things the app remembers. Settings stay this small on purpose (rule 4). */
data class Prefs(
    val serverUrl: String? = null,
    val serverId: String? = null,
    val serverName: String? = null,
    /** "system", "light" or "dark". */
    val theme: String = "system",
    /** The speaker the user last picked; null means "This phone". */
    val speakerId: String? = null,
    /** Its name, shown until the server lists the speaker again. */
    val speakerName: String? = null,
    /** "This phone"'s Music Assistant player id (its Sendspin public key), once known. */
    val phonePlayerId: String? = null,
    /** The session's token, sealed (see [TokenVault]). */
    val sealedToken: String? = null,
    /** The sleep timer last chosen, in minutes. */
    val sleepMinutes: Int = 30,
    /** How long the sound fades before the sleep timer stops it; 0 for no fade. */
    val sleepFadeSec: Int = 30,
    /** A shake sets a running sleep timer back to its full length. */
    val sleepShake: Boolean = true,
    /** Every start of playback sets the sleep timer to [sleepMinutes]. */
    val sleepOnPlay: Boolean = false,
    /** Opening the app picks the speaker that plays (a group's leader), else This phone. */
    val followPlaying: Boolean = false,
    /** The speakers following the music leaves out; none at first, so it follows every one. */
    val followSkipped: Set<String> = emptySet(),
    /** Above this level a speaker plays louder only after Amsel asked; 0 for no limit. */
    val volumeLimit: Int = 0,
    /** Each start of the app asks GitHub for a newer version. */
    val lookForUpdates: Boolean = false,
    /** Downloads wait for Wi-Fi (or another network that costs nothing per byte). */
    val downloadOnWifi: Boolean = true,
    /** The Audiobookshelf server downloads come from, and who signed in there. */
    val absUrl: String? = null,
    val absUser: String? = null,
    /** The last Audiobookshelf server and name signed in with, kept after signing out to sign in again. */
    val absLastUrl: String? = null,
    val absLastUser: String? = null,
    /** The tips that are not shown again, and the day (since the epoch) the last one shown left. */
    val tipsSeen: Set<String> = emptySet(),
    val tipRestDay: Long? = null,
    /** How often in a row each kind of hint went untaken; one passed up three times is not shown again. */
    val hintPasses: Map<String, Int> = emptyMap(),
    /** The speaker last picked other than This phone, to go back to at home. */
    val homeSpeakerId: String? = null,
    /** The playlist a song was last added to, and its name. */
    val lastPlaylistKey: String? = null,
    val lastPlaylistName: String? = null,
    /** When the sleep timer was last offered (ms since the epoch): once a night. */
    val sleepOfferedAt: Long? = null,
    /** The places the bar shows, in its order, by their ids; null for the bar as it comes. */
    val barPlaces: List<String>? = null,
    /** The mini player's controls in their order, those left out marked; null for the defaults. */
    val dockControls: List<String>? = null,
    /** What the Library shows, as chips, in their order, those left out marked; null for the defaults. */
    val libraryKinds: List<String>? = null,
    /** The kinds of hint the person turned off; [HINTS_ALL] among them turns every one off. */
    val hintsOff: Set<String> = emptySet(),
)

class AppPrefs(context: Context, scope: CoroutineScope) {
    private val store = context.applicationContext.store
    private val stored = store.data.map { p ->
        val unset = Prefs()
        Prefs(
            serverUrl = p[SERVER_URL],
            serverId = p[SERVER_ID],
            serverName = p[SERVER_NAME],
            theme = p[THEME] ?: unset.theme,
            speakerId = p[SPEAKER],
            speakerName = p[SPEAKER_NAME],
            phonePlayerId = p[PHONE_PLAYER],
            sealedToken = p[TOKEN],
            sleepMinutes = p[SLEEP_MINUTES] ?: unset.sleepMinutes,
            sleepFadeSec = p[SLEEP_FADE] ?: unset.sleepFadeSec,
            sleepShake = p[SLEEP_SHAKE] ?: unset.sleepShake,
            sleepOnPlay = p[SLEEP_ON_PLAY] ?: unset.sleepOnPlay,
            followPlaying = p[FOLLOW_PLAYING] ?: unset.followPlaying,
            followSkipped = p[FOLLOW_SKIPPED].orEmpty(),
            volumeLimit = p[VOLUME_LIMIT] ?: unset.volumeLimit,
            lookForUpdates = p[LOOK_FOR_UPDATES] ?: unset.lookForUpdates,
            downloadOnWifi = p[DOWNLOAD_ON_WIFI] ?: unset.downloadOnWifi,
            absUrl = p[ABS_URL],
            absUser = p[ABS_USER],
            absLastUrl = p[ABS_LAST_URL],
            absLastUser = p[ABS_LAST_USER],
            tipsSeen = p[TIPS_SEEN].orEmpty(),
            tipRestDay = p[TIP_REST_DAY],
            hintPasses = p[HINT_PASSES].orEmpty().lines().mapNotNull { line ->
                val (kind, count) = line.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                count.toIntOrNull()?.let { kind to it }
            }.toMap(),
            homeSpeakerId = p[HOME_SPEAKER],
            lastPlaylistKey = p[LAST_PLAYLIST],
            lastPlaylistName = p[LAST_PLAYLIST_NAME],
            sleepOfferedAt = p[SLEEP_OFFERED_AT],
            barPlaces = p[BAR_PLACES]?.split(',')?.filter { it.isNotBlank() },
            dockControls = p[DOCK_CONTROLS]?.split(',')?.filter { it.isNotBlank() },
            libraryKinds = p[LIBRARY_KINDS]?.split(',')?.filter { it.isNotBlank() },
            hintsOff = p[HINTS_OFF].orEmpty(),
        )
    }

    /** Read once at start and kept, so a screen opens on what is stored; null until then. */
    val prefs: StateFlow<Prefs?> = stored.stateIn(scope, SharingStarted.Eagerly, null)

    /** What is stored now, every finished edit included. */
    suspend fun current(): Prefs = stored.first()

    suspend fun saveServer(url: String, id: String, name: String?, sealedToken: String) {
        store.edit {
            // Speakers belong to their server.
            if (it[SERVER_ID] != id) {
                it.remove(SPEAKER)
                it.remove(SPEAKER_NAME)
            }
            it[SERVER_URL] = url
            it[SERVER_ID] = id
            if (name != null) it[SERVER_NAME] = name else it.remove(SERVER_NAME)
            it[TOKEN] = sealedToken
        }
    }

    suspend fun saveServerName(name: String?) {
        store.edit { if (name != null) it[SERVER_NAME] = name else it.remove(SERVER_NAME) }
    }

    suspend fun saveToken(sealedToken: String) {
        store.edit { it[TOKEN] = sealedToken }
    }

    suspend fun clearToken() {
        store.edit { it.remove(TOKEN) }
    }

    /** Where the server last answered as itself ([NetworkKey.text]): kept out of [Prefs], as no screen reads it. */
    suspend fun checkedNetwork(): String? = store.data.first()[CHECKED_NETWORK]

    suspend fun saveCheckedNetwork(text: String?) {
        store.edit { if (text != null) it[CHECKED_NETWORK] = text else it.remove(CHECKED_NETWORK) }
    }

    suspend fun setTheme(theme: String) {
        store.edit { it[THEME] = theme }
    }

    suspend fun setBarPlaces(ids: List<String>) {
        store.edit { it[BAR_PLACES] = ids.joinToString(",") }
    }

    suspend fun setDockControls(ids: List<String>) {
        store.edit { it[DOCK_CONTROLS] = ids.joinToString(",") }
    }

    suspend fun setLibraryKinds(ids: List<String>) {
        store.edit { it[LIBRARY_KINDS] = ids.joinToString(",") }
    }

    suspend fun setHintsOff(kinds: Set<String>) {
        store.edit { it[HINTS_OFF] = kinds }
    }

    suspend fun setSleepMinutes(minutes: Int) {
        store.edit { it[SLEEP_MINUTES] = minutes }
    }

    suspend fun setSleepFade(seconds: Int) {
        store.edit { it[SLEEP_FADE] = seconds }
    }

    suspend fun setVolumeLimit(level: Int) {
        store.edit { it[VOLUME_LIMIT] = level }
    }

    suspend fun setSleepShake(on: Boolean) {
        store.edit { it[SLEEP_SHAKE] = on }
    }

    suspend fun setSleepOnPlay(on: Boolean) {
        store.edit { it[SLEEP_ON_PLAY] = on }
    }

    /** Turned on, following takes in every speaker again. */
    suspend fun setFollowPlaying(on: Boolean) {
        store.edit {
            it[FOLLOW_PLAYING] = on
            if (on) it.remove(FOLLOW_SKIPPED)
        }
    }

    suspend fun setFollowSkipped(ids: Set<String>) {
        store.edit { it[FOLLOW_SKIPPED] = ids }
    }

    suspend fun setLookForUpdates(on: Boolean) {
        store.edit { it[LOOK_FOR_UPDATES] = on }
    }

    suspend fun setSpeaker(id: String?, name: String? = null) {
        store.edit {
            if (id == null) it.remove(SPEAKER) else it[SPEAKER] = id
            if (id == null || name == null) it.remove(SPEAKER_NAME) else it[SPEAKER_NAME] = name
        }
    }

    suspend fun setPhonePlayerId(id: String) {
        store.edit { it[PHONE_PLAYER] = id }
    }

    suspend fun setDownloadOnWifi(on: Boolean) {
        store.edit { it[DOWNLOAD_ON_WIFI] = on }
    }

    /** The Audiobookshelf refresh token, sealed: kept out of [Prefs], which screens read. */
    suspend fun absToken(): String? = store.data.first()[ABS_TOKEN]

    suspend fun saveAbs(url: String, user: String, sealedToken: String) {
        store.edit {
            it[ABS_URL] = url
            it[ABS_USER] = user
            it[ABS_TOKEN] = sealedToken
            it[ABS_LAST_URL] = url
            it[ABS_LAST_USER] = user
        }
    }

    suspend fun saveAbsToken(sealedToken: String) {
        store.edit { it[ABS_TOKEN] = sealedToken }
    }

    suspend fun clearAbs() {
        store.edit {
            it.remove(ABS_URL)
            it.remove(ABS_USER)
            it.remove(ABS_TOKEN)
        }
    }

    /** Whether the download of the book or episode at [uri] was offered already: each is offered once. */
    suspend fun downloadOffered(uri: String): Boolean = uri in store.data.first()[DOWNLOADS_OFFERED].orEmpty().lines()

    suspend fun setDownloadOffered(uri: String) {
        store.edit {
            val before = it[DOWNLOADS_OFFERED].orEmpty().lines().filter { line -> line.isNotEmpty() && line != uri }
            it[DOWNLOADS_OFFERED] = (before + uri).takeLast(OFFERS_KEPT).joinToString("\n")
        }
    }

    /** Hint [kind] went untaken [passes] times in a row (0 once taken). */
    suspend fun setHintPasses(kind: String, passes: Int) {
        store.edit {
            val others = it[HINT_PASSES].orEmpty().lines().filterNot { line ->
                line.isEmpty() || line.startsWith("$kind=")
            }
            it[HINT_PASSES] = (others + "$kind=$passes").joinToString("\n")
        }
    }

    suspend fun setHomeSpeaker(id: String) {
        store.edit { it[HOME_SPEAKER] = id }
    }

    suspend fun setLastPlaylist(key: String, name: String) {
        store.edit {
            it[LAST_PLAYLIST] = key
            it[LAST_PLAYLIST_NAME] = name
        }
    }

    suspend fun setSleepOffered(atMs: Long) {
        store.edit { it[SLEEP_OFFERED_AT] = atMs }
    }

    /** Tip [id] is not shown again; with a [restDay], no other tip is shown on that day. */
    suspend fun setTipSeen(id: String, restDay: Long?) {
        store.edit {
            it[TIPS_SEEN] = it[TIPS_SEEN].orEmpty() + id
            if (restDay != null) it[TIP_REST_DAY] = restDay
        }
    }

    private companion object {
        /** More than anyone has in progress; the oldest are forgotten. */
        const val OFFERS_KEPT = 300
        val DOWNLOADS_OFFERED = stringPreferencesKey("downloads_offered")
        val SERVER_URL = stringPreferencesKey("server_url")
        val SERVER_ID = stringPreferencesKey("server_id")
        val SERVER_NAME = stringPreferencesKey("server_name")
        val THEME = stringPreferencesKey("theme")
        val BAR_PLACES = stringPreferencesKey("bar_places")
        val DOCK_CONTROLS = stringPreferencesKey("dock_controls")
        val LIBRARY_KINDS = stringPreferencesKey("library_kinds")
        val HINTS_OFF = stringSetPreferencesKey("hints_off")
        val SPEAKER = stringPreferencesKey("speaker_id")
        val SPEAKER_NAME = stringPreferencesKey("speaker_name")
        val PHONE_PLAYER = stringPreferencesKey("phone_client_id")
        val TOKEN = stringPreferencesKey("token")
        val CHECKED_NETWORK = stringPreferencesKey("checked_network")
        val SLEEP_MINUTES = intPreferencesKey("sleep_minutes")
        val SLEEP_FADE = intPreferencesKey("sleep_fade")
        val SLEEP_SHAKE = booleanPreferencesKey("sleep_shake")
        val SLEEP_ON_PLAY = booleanPreferencesKey("sleep_on_play")
        val FOLLOW_PLAYING = booleanPreferencesKey("follow_playing")
        val FOLLOW_SKIPPED = stringSetPreferencesKey("follow_skipped")
        val VOLUME_LIMIT = intPreferencesKey("volume_limit")
        val LOOK_FOR_UPDATES = booleanPreferencesKey("look_for_updates")
        val DOWNLOAD_ON_WIFI = booleanPreferencesKey("download_on_wifi")
        val ABS_URL = stringPreferencesKey("abs_url")
        val ABS_USER = stringPreferencesKey("abs_user")
        val ABS_LAST_URL = stringPreferencesKey("abs_last_url")
        val ABS_LAST_USER = stringPreferencesKey("abs_last_user")
        val ABS_TOKEN = stringPreferencesKey("abs_token")
        val TIPS_SEEN = stringSetPreferencesKey("tips_seen")
        val TIP_REST_DAY = longPreferencesKey("tip_rest_day")
        val HINT_PASSES = stringPreferencesKey("hint_passes")
        val HOME_SPEAKER = stringPreferencesKey("home_speaker_id")
        val LAST_PLAYLIST = stringPreferencesKey("last_playlist")
        val LAST_PLAYLIST_NAME = stringPreferencesKey("last_playlist_name")
        val SLEEP_OFFERED_AT = longPreferencesKey("sleep_offered_at")
    }
}

/** In [Prefs.hintsOff]: every hint is off, whatever the others say. */
const val HINTS_ALL = "all"
