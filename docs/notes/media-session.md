# Media session for a remote speaker (M1 background-controls spike)

**Verdict: GO.** One Media3 1.11.1 `MediaLibraryService` with one `MediaLibrarySession`, whose player
is a `RemotePlayer : SimpleBasePlayer`, drives the notification, lock screen, volume keys, the system
volume panel and Android Auto for a speaker the phone is not playing on. **One surface is a no-go
on the platform: Bluetooth/wired headset buttons while the phone has never played audio itself.**
For that surface only, the plan's fallback applies (controls in the app and the notification, no
headphone buttons); it fixes itself once "This phone" has played (M3), because the platform session
stays the same across player swaps.

Prototype: `/tmp/media3-spike` (outside the repo), module `:remote`, AGP 9.5.0, Kotlin 2.4.21,
Gradle 9.8.2, compileSdk 37, minSdk 28, media3 1.11.1. `compileDebugKotlin` with no warnings,
`lintDebug` clean (opt-ins satisfied), 11 Robolectric tests (SDK 36) green.

**Evidence markers.** [src] read in source: `m3/` = Media3 1.11.1 sources jars from Google Maven
(`androidx/media3/{common,session}/`), `aosp/` = `frameworks/base` `main` on android.googlesource.com
(fetched 2026-11-01; shipped releases can differ), `ma/` = `/home/user/ref/server` @ `acea6207`.
[doc] developer.android.com, 2026-11-01. [test] proven by the spike's tests. [inf] inferred, check
on a device (checklist at the end). D1-D13 are the proposed decisions in
`docs/notes/official-app-lessons.md` §4.

## 1. What works, surface by surface

| Surface | Remote speaker | Why |
| --- | --- | --- |
| Notification, lock screen, Quick Settings media card | **Yes** | Media3 shows the notification when the timeline is non-empty and the state is not IDLE (`m3/MediaNotificationManager.java:377-400`), and holds a `mediaPlayback` FGS while `playWhenReady && (READY or BUFFERING)` (`:275-302`, `startForeground` `:533-542`). Local or remote makes no difference. After a pause it stays foreground 10 min (`m3/MediaSessionService.java:186`), the same 10 min the platform allows a non-playing session (`aosp/MediaSessionRecord.java:274`, `:1058-1095`). No `POST_NOTIFICATIONS` needed (comment at `m3/MediaNotificationManager.java:341-343`). [src] |
| Volume keys, screen off or another app in front | **Yes while the speaker plays** | Keys go to the first *active* session that `canHandleVolumeKey()` (`aosp/MediaSessionStack.java:329-339`); a remote session with non-fixed volume qualifies (`aosp/MediaSessionRecord.java:637-654`). Exception: if another app is playing audio locally, the local stream wins (`aosp/MediaSessionService.java:2553-2572`). [src] |
| Volume keys, Amsel in front, speaker paused | **Yes**, with `Activity.setMediaController(platform controller)` | `PhoneWindow` sends volume and media keys to the activity's session (`aosp/PhoneWindow.java:2022-2052`), which adjusts it with no active-state check; a fixed-volume session falls back to phone volume (`aosp/MediaSessionService.java:2239-2290`). Compiled helper: `VolumeKeys.kt`. [src] |
| System volume panel | **Yes** (remote slider) | Remote adjustments are broadcast to SystemUI (`aosp/MediaSessionRecord.java:452`, `:490`; `aosp/MediaSessionService.java:485-497`). The slider calls `setDeviceVolume`. The mute key does nothing: "Muting remote playback is not supported" (`aosp/MediaSessionRecord.java:421`). [src; panel look inf] |
| Output switcher chip on the media card | **Shows "Other device", disabled** | A remote session without a MediaRouter2 route gets that fallback (`aosp/MediaDeviceManager.kt:335-363`, string at SystemUI `strings.xml:1122`). The artist line names the speaker instead (D6). A `MediaRoute2ProviderService` that offered the speakers as routes was tried (2026.11.16 to 2026.11.22): Android listed them and held the routing session, and the chip on the owner's phone (Nothing, Android 16) still did not name the speaker, so it was taken out again. [src, device] |
| Headset / Bluetooth buttons | **No** until this phone has played audio | Keys go to the "media button session": the session of the app that most recently played audio *locally* (`aosp/MediaSessionStack.java:196-229`, `:252-276`; dispatch `aosp/MediaSessionService.java:2629-2663`). A phone that only remote-controls plays nothing, so the last music app gets them. After M3's LocalPlayer plays, our one session stays the media-button session until another app plays [inf]. Playing silence to win the keys is rejected (battery, focus fights, rule 1). [src] |
| Android Auto | **Yes** (browse and control) | Browsing and session commands do not depend on local audio. But the car hears the phone, so a home speaker in the car is useless: plays from the car go to This phone (D4) and M4 follows M3. [src; inf] |
| Background start | **Limited** | If a speaker starts while Amsel is in the background without an FGS, `startForegroundService` throws `ForegroundServiceStartNotAllowedException`; Media3 catches it, calls `MediaSessionService.Listener.onForegroundServiceStartNotAllowedException()` and posts **no** notification (`m3/MediaNotificationManager.java:318-333`, `:533-542`, `m3/MediaSessionService.java:907-940`). A tap on the notification, lock screen or a media key grants a 10 s FGS-start allowlist (`aosp/MediaSessionService.java:882-917`, `aosp/MediaSessionDeviceConfig.java:34`, `:45`), and `RemotePlayer`'s optimistic state flips to playing at once, so Media3 starts the FGS inside that window. [src] |

