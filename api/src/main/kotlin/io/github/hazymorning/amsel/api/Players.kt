package io.github.hazymorning.amsel.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Whatever the speaker plays, from its queue or an external source. */
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

/** [playerId] is also its queue's id. */
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
     * False when its power control says off, a Home Assistant entity linked in Music Assistant too;
     * its queue can still say playing. True when it has no power control.
     */
    val powered: Boolean? = null,
    @SerialName("volume_control") val volumeControl: String = "none",
    @SerialName("volume_level") val volumeLevel: Int? = null,
    @SerialName("volume_muted") val volumeMuted: Boolean? = null,
    /** A group's or sync leader's loudest member; a lone speaker can send 0 whatever its level. */
    @SerialName("group_volume") val groupVolume: Int? = null,
    @SerialName("group_volume_muted") val groupVolumeMuted: Boolean? = null,
    @SerialName("group_members") val groupMembers: List<String> = emptyList(),
    @SerialName("synced_to") val syncedTo: String? = null,
    @SerialName("active_group") val activeGroup: String? = null,
    @SerialName("active_source") val activeSource: String? = null,
    @SerialName("current_media") val currentMedia: PlayerMedia? = null,
    @SerialName("supported_features") val supportedFeatures: List<String> = emptyList(),
    @SerialName("can_group_with") val canGroupWith: List<String> = emptyList(),
    /** Unix seconds; 2.11 on. */
    @SerialName("sleep_timer_expires_at") val sleepTimerExpiresAt: Double? = null,
) {
    public val shownName: String get() = name.ifBlank { playerId }

    public val isPlaying: Boolean get() = playbackState == PLAYING

    public val isOff: Boolean get() = powered == false

    public val hasMusic: Boolean get() = playbackState == PLAYING || playbackState == PAUSED

    public val volume: Int? get() = if (groupMembers.isEmpty()) volumeLevel else groupVolume

    public val muted: Boolean get() = (if (groupMembers.isEmpty()) volumeMuted else groupVolumeMuted) == true

    public val hasVolume: Boolean get() = volumeControl != "none" && volume != null

    public fun isShownSpeaker(ownPlayerId: String?): Boolean =
        enabled && !hideInUi && !needsSetup && type in SPEAKER_TYPES &&
            syncedTo == null && activeGroup == null && (!isPrivate || playerId == ownPlayerId)

    /** Group players, and speakers one runs (`active_group`), are grouped in Music Assistant, not here. */
    public val canPlayAlong: Boolean get() = available && enabled && type != GROUP && activeGroup == null

    public val canLeadGroup: Boolean get() = canPlayAlong && syncedTo == null && SET_MEMBERS in supportedFeatures
}

/** By each member's `synced_to`: 2.11 can leave the leader's `group_members` empty or stale. */
public fun Map<String, Player>.membersOf(leaderId: String): List<Player> =
    values.filter { it.syncedTo == leaderId && it.playerId != leaderId }

/**
 * From the members' `synced_to`, reported one at a time seconds apart; the leader's list names a
 * speaker for 2 s after it left, and asking it to leave again is an error. See docs/API-NOTES.md,
 * "Player fields used".
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

/** [items] is a count, not the items. */
@Serializable
public data class PlayerQueue(
    @SerialName("queue_id") val queueId: String,
    val active: Boolean = false,
    val items: Int = 0,
    val state: String = "idle",
    @SerialName("shuffle_enabled") val shuffleEnabled: Boolean = false,
    @SerialName("repeat_mode") val repeatMode: RepeatMode = RepeatMode.Off,
    /** Shuffle is forced on, and changing shuffle or repeat is refused (error 12). */
    @SerialName("is_dynamic") val isDynamic: Boolean = false,
    @Serializable(with = MappingListSerializer::class) val sources: List<ItemMapping> = emptyList(),
    @SerialName("current_index") val currentIndex: Int? = null,
    @SerialName("index_in_buffer") val indexInBuffer: Int? = null,
    @SerialName("elapsed_time") val elapsedTime: Double = 0.0,
    @SerialName("elapsed_time_last_updated") val elapsedTimeLastUpdated: Double? = null,
    /** The rate the position moves at: 1.0, or a book's or episode's speed set in Music Assistant. */
    @SerialName("playback_speed") val playbackSpeed: Double = 1.0,
    @SerialName(
        "current_item",
    ) @Serializable(with = QueueItemOrNullSerializer::class) val currentItem: QueueItem? = null,
    @SerialName("next_item") @Serializable(with = QueueItemOrNullSerializer::class) val nextItem: QueueItem? = null,
    @SerialName("extra_attributes") val extraAttributes: JsonObject? = null,
) {
    public val isPlaying: Boolean get() = state == PLAYING

    public val playActionInProgress: Boolean
        get() = (extraAttributes?.get("play_action_in_progress") as? JsonPrimitive)?.booleanOrNull ?: false
}

public enum class QueueOption(public val api: String) {
    Replace("replace"),

    Next("next"),

    Add("add"),
}

@Serializable(with = RepeatModeSerializer::class)
public enum class RepeatMode(public val api: String) {
    Off("off"),
    One("one"),
    All("all"),
}
