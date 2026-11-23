package io.github.hazymorning.amsel.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** What a speaker plays now, whatever the source. */
@Serializable
public data class PlayerMedia(
    val uri: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val duration: Double? = null,
    @SerialName("queue_item_id") val queueItemId: String? = null,
    @SerialName("elapsed_time") val elapsedTime: Double? = null,
    @SerialName("elapsed_time_last_updated") val elapsedTimeLastUpdated: Double? = null,
)

/** A speaker. Its id is also its queue's id. */
@Serializable
public data class Player(
    @SerialName("player_id") val playerId: String,
    val name: String = "",
    val type: String = "player",
    val available: Boolean = true,
    val enabled: Boolean = true,
    @SerialName("hide_in_ui") val hideInUi: Boolean = false,
    @SerialName("private") val isPrivate: Boolean = false,
    @SerialName("needs_setup") val needsSetup: Boolean = false,
    @SerialName("playback_state") val playbackState: String? = null,
    /**
     * False when its power control says it is off, a Home Assistant entity linked in Music
     * Assistant too; its queue can still say it plays. Null when it has no power control.
     */
    val powered: Boolean? = null,
    @SerialName("volume_control") val volumeControl: String = "none",
    @SerialName("volume_level") val volumeLevel: Int? = null,
    @SerialName("volume_muted") val volumeMuted: Boolean? = null,
    /** A group's or sync leader's loudest member. For a speaker on its own it can be 0 whatever its level. */
    @SerialName("group_volume") val groupVolume: Int? = null,
    @SerialName("group_volume_muted") val groupVolumeMuted: Boolean? = null,
    /** A group's members, or the speakers synced to this one. */
    @SerialName("group_members") val groupMembers: List<String> = emptyList(),
    @SerialName("synced_to") val syncedTo: String? = null,
    @SerialName("active_group") val activeGroup: String? = null,
    @SerialName("active_source") val activeSource: String? = null,
    @SerialName("current_media") val currentMedia: PlayerMedia? = null,
    @SerialName("supported_features") val supportedFeatures: List<String> = emptyList(),
    /** The players it can play in step with, as a group's leader. */
    @SerialName("can_group_with") val canGroupWith: List<String> = emptyList(),
    /** When the server's sleep timer stops it, in Unix seconds; 2.11 on. */
    @SerialName("sleep_timer_expires_at") val sleepTimerExpiresAt: Double? = null,
) {
    public val shownName: String get() = name.ifBlank { playerId }

    public val isPlaying: Boolean get() = playbackState == PLAYING

    public val isOff: Boolean get() = powered == false

    /** Playing, or paused with something to go on with. */
    public val hasMusic: Boolean get() = playbackState == PLAYING || playbackState == PAUSED

    /** The level its volume control shows: a group's or sync leader's is its members' ([groupVolume]). */
    public val volume: Int? get() = if (groupMembers.isEmpty()) volumeLevel else groupVolume

    public val muted: Boolean get() = (if (groupMembers.isEmpty()) volumeMuted else groupVolumeMuted) == true

    public val hasVolume: Boolean get() = volumeControl != "none" && volume != null

    /** Shown in "Play on"; private players only when they are this phone. */
    public fun isShownSpeaker(ownPlayerId: String?): Boolean =
        enabled && !hideInUi && !needsSetup && type in SPEAKER_TYPES &&
            syncedTo == null && activeGroup == null && (!isPrivate || playerId == ownPlayerId)

    /**
     * In reach and free to play along with a speaker. A group player is not, nor a speaker one
     * runs (`active_group`): those groups are arranged in Music Assistant.
     */
    public val canPlayAlong: Boolean get() = available && enabled && type != GROUP && activeGroup == null

    /** On its own and able to take speakers along (`set_members`). */
    public val canLeadGroup: Boolean get() = canPlayAlong && syncedTo == null && SET_MEMBERS in supportedFeatures
}

/**
 * The speakers playing in step with [leaderId], by what each of them reports (`synced_to`): a 2.11
 * server can leave the leader's own `group_members` empty or out of date while its members say
 * where they belong.
 */