Platform rules checked: Android 14+ needs `foregroundServiceType="mediaPlayback"` plus
`FOREGROUND_SERVICE_MEDIA_PLAYBACK` (Media3's AAR adds neither; Media3 passes the type,
`m3/MediaNotificationManager.java:535-541`); Android 15 forbids starting a `mediaPlayback` FGS from
`BOOT_COMPLETED` [doc] (we never do); Android 16+ on `main` demotes a media FGS whose session left an
active state for 10 min, behind flag `enable_notifying_activity_manager_with_media_session_status_change`
(`aosp/MediaSessionService.java:698-830`) [src; which release ships it: inf]. Android 17 (API 37,
`CINNAMON_BUN` in `api-versions.xml`) adds two relevant rules for apps targeting 37 [doc
`about/versions/17/behavior-changes-17`, `/changes/bg-audio`]: `ACCESS_LOCAL_NETWORK` is mandatory
(already in the plan), and **background audio hardening**: playback, audio focus and volume APIs from
the background fail silently unless a foreground service with while-in-use capability runs.
`RemotePlayer` calls no audio API, so it is unaffected; LocalPlayer (M3) is (§4). Using `mediaPlayback`
for controlling remote playback matches its description "continue audio or video playback from the
background" and what Media3 Cast senders do [inf].

## 2. RemotePlayer design

**State** (`getState()`, from one immutable `SpeakerSnapshot` per server event):

| Media3 | From Music Assistant |
| --- | --- |
| playlist | `[current_item, next_item]` of the `PlayerQueue` (no extra request; D11 stays). `MediaItemData` uid and `mediaId` = `queue_item_id`. Drop `next` when it equals `current` (repeat one): duplicate uids throw (`m3/SimpleBasePlayer.java:600`) [test] |
| empty playlist, `STATE_IDLE` | no `current_item`, or speaker unknown (an empty playlist must be IDLE/ENDED, `:1009-1013`) |
| `STATE_READY` + `playWhenReady` | `state == playing`; paused/idle-with-item = READY + not playWhenReady (notification stays) |
| `STATE_BUFFERING` + `playWhenReady` | `extra_attributes.play_action_in_progress` (keeps the FGS while `play_media` runs) |
| position | `PositionSupplier { elapsed_time + (serverNow - elapsed_time_last_updated) }` while playing, clamped to duration; constant when paused. Server clock from the `time` command offset, as the web UI's `helpers/elapsed.ts` does. Queue timing first, then `current_media`, then the player's (`frontend activeElapsedTime.ts`). SimpleBasePlayer turns jumps over 1 s into discontinuities itself (`:2216`, `:4019-4078`) [test] |
| `DeviceInfo` | `PLAYBACK_TYPE_REMOTE`, min 0, max **100** if `volume_control != "none"`, else **0** (fixed) |
| device volume / muted | `group_volume`/`group_volume_muted` for groups and sync leaders, else `volume_level`/`volume_muted` |
| shuffle / repeat | `shuffle_enabled`, `repeat_mode` |
| `maxSeekToPreviousPositionMs` | 5000: MA restarts the track after 5 s (`ma/.../player_queues/controller.py:905-907`, same at 2.10.8 `:824-826`) |
| metadata | title, artist **"Artist · Kitchen"** (D6), album, `artworkUri` = `/imageproxy/<proxy_id>?size=512` on the connection base, duration, `MEDIA_TYPE_MUSIC` |
| commands | always `PLAY_PAUSE`, `SET_MEDIA_ITEM`, `GET_DEVICE_VOLUME`, `RELEASE` (an empty action mask made voice hosts ignore the official app); with a speaker: `GET_CURRENT_MEDIA_ITEM`, `GET_TIMELINE`, `GET_METADATA`, `SEEK_TO_MEDIA_ITEM`, shuffle, repeat; `seek` feature → `SEEK_IN_CURRENT_MEDIA_ITEM`; `next_previous` → the four next/previous commands; volume control → `SET_/ADJUST_DEVICE_VOLUME(_WITH_FLAGS)` |

**Commands** (`handle*`, each returns a future, see "optimism" below):

| Media3 entry point | MA command |
| --- | --- |
| `handleSetPlayWhenReady(true/false)` | `players/cmd/play` / `players/cmd/pause` (skipped for 2 s after a `play_media`, because Media3 calls `play()` right after `setMediaItems()`, `m3/MediaSessionLegacyStub.java:1117-1152`) |
| `handleSeek(_, _, COMMAND_SEEK_TO_NEXT*)` | `players/cmd/next` |
| `handleSeek(_, _, COMMAND_SEEK_TO_PREVIOUS*)` | `players/cmd/previous` (MA decides restart vs previous) |
| `handleSeek(_, pos, COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)` | `players/cmd/seek {position: pos/1000}` |
| `handleSeek(i, _, COMMAND_SEEK_TO_MEDIA_ITEM)` | `player_queues/play_index {index: queue_item_id}` |
| `handleSetDeviceVolume(v, _)` | `players/cmd/group_volume` (a lone speaker takes it as `volume_set`) |
| `handleIncrease/DecreaseDeviceVolume` (volume keys) | `players/cmd/group_volume_up` / `group_volume_down`: the server's adaptive step from the group's volume (`players/controller.py:1001-1035`) |
| `handleSetDeviceMuted` | `players/cmd/volume_mute` if `mute_control != "none"` |
| `handleSetShuffleModeEnabled` / `handleSetRepeatMode` | `player_queues/shuffle` / `player_queues/repeat` |
| `handleSetMediaItems` (Android Auto, voice) | `player_queues/play_media {option: "replace", media, start_item}` through the PlayDispatcher |

Verified mechanics: `BasePlayer.seekToNext()` is final and, when the window has no next item, calls
`seekTo(C.INDEX_UNSET, …, COMMAND_SEEK_TO_NEXT)` (`m3/BasePlayer.java:191-204`, `:405-411`), and
`SimpleBasePlayer.seekTo` still calls `handleSeek` (`m3/SimpleBasePlayer.java:2560-2585`). So the
two-item window loses nothing: the intent always reaches the server [test]. Platform skip actions
come from the commands alone, not the timeline (`m3/MediaSessionLegacyStub.java:1913-1930`, `:2027-2033`).