public fun Map<String, Player>.membersOf(leaderId: String): List<Player> =
    values.filter { it.syncedTo == leaderId && it.playerId != leaderId }

/**
 * The groups `set_members` can change, by leader, each with the speakers that say they play along
 * with it. Their word comes late: they say so one at a time, two seconds apart, on 2.10 and 2.11
 * alike [live]. The leader's own list is not read: it goes on naming a speaker for two seconds
 * after it left, and asking that one to leave again is an error [live].
 */
public fun Map<String, Player>.syncGroups(): Map<String, Set<String>> = buildMap<String, MutableSet<String>> {
    for (member in this@syncGroups.values) {
        val leader = member.syncedTo?.let(this@syncGroups::get) ?: continue
        if (member.canPlayAlong && leader.canLeadGroup) getOrPut(leader.playerId, ::mutableSetOf) += member.playerId
    }
}

private val SPEAKER_TYPES = setOf("player", "stereo_pair", GROUP)

private const val GROUP = "group"
private const val SET_MEMBERS = "set_members"

private const val PLAYING = "playing"
private const val PAUSED = "paused"

@Serializable
public data class QueueItem(
    @SerialName("queue_item_id") val queueItemId: String,
    val name: String = "",
    @Serializable(with = LenientIntSerializer::class) val duration: Int? = null,
    @SerialName("media_item") @Serializable(with = MediaItemOrNullSerializer::class) val mediaItem: MediaItem? = null,
    val image: MediaItemImage? = null,
)

/** A speaker's queue. [items] is a count. */
@Serializable
public data class PlayerQueue(
    @SerialName("queue_id") val queueId: String,
    val active: Boolean = false,
    val items: Int = 0,
    val state: String = "idle",
    @SerialName("shuffle_enabled") val shuffleEnabled: Boolean = false,
    @SerialName("repeat_mode") val repeatMode: RepeatMode = RepeatMode.Off,
    /** A mix the server keeps flowing: it always shuffles, and shuffle and repeat are refused. */
    @SerialName("is_dynamic") val isDynamic: Boolean = false,
    /** What the queue was filled from; a mix names itself here ([endlessMixOf]). */
    @Serializable(with = MappingListSerializer::class) val sources: List<ItemMapping> = emptyList(),
    @SerialName("current_index") val currentIndex: Int? = null,
    @SerialName("index_in_buffer") val indexInBuffer: Int? = null,
    @SerialName("elapsed_time") val elapsedTime: Double = 0.0,
    @SerialName("elapsed_time_last_updated") val elapsedTimeLastUpdated: Double? = null,
    /** How fast the position moves: 1.0, or a book's or episode's speed set in Music Assistant. */
    @SerialName("playback_speed") val playbackSpeed: Double = 1.0,
    @SerialName(
        "current_item",
    ) @Serializable(with = QueueItemOrNullSerializer::class) val currentItem: QueueItem? = null,
    @SerialName("next_item") @Serializable(with = QueueItemOrNullSerializer::class) val nextItem: QueueItem? = null,
    @SerialName("extra_attributes") val extraAttributes: JsonObject? = null,
) {
    public val isPlaying: Boolean get() = state == PLAYING

    /** True while the server is still starting a play action. */
    public val playActionInProgress: Boolean
        get() = (extraAttributes?.get("play_action_in_progress") as? JsonPrimitive)?.booleanOrNull ?: false
}

/** How a play action treats the speaker's Up next. */
public enum class QueueOption(public val api: String) {
    /** Replace Up next and play from the start: Play, Shuffle, tapping a track. */
    Replace("replace"),

    /** Insert after the current item: "Play next". */
    Next("next"),

    /** Append: "Add to Up next". */
    Add("add"),
}

/** How a queue repeats; a mode this app does not know reads as [Off]. */
@Serializable(with = RepeatModeSerializer::class)
public enum class RepeatMode(public val api: String) {
    Off("off"),
    One("one"),
    All("all"),
}