**Optimism.** While any `handle*` future is pending, `invalidateState()` is ignored
(`m3/SimpleBasePlayer.java:3172-3179`) and Media3 shows its placeholder (e.g. paused right away).
Each future completes when a new snapshot confirms the request, or after 3 s (1.5 s for volume), or
on error; then the real server state shows. So taps answer in under 100 ms without flicker, and an
unconfirmed command quietly reverts [test]. `play_media` completes once queued, never after
playback starts (that can take 30 s and would freeze updates).

**Volume.** Media3 builds the platform `VolumeProviderCompat` (FIXED/RELATIVE/ABSOLUTE from the
commands, max from `DeviceInfo`) only in `onDeviceInfoChanged` and on a player swap
(`m3/MediaSessionLegacyStub.java:1454`, `:1724-1731`, `:2076-2095`), not when commands change. Hence
max 0/100 above: a speaker gaining or losing volume control changes `DeviceInfo` and forces a
rebuild. The platform shows an optimistic +1 per key press (`aosp/MediaSessionRecord.java:430-438`)
until MA's value arrives. Coalesce slider drags in the real code (latest `volume_set` wins).

**Artwork.** `artworkUri` only; the session's default loader (DataSourceBitmapLoader, size-limited,
cached; `m3/MediaSession.java:2846-2865`) fetches it over the cleartext-allowed network config.

## 3. Service, swap and lifecycle

- **One session, swap the player.** `session.player = next` then `previous.release()`. `setPlayer`
  needs the same application looper (`m3/MediaSession.java:984-990`); Media3 then re-sends timeline,
  metadata, state and DeviceInfo to every controller (`m3/MediaSessionImpl.java:280-312`,
  `m3/MediaSessionLegacyStub.java:1423-1470`). A new `RemotePlayer` per selected speaker; no
  forwarding wrapper. The platform session keeps its identity, so routing earned by local audio stays.
- **Never pause a speaker on swipe-away.** Media3's default `onTaskRemoved` calls
  `pauseAllPlayersAndStopSelf()` when nothing plays or the FGS is not running
  (`m3/MediaSessionService.java:753-758`). With a remote player that pauses the kitchen for everyone.
  Override: `if (!isPlaybackOngoing || !player.isPlaying) stopSelf()`, nothing else.
- **Keep the last snapshot while reconnecting.** An empty timeline removes the notification and
  stops the FGS at once (`m3/MediaNotificationManager.java:197-200`, `:364-376`), and from the
  background it cannot come back. `:data` keeps the last speaker state until the socket is back or
  the user changes speaker. The socket stays open while the speaker plays; commands wait up to 10 s
  for it (D7). Two minutes without the socket (`Session.stale`), the snapshot rests where it was,
  not playing: the notification keeps its item, Media3 lets the FGS go 10 min later, and the socket
  closes 60 s after, unless the app is in front or This phone plays.
- **Never start the service by hand.** Media3 starts it with `startForegroundService(startSelfIntent)`
  when playback starts (`m3/MediaNotificationManager.java:534`).
- **No `MediaButtonReceiver`, no resumption.** Media3 treats a manifest receiver as "this app does
  playback resumption" (`m3/MediaSessionLegacyStub.java:203-205`); a stale card for a speaker
  someone else now uses would mislead. `onGetLibraryRoot` answers `ERROR_NOT_SUPPORTED` for
  `params.isRecent`.
- **Controllers.** The 1.11 default `onConnectAsync` gives trusted controllers all commands and
  others read access (`m3/MediaSession.java:2284-2298`); SystemUI gets the platform state
  (`m3/MediaSessionImpl.java:975-978`). `BrowseTree.onConnectAsync` gives trusted controllers and the
  car the library, less search, and every player command. Media3 knows the car by package name alone
  (`isAutoCompanionController`, `isAutomotiveController`), which any app can take where Android Auto
  or the car's media app is not installed, and which a legacy controller names itself before
  Android 13 (`ControllerInfo.isPackageNameVerified`). So a car counts when Android trusts it, or
  when its package is a system app installed under the controller's uid (`Cars`, once per uid).
  Android Auto from Play on Android 9 is no system app: it counts only while trusted, as an app with
  notification access is; its signing certificate is not checked.
- **Manifest** (`:playback`): the two FGS permissions; `<service android:exported="true"
  android:foregroundServiceType="mediaPlayback" tools:ignore="ExportedService">` with actions
  `androidx.media3.session.MediaLibraryService` and `android.media.browse.MediaBrowserService`.
- Small icon: the app overrides Media3's `media3_notification_small_icon` drawable.
  `setSessionActivity(PendingIntent)` with the app's launch intent opens Amsel from the
  notification, the lock-screen card and the Quick Settings player (built in beta 2).

## 4. Swapping to LocalPlayer (M3)

The phone is just another MA player with its own queue, so LocalPlayer reuses RemotePlayer's state
and command mapping for the phone's `player_id`, and differs only in its output: `DeviceInfo`
`PLAYBACK_TYPE_LOCAL`, no device-volume commands (Media3 then calls `setPlaybackToLocal`, keys move
phone media volume, D3), `USAGE_MEDIA` audio attributes, its own audio focus and "becoming noisy"
handling (SimpleBasePlayer does neither), the Sendspin client and sink, and wake + Wi-Fi locks.
Selecting "This phone" is the same `session.player = localPlayer`. **Android 17:** audio started in
the background without a while-in-use FGS fails silently [doc bg-audio], so Sendspin may only
produce audio inside the Media3 FGS that a user action started; a server-pushed start while Amsel
is in the background stays silent by design. Test with `adb shell cmd audio set-enable-hardening throw`.

## 5. Android Auto browse tree (M4)

`MediaLibrarySession.Callback` (compiled in `BrowseTree.kt`): root → **Recently played, Playlists,
Albums, Artists** (four tabs; honour `MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT`) → library rows
read live from the server (each read wakes a socket the idle close shut), at most 100 per page → an album or playlist's tracks; an artist's albums. Grid
for browsable, list for playable (`EXTRAS_KEY_CONTENT_STYLE_BROWSABLE/PLAYABLE`). A track row's
`mediaId` is `"<album or playlist uri>|<track uri>"`, which `handleSetMediaItems` turns into
`play_media(media=parent, start_item=track)`. `onAddMediaItems` must pass items through: the default
fails for items without a URI (`m3/MediaSession.java:1958-1966`).

## 6. Code sketch (condensed from the compiled `/tmp/media3-spike/remote/src/main/kotlin/io/github/hazymorning/amsel/playback/RemotePlayer.kt`)

```kotlin
@OptIn(UnstableApi::class)
class RemotePlayer(
    private val playerId: String,
    private val snapshots: StateFlow<SpeakerSnapshot?>,
    private val commands: SpeakerCommands,
    private val clock: ServerClock,
    private val onCommandFailed: (Throwable) -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var snapshot = snapshots.value

    init {
        scope.launch { snapshots.collect { snapshot = it; invalidateState() } }
    }

    override fun getState(): State {
        val s = snapshot
        val b = State.Builder()
            .setAvailableCommands(availableCommands(s))
            .setDeviceInfo(
                DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                    .setMaxVolume(if (s?.canSetVolume == true) 100 else 0).build(),
            )
            .setDeviceVolume(s?.volumeLevel ?: 0)
            .setIsDeviceMuted(s?.volumeMuted ?: false)
            .setMaxSeekToPreviousPositionMs(5_000)
        val current = s?.current ?: return b.setPlaybackState(Player.STATE_IDLE).build()
        val playing = s.state == MaPlaybackState.PLAYING
        val next = s.next?.takeIf { it.queueItemId != current.queueItemId } // repeat one
        return b.setPlaylist(listOfNotNull(itemData(current, s), next?.let { itemData(it, s) }))
            .setCurrentMediaItemIndex(0)
            .setPlayWhenReady(playing || s.playActionInProgress, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(if (s.playActionInProgress) Player.STATE_BUFFERING else Player.STATE_READY)
            .setContentPositionMs(
                if (!playing) {
                    PositionSupplier.getConstant((s.elapsedSeconds * 1000).toLong())
                } else {
                    PositionSupplier {
                        val delta = (clock.nowSeconds() - s.elapsedLastUpdated).coerceAtLeast(0.0)
                        ((s.elapsedSeconds + delta) * 1000).toLong() // clamped to duration in the file
                    }
                },
            )
            .setShuffleModeEnabled(s.shuffle)
            .setRepeatMode(s.repeat.toMedia3())
            .build()
    }

    // The file also skips play() for 2 s after a play_media (Media3 calls play() after setMediaItems).
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
        if (playWhenReady) {
            send({ it.state == MaPlaybackState.PLAYING || it.playActionInProgress }) { play(playerId) }
        } else {
            send({ it.state != MaPlaybackState.PLAYING }) { pause(playerId) }
        }

    // Called even for seeks Media3 "ignores" (mediaItemIndex == C.INDEX_UNSET): intent always arrives.
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val before = snapshot?.current?.queueItemId
        return when (seekCommand) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
                send({ it.current?.queueItemId != before }) { next(playerId) }
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                send({ it.current?.queueItemId != before || it.elapsedSeconds < 2 }) { previous(playerId) }
            COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> {
                val sec = (positionMs.coerceAtLeast(0) / 1000).toInt()
                send({ abs(it.elapsedSeconds - sec) < 2 }) { seek(playerId, sec) }
            }
            COMMAND_SEEK_TO_MEDIA_ITEM -> {
                val id = currentTimeline.getWindow(mediaItemIndex, Timeline.Window()).uid as String
                send({ it.current?.queueItemId == id }) { playIndex(playerId, id) }
            }
            else -> Futures.immediateVoidFuture()
        }
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        val level = deviceVolume.coerceIn(0, 100)
        return send({ it.volumeLevel == level }, 1_500) { setVolume(playerId, level) }
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        val before = snapshot?.volumeLevel ?: 0
        return send({ (it.volumeLevel ?: 0) > before }, 1_500) { volumeUp(playerId) }
    }
    // handleDecreaseDeviceVolume, handleSetDeviceMuted, handleSetShuffleModeEnabled and
    // handleSetRepeatMode follow the same pattern.

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long,
    ): ListenableFuture<*> {
        val request = BrowseIds.toPlayRequest(mediaItems.map { it.mediaId }, startIndex)
            ?: return Futures.immediateVoidFuture()
        return send(confirm = null) { playMedia(playerId, request) } // done once queued
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    // Completes on confirmation, timeout or error; until then Media3 shows its placeholder state.
    private fun send(
        confirm: ((SpeakerSnapshot) -> Boolean)?,
        timeoutMs: Long = 3_000,
        command: suspend SpeakerCommands.() -> Unit,
    ): ListenableFuture<*> {
        val done = SettableFuture.create<Unit>()
        scope.launch {
            try {
                commands.command()
                if (confirm != null) withTimeoutOrNull(timeoutMs) { snapshots.first { it != null && confirm(it) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onCommandFailed(e) // plain-sentence snackbar via :data
            } finally {
                done.set(Unit)
            }
        }
        return done
    }
}
```

APIs that compiled: `SimpleBasePlayer(Looper)`; `State.Builder` setters above plus
`setPlaylist(List<MediaItemData>)`; `MediaItemData.Builder(uid).setMediaItem/setDurationUs/setIsSeekable`;
`PositionSupplier { }` (SAM) and `PositionSupplier.getConstant`; every `handle*` above returning
`ListenableFuture<*>` (guava `SettableFuture`, no new dependency); `currentTimeline`;
`DeviceInfo.Builder(PLAYBACK_TYPE_REMOTE).setMinVolume/setMaxVolume`; `MediaLibrarySession.Builder(service,
player, callback)`; `session.player = …`; `setListener(MediaSessionService.Listener)`;
`isPlaybackOngoing`; `LibraryResult.ofItem/ofItemList/ofError(SessionError.ERROR_NOT_SUPPORTED)`
(lint rejects `LibraryResult.RESULT_ERROR_*` as `WrongConstant`); `LibraryParams.isRecent`;
`MediaSession.platformToken` and `Activity.mediaController`. All Media3 classes used are
`@UnstableApi`: annotate with `@androidx.annotation.OptIn(UnstableApi::class)` (lint, not the
compiler, enforces it). `COMMAND_SET_DEVICE_VOLUME`/`COMMAND_ADJUST_DEVICE_VOLUME` are deprecated
but still needed for older controllers: `@Suppress("DEPRECATION")`. The real `RemotePlayer.kt` came
to 348 lines after ktlint: split the snapshot-to-State mapping into its own file.

Tests that pass [test]: playing speaker maps to READY/remote/volume 40/"Artist 1 · Kitchen"; position
moves 7.5 s with the server clock and stays put when paused; next with no next item still sends
`next`; previous sends `previous`; volume key → `volume_up`, slider → `volume_set:25`; fixed volume
when not controllable; repeat-one duplicate does not crash; an unconfirmed pause reverts after 3 s;
a car play sends one `play_media` and no extra `play`; a Media3 `MediaController` on the session sees
`PLAYBACK_TYPE_REMOTE`, max 100, volume 40, and its `setDeviceVolume(55)` reaches the speaker.
(Robolectric's platform `MediaController.getPlaybackInfo()` returns null, so the platform side of
remote volume rests on the source reading above.)

## 7. Device checklist (M1 gate, Pixel on Android 16 or 17, speaker on the test server)

1. Start a speaker from Amsel, lock the phone: lock-screen card shows title, "Artist · Speaker", art,
   moving position; play/pause/next/previous work.
2. Screen off, speaker playing: volume keys change the speaker; the system panel shows a slider.
3. Speaker paused, Amsel in front: volume keys change the speaker (setMediaController).
4. Pause, wait 11 min: the notification stays, the FGS ends (`adb shell dumpsys activity services`);
   tap play: speaker resumes within a few seconds.
5. Swipe Amsel from recents while the speaker plays and while paused: the speaker never pauses.
6. Bluetooth headset, remote speaker selected, another music app played last: the buttons go to
   that app (the expected no-go). After M3: play on This phone, switch to a speaker, and the
   buttons should reach Amsel until another app plays audio [inf].
7. `adb shell dumpsys media_session`: our session is `PLAYBACK_TYPE_REMOTE`, volume control ABSOLUTE.

## 8. Decisions (proposed lines for `docs/DECISIONS.md`)

| Decision | Settled by |
| --- | --- |
| GO for remote background controls via Media3; headphone buttons for a remote-only phone use the plan's fallback (no headphone buttons), everything else ships | E (source), Q1 |
| One `MediaLibrarySession` for the service's life; selecting a speaker swaps in a new `RemotePlayer` with `setPlayer`, no forwarding wrapper | Q2 |
| Session timeline is `[current_item, next_item]` from the queue object, `queue_item_id` as uid and mediaId; no queue paging for the session | Q2, Q4 |
| Commands keep Media3's optimistic state until the server confirms, at most 3 s (volume 1.5 s) | Q3 |
| Volume keys use MA's `group_volume_up`/`group_volume_down`; the slider uses `group_volume`; `DeviceInfo` max 100, or 0 when the speaker has no volume control | Q1 |
| `maxSeekToPreviousPositionMs` = 5 s to match MA's restart rule; previous always goes to the server | Q1 |
| `onTaskRemoved` never pauses a player; it only stops the service when nothing plays | Q1 |
| No `MediaButtonReceiver` and no playback resumption | Q2 |
| While Amsel is in front, the activity routes volume keys to the session (`setMediaController`) | Q1 |
| Artwork through the session's default bitmap loader at `size=512` | Q2 |
| Keep the last speaker state during reconnects instead of an empty session | Q3 |
