# Lessons from the official Music Assistant app

What the official app (`music-assistant/mobile-app`, Kotlin Multiplatform, Apache-2.0) does for the
protocol and Android platform areas Amsel shares with it, what to copy in spirit, and what to avoid.
Written for the M1 (remote, media session) and M4 (Android Auto) work. Protocol details that
`docs/API-NOTES.md` already covers are only referenced here.

**Source.** `/home/user/ref/mobile-app` at commit `d4a7256c` (2026-10-31, shallow). Abbreviations:
`ca/` = `composeApp/src/commonMain/kotlin/io/music_assistant/client/`,
`aa/` = `androidApp/src/main/kotlin/io/music_assistant/client/`,
`sk/` = `sendspin-kmp/src/androidMain/kotlin/io/music_assistant/sendspin/android/`,
`.claude/` = the app's own notes, `srv/` = `/home/user/ref/server` at `dev` `acea6207` (checked
against tag `2.10.8` where stated), `m3/` = Media3 1.11.1 `media3-session-1.11.1-sources.jar` from
Google Maven, package `androidx/media3/session/`.
**Markers.** Plain statements were read in source, with file:line. **[inf]** = inferred; check it
before relying on it.

## 0. The short version

1. **Their protocol layer is good; copy its rules, not its size.** One RPC table keyed by
   `message_id`, partial-result joining, fail every pending call when the socket drops, re-run `auth`
   on every reconnect, decode failures never crash. `ca/api/` is 4,256 lines, about 2,300 without
   the request builders, WebRTC transport and deep links (`ca/api/KtorServiceClient.kt` alone is
   1,281). Much of that is WebRTC and race repair we do not need.
2. **Their media session is legacy `MediaSessionCompat`, not Media3**, written by a hand-rolled
   singleton shared by two services. It reports *local* playback even for a remote speaker, so the
   volume keys never reach the speaker. They decided that on purpose ("not and won't be available").
   Media3's `DeviceInfo(PLAYBACK_TYPE_REMOTE)` solves exactly this (verified in `m3/`, §1.1).
3. **Headphone buttons for remote speakers are not handled.** Nothing in the code or notes claims they
   work. Our M1 spike must answer that on a device.
4. **Android Auto only ever plays on the phone** in their app, by design.
5. **They never call `auth/token/create`.** They keep the `auth/login` session token, which the server
   caps at 30 days sliding and 90 days absolute (`srv/music_assistant/controllers/webserver/auth.py:92-95`,
   same at 2.10.8 `:75-79`). So their users sign in again at least every 90 days.
6. **Their OAuth return scheme cannot be ours.** The server accepts no custom scheme except
   `musicassistant://` (`srv/music_assistant/helpers/redirect_validation.py:19-26`, same at 2.10.8),
   and that scheme belongs to their app. Use the WebView flow in `docs/API-NOTES.md` §3.4.
7. **Correction to PLAN.md:** the Android decoder and `AudioTrack` sink no longer live in `player/local/`.
   They are inside the module now: `sk/AudioTrackSink.kt`, `sk/AndroidDecoders.kt` (§2.12).

## 1. The two questions

### 1.1 How their media session works while a remote speaker plays

| Piece | What they do | Where |
| --- | --- | --- |
| Start trigger | `MainActivity` observes `isAnythingPlaying` (any listed player in `PLAYING`, remote included) and calls `startForegroundService(MainMediaPlaybackService)`. It catches `ForegroundServiceStartNotAllowedException` and logs "will retry when foregrounded". The Android Auto service does the same while it is bound. | `aa/MainActivity.kt:57-75`, `aa/services/AndroidAutoPlaybackService.kt:291-312`, `ca/data/MainDataSource.kt:314-318` |
| Service | `MainMediaPlaybackService : MediaBrowserServiceCompat`, `foregroundServiceType="mediaPlayback"`. It calls `startForeground` at once with a placeholder notification, then again on every content change with `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`. | `aa/services/MainMediaPlaybackService.kt:96-158`, `:239-253`; manifest `:126-129` |
| Stop rule | Stops only when **no** player has a current queue item (`doesAnythingHavePlayableItem`), or on swipe-away from recents while nothing plays. A paused remote speaker with a queue therefore keeps a foreground service and an ongoing notification indefinitely. | `MainMediaPlaybackService.kt:134-139`, `:255-264`; `MainDataSource.kt:319-323` |
| Session | One `MediaSessionCompat` owned by `SharedMediaSessionManager`, a Koin singleton, reference-counted by both services (`acquire`/`release`). It is the session's only writer and feeds from `nowPlayingPlayer`: the selected player if it has a current item, else the first playing one, else the first eligible one. | `aa/services/SharedMediaSessionManager.kt:141-160`, `:263-288`; `MainDataSource.kt:389-394`, `:1845-1848` |
| "Player" | No `Player` implementation. A `MediaSessionCompat.Callback` maps `onPlay`, `onPause` and the skip calls to `PlayerAction`s, which become `players/cmd/*` for whichever player the session presents. | `SharedMediaSessionManager.kt:479-578` |
| Playback type | `setPlaybackToLocal(AudioManager.STREAM_MUSIC)`, even for a remote speaker. There is no `VolumeProviderCompat`, `setPlaybackToRemote` or `DeviceInfo` anywhere (grep of the repo). | `SharedMediaSessionManager.kt:322` |
| Volume keys | The system handles them as phone media volume. In the app, every key press while a remote player is selected shows a toast: "Volume buttons only work for the local player". Their user docs say hardware volume for other players "is not and won't be available". | `aa/MainActivity.kt:92-99`, `ca/ui/compose/home/MainNavRoot.kt:254-266`, `composeResources/values/strings.xml:348`, `docs/app-documentation/players-pager.md:84` |
| Notification | Hand-built `NotificationCompat` with `MediaStyle(sessionToken)`, its own previous/play/next actions as broadcasts to receivers registered `RECEIVER_NOT_EXPORTED`, `FOREGROUND_SERVICE_IMMEDIATE`, and the artist line suffixed "(on <player>)" for a remote player. Below API 33 SystemUI draws only what the notification carries, so they repost on each track or play-state change. | `aa/services/MediaNotificationManager.kt:32-118`, `MainMediaPlaybackService.kt:199-230`, `SharedMediaSessionManager.kt:686-692` |
| Headphone buttons | No special handling. `onMediaButtonEvent` only turns the car's wheel keys into seeks for audiobooks. Every other key goes to the default compat handling, which calls `onPlay`/`onPause`/skip, and those act on the presented player, remote included. No `MediaButtonReceiver` is declared, so no resumption after process death. | `SharedMediaSessionManager.kt:526-535`, `:585-594` |

**Do headphone buttons work for a remote speaker?** Only if Android routes the key to this session,
and nothing in their code or notes says it does. **[inf]** The framework normally sends media keys to
the session of the app that most recently played audio *locally*. A phone that only controls a speaker
plays nothing, so the key usually goes to the last music or video app. It reaches Music Assistant only
after the user played on the local player, or if no other app has played since boot. The M1 spike
must measure this on a device.

**What Media3 gives us instead** (verified in `m3/`):
- `MediaSessionLegacyStub.java:1723-1731` and `:2076-2100`: when the player's `DeviceInfo.playbackType`
  is `PLAYBACK_TYPE_REMOTE`, Media3 calls `setPlaybackToRemote(VolumeProviderCompat)`. The volume
  control is `RELATIVE` with `COMMAND_ADJUST_DEVICE_VOLUME(_WITH_FLAGS)` and `ABSOLUTE` with
  `COMMAND_SET_DEVICE_VOLUME(_WITH_FLAGS)`. `maxVolume` comes from `DeviceInfo`. Key presses arrive as
  `increaseDeviceVolume`/`setDeviceVolume` on our player. **[inf]** Android sends volume keys to an
  active, playing remote session even while the app is in the background, as Cast senders do. Check
  this in the M1 spike.
- `MediaNotificationManager.java:275-300`: Media3 holds the foreground service while any session's
  player has `playWhenReady` and `STATE_READY`/`STATE_BUFFERING`. Local or remote makes no difference.
  After a pause it lets the service leave the foreground after `DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS`
  = 600,000 ms (`MediaSessionService.java:186`). That is better than their "foreground until the queue
  is empty".
- The media3-session 1.11.1 AAR adds no `uses-permission`. It adds only a
  `BluetoothValidationActivity` guarded by `BLUETOOTH_PRIVILEGED`, which is not a permission we request
  (AAR manifest in the Gradle transforms cache).

### 1.2 What was hard, per their own notes and comments

| Hard thing | Evidence | Lesson for Amsel |
| --- | --- | --- |
| `play_media` answers only after playback starts (3-5 s, over 30 s for a large artist). Users tap again, and every tap is a full server play action. | `.claude/play-request-feedback-plan.md:59-70` | `PlayDispatcher` (§2.7). Already in PLAN. |
| Reconnect and re-auth races: RPCs fired before `auth` landed, auth lost when sent before `server/hello`, sessions wedged in `Connecting`, a stale "ready" socket. | `ca/api/KtorServiceClient.kt:547-586`, `:158-162`, `:678-696`, `:1069-1081` | Gate every non-auth command on "authenticated", and fail every pending call on transport loss (§2.1). |
| SystemUI binds the browser service just to draw the notification. Treating that as a car "zombified the notification on remote players" (their issue #519). | `aa/services/AndroidAutoPlaybackService.kt:134-155` | In Media3 `onConnect`, never treat `com.android.systemui` as Android Auto. |
| Two services share one session, and whichever the OS bound used to overwrite the other's state. | `SharedMediaSessionManager.kt:141-152` | One `MediaLibraryService`, one session, one writer (decision D1). |
| Android Auto queue screen always opened at the top: session queue ids came from track ids, not queue-slot ids. | `SharedMediaSessionManager.kt:110-125` | `MediaItem.mediaId` = the server's `queue_item_id`, never the track id. |
| Session idle state: a paused baseline drew a phantom empty card, and an empty action mask made voice hosts ignore the app. | `SharedMediaSessionManager.kt:69-95`, `:698-707`, `:318-334` | Media3 handles the mask through available commands. Keep transport commands available while idle and while reconnecting. |
| OEM surfaces (One UI, MIUI) do not extrapolate the position. | `aa/services/MediaNotificationData.kt:13-16`, `:42-46` | Republish the position when it drifts more than 1 s, with the sample time, not the write time. |
| Wireless Android Auto disconnect removes A2DP and SCO at once, which caused a double pause. | `MainMediaPlaybackService.kt:50-90` | Debounce the "output removed, pause This phone" rule (300 ms). Pause only the local player; a remote speaker plays on. |
| `startForegroundService` must be followed by `startForeground` before `stopSelf`, or the process dies; started from the shell without a type it throws `ForegroundServiceDidNotStartInTimeException`. | `MainMediaPlaybackService.kt:104-122`, `docs/ANDROID-AUTO.md:216` | Let Media3 own the foreground transitions. Never start the service by hand. |
| Android Auto: `onGetRoot` must always return a root, or the car shows a loading screen forever. At most 4 root tabs ("More" holds the rest). | `AndroidAutoPlaybackService.kt:197-224`, `docs/ANDROID-AUTO.md:37` | At most 4 root tabs, no refusals (§2.11). |
| Gemini ignores App Actions, so phone-side "play X on Music Assistant" no longer works. | `docs/ANDROID-AUTO.md:127-140` | Skip App Actions and `shortcuts.xml` entirely. |
| Sendspin: monotonic time base, the wire change at 2.10.7, clock re-seed, buffer sizing. | `.claude/sendspin-status.md:419-465`, `.claude/architecture.md:328-343` | Vendor, do not rewrite (PLAN already says so). |
| Background restriction ("Allow background usage" off) kills the media foreground service whatever the app does. | `ca/utils/BackgroundUsageGuard.kt:3-17` | Known risk for M3's two-hour test (see §5). |

## 2. Area by area

### 2.1 Server connection, reconnect and backoff
**They do.** `DirectTransport` opens `ws(s)://host:port[basePath]/ws` with Ktor and a 10 s ping
interval (`ca/api/DirectTransport.kt:110`, `KtorServiceClient.kt:92-96`, `ca/api/ConnectionInfo.kt:12-18`).
The first failure of a fresh connect is terminal (`Failed`). A drop after a successful connect enters
`runReconnectionLoop`. The backoff ladder is 0, 0.5, 1, 2, 4, 8, 15, 30, 60, then 120 s, for 20
attempts (about 24 min), then `Failed` (`ca/api/ReconnectBackoff.kt:23-41`). While the default network
is down the loop waits for it without spending attempts, adds 500 ms of grace for DNS, and replaces the
HTTP client to drop poisoned pools (`ReconnectBackoff.kt:66-84`, `KtorServiceClient.kt:104-133`).
`connectivityOnline` uses `registerDefaultNetworkCallback` (`sk/ConnectivityOnline.kt:14-34`).
A connect stuck in `Connecting` is failed after 20 s (`KtorServiceClient.kt:678-696`, `:1259`).
Every transport loss calls `rpcEngine.failAll(...)` *after* the state update, so a retry sees "not
ready" (`:600-651`, `:830-838`). Every non-auth `sendRequest` first waits up to 10 s for an
authenticated session. If the session is down, it kicks a reconnect from the saved server
(`ensureReadyForCommands`/`kickRecovery`, `:1069-1128`, `:1135-1158`). A `SessionHold` keeps that
reconnect alive in the background for 10 s (`ca/api/SessionHold.kt`).
In the background they never close an idle socket. A drop while backgrounded and idle becomes
`Disconnected.Backgrounded`, and the next foreground reconnects (`:600-605`, `:459-471`).
`backgroundedAt` is written and never read (`:168`, `:364`). The lifecycle hook is the activity's
`ON_START`/`ON_STOP` (`ca/ui/compose/App.kt:58-74`).
**Copy.** The ladder, network-aware waiting, the connect watchdog, `failAll` on loss, re-auth on every
reconnect, and above all "a command while disconnected waits up to 10 s for a reconnect instead of
failing". That rule is what lets a notification or Bluetooth tap work after our 60 s idle close.
**Avoid.** The attempt ceiling that ends in an error state with recovery only from Settings
(`.claude/sendspin-resilient-architecture.md:348-351`). Amsel has no reconnect UI. Retry forever at
the 120 s step while the app is visible or a speaker plays, and let the snackbar's Retry reset the
ladder (D7). Use `ProcessLifecycleOwner` (already allowlisted), not the activity, for the 60 s idle
close. Rotating the whole HTTP client is an iOS/WireGuard fix; with OkHttp, `connectionPool.evictAll()`
is enough **[inf]**.

### 2.2 Request and response matching
**They do.** `Request(command, args, message_id = UUID)` (`ca/api/Request.kt:51-56`). `RpcEngine`
keeps `message_id -> callback` and `message_id -> partial results` in CAS-updated immutable maps, so
exactly one party completes a request (`ca/api/RpcEngine.kt:44-133`). `partial: true` frames are
joined into the final `result` (`:67-91`). An answer with `error_code` 20 drops the user to "not
authenticated" (`:94-104`, `:137`). Any other error's `details` goes to a toast bus. Cancelling the
caller removes the callback (`KtorServiceClient.kt:1184-1190`). There is no per-request timeout, on
purpose: `play_media` can take tens of seconds (`.claude/play-request-feedback-plan.md:75`). Only
auth round trips have a 15 s cap (`ca/api/AuthRoundTrip.kt:59-63`, `KtorServiceClient.kt:1268`).
Decode failures return `null` and never throw (`ca/api/Answer.kt:19-42`, `ca/api/Event.kt:43-100`).
Their JSON config is `ignoreUnknownKeys` and `coerceInputValues` (`ca/utils/myJson.kt:5-13`).
**Copy.** All of it, in about 100 lines of `:api`: `ConcurrentHashMap` plus `remove()` as the single
completion point, partial joining, `failAll`, no generic timeout, and lenient decoding that never
crashes the collector.
**Avoid.** `prettyPrint = true` on the wire (`myJson.kt:6`), and raw server `details` strings shown to
users (PLAN wants plain sentences).

### 2.3 Login, auth and token storage
**They do.** `auth/login {username, password, device_name}`, then `auth {token, device_name, locale?}`
(`Request.kt:1120-1142`). `device_name` is a random `"KMP app <uuid>"`
(`ca/settings/SettingsRepository.kt:166-174`), which reads badly in the server's token list. After
`server_info` arrives, `AuthenticationManager` looks up the token **by `server_id`** and auto-logs in.
It saves the token again on every successful auth (`ca/auth/AuthenticationManager.kt:96-176`). Tokens
live in plain `SharedPreferences` "AppSecrets" (`SettingsRepository.kt:134-150`,
`provideSettings.android.kt`), excluded from cloud and device-transfer backup by two rule files kept
in sync by hand (`res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`).
Re-auth after a reconnect may fail silently three times before the login screen shows
(`KtorServiceClient.kt:1263`, `ca/api/SilentReauth.kt`). But **any** `error_code` on `auth` counts as
"rejected", and the token is deleted (`AuthRoundTrip.kt:79-88`, `AuthenticationManager.kt:128-135`).
On logout, the server revokes the token and closes the socket, and the client simply reconnects
(`.claude/settings-screen.md:127-142`). Home Assistant sign-in uses a Custom Tab
(`androidx.browser`) and returns to `musicassistant://auth/callback?code=<token>`
(`ca/auth/OAuthCallback.kt:32-76`, `CustomTabsOAuthHandler.kt`).
**Copy.** Key the token by `server_id`, not by address. Treat a missing reply as connectivity, not
rejection. On logout, let the server close the socket.
**Avoid.** Keeping the short-lived login token: after `auth/login`, create a long-lived token with
`auth/token/create {name}` (365 days; `auth.py:1314-1355`) and store only that (D8). Use the name
"Amsel on <device model>", not a UUID. Delete the token only on `InvalidToken` (23) or
`AuthenticationFailed` (21) (`music_assistant_models/errors.py`: 20 `AuthenticationRequired`, 21, 22
`InsufficientPermissions`, 23, 24 `ResourceBusyError`), never on a timeout or a server error (D9).
Custom Tabs and a custom scheme: not possible for us (§0.6). The token store is Keystore-encrypted per
PLAN, so backup of it is useless; set `allowBackup="false"` instead of maintaining two rule files (D12).

### 2.4 Server discovery
**They do.** None. Host, port, TLS and a base path are typed by hand
(`.claude/settings-screen.md:18-27`), and "Server discovery (mDNS/Zeroconf)" is an open wish (`:343`).
There is no NSD code in the repo. On Android, their local-network gate reports "not available",
because they target SDK 36 (`androidHostTest/.../LocalNetworkPermissionAndroidTest.kt:8-27`).
**Copy.** Their `ConnectionInfo` normalisation: base path "" or "/seg" with no trailing slash, and
`wsUrl`/`webUrl` derived once (`ConnectionInfo.kt:3-28`). A reverse proxy under a sub-path needs it.
**Avoid.** Nothing to reuse. Amsel's `_mass._tcp` NSD flow and the Android 17 `ACCESS_LOCAL_NETWORK`
prompt are new ground; the official app is no help here.

### 2.5 Event handling
**They do.** Frames with `message_id` are RPC answers. A frame with `server_id` is `server_info`; a
frame with `event` is an event (`KtorServiceClient.kt:993-1022`). Events are decoded per type, and
unknown or broken ones are logged and dropped (`Event.kt:52-100`). `core_state_updated` refreshes the
cached server info only when the `server_id` matches (`KtorServiceClient.kt:1031-1041`). Player and
queue rebuilds are debounced (`MainDataSource.kt:454-464`). A queue update older than the one held,
by `elapsed_time_last_updated`, is dropped (`:1073-1082`, `ca/data/model/client/QueueInfo.kt:71-75`),
and a full snapshot cannot overwrite newer event state (`mergeFullQueueSnapshot`, `MainDataSource.kt:124-133`).
`queue_items_updated` always refetches the items, even when the anchor is stale, because shuffle does
not move time (`:1466-1500`). `playlog_updated` for another `userid` is ignored, and Home reloads
1.5 s after the last one (`.claude/architecture.md:200-202`).
**Copy.** The staleness gate and snapshot merge, the debounce, the `userid` filter, and
`favorite_updated` handling (API-NOTES has the details).
**Avoid.** Their 16 typed event classes for a music-only app; decode only the ~10 events Amsel uses.

### 2.6 Player and queue state
**They do.** On each authenticated session: `players/all`, then `player_queues/all`, then **every
player's full queue items** in pages of 500 (`MainDataSource.kt:1638-1702`, `:1729-1740`,
`:1757-1767`; `ca/api/Paging.kt:8-52`). Visibility: `enabled && !hidden && !hide_in_ui`. Selectable:
`available || needs_setup` (`ca/data/factory/PlayerFactory.kt:32`, `ca/data/model/client/Player.kt:43-50`).
A player's queue is `active_source ?: current_media.source_id` (`PlayerFactory.kt:42`). Group
volume applies when `group_members` is non-empty (`Player.kt:77-80`). A missing
`current_media.image_url` falls back to the queue item's thumbnail (`MainDataSource.kt:912-930`).
The selected player persists, and the resolver falls back to the first visible one
(`:331-359`, `:1850-1857`). After a persistent error or a background drop, data turns `Stale`, not
empty (`:668-735`).
**Copy.** The visibility and queue-id rules, the artwork fallback, showing stale data while
reconnecting, and a pure function for "which speaker is selected".
**Avoid.** Fetching every player's queue items on connect: fetch Up next only for the selected speaker
when it is shown (D11). Avoid user-sortable player order (`settings.playersSorting`): the Play on sheet
has a fixed order.

### 2.7 Play requests: one per queue, drop double taps
**They do.** `PlayDispatcher` is an app-scope singleton with one lane per queue: one request in
progress plus a waiting list (`ca/data/play/PlayDispatcher.kt:20-112`). The coalescing rule is a pure
function. A new `play` or `replace` replaces a waiting `play`/`replace`; `add`, `next` and
`replace_next` never move; a request equal to its predecessor is a double tap and is dropped
(`ca/data/play/PlayCoalescing.kt:13-29`). Identity is the whole `(queue, media, option, radio_mode,
start_item, shuffle, start_from_beginning)` (`PlayRequest.kt:5-17`). `busyQueueIds` is OR-ed with the
server flag `extra_attributes.play_action_in_progress` (present in 2.10.8 and dev,
`srv/music_assistant/constants.py:980`) to draw a ring on the play button. The ring also shows for
other clients' actions (`MainDataSource.kt:97-109`). On dev, a wait over 120 s per lock raises
`ResourceBusyError` (`srv/.../players/controller.py:244-258`; absent at 2.10.8). Before playing on the
local player they first remove it from any sync group, because otherwise "MA promotes
`play(queue_id=child)` to the group queue" (their claim, `ca/data/LocalPlayerDispatch.kt:51-72`).
Moving music between speakers is `player_queues/transfer {source_queue_id, target_queue_id,
auto_play}` with `auto_play` = the source is playing (`Request.kt:296-307`,
`ca/ui/compose/home/players/PlayersPager.kt:970-978`).
**Copy.** The lane, the pure `coalesce` with its tests, the busy flag from both sources, and
`transfer` for "Play on".
**Avoid.** Nothing big. Their ring is our "visible feedback within 100 ms".

### 2.8 Volume for remote players
**They do.** A slider for players with `volume_set` support or groups, shown only when a volume is
known and the player is on (`Player.kt:80`). It sends once, on release, either
`players/cmd/volume_set {player_id, volume_level 0-100}` or `players/cmd/group_volume` when members
are bound (`PlayersPager.kt:785-823`, `Request.kt:113-144`). Tapping beside the thumb steps with
`volume_up`/`volume_down` (`ca/data/PlayerRequestFactory.kt:95-117`). There is no hardware-key path
for remote players (§1.1). For the local player they deliberately avoid two-way sync: the OS keys set
phone volume, and a server push sets `AudioTrack` gain (`.claude/volume-control.md:5-35`).
**Copy.** Send on release (or throttled), group volume rules, `volume_up`/`volume_down` for relative
steps.
**Avoid.** Leaving the keys on phone volume. `RemotePlayer` reports
`DeviceInfo.Builder(PLAYBACK_TYPE_REMOTE).setMaxVolume(100)` plus the set/adjust device-volume
commands, so the keys, the slider and the system output panel all move the speaker (D2). For This
phone the slider is phone media volume (D3).

### 2.9 Image URLs
**They do.** For schema 31 and up with a `proxy_id`: `<webUrl>/imageproxy/<proxy_id>?size=512&checksum=`.
Otherwise the legacy `?path=&provider=&checksum=` (`KtorServiceClient.kt:226-299`, `:1275-1279`;
`ca/imageloader/ArtworkRequest.kt:16`). A `remotely_accessible` https path is used directly (`:232`).
The player's `current_media.image_url` arrives as an absolute URL in the *server's own* origin. They
re-base it onto the address the app actually uses, stripping a duplicated base path
(`rebaseServerImageUrl`, `:301-333`). No auth header is sent for images (grep).
**Server facts.** Only `/imageproxy/<64-hex id>` is served, and `size` must be one of
{0, 80, 160, 256, 512, 1024}, else 400 (`srv/.../metadata/images.py:327-361`,
`metadata/constants.py:135-137`; same at 2.10.8). The legacy query form gets a 400 on both supported
servers. `proxy_id` is filled on serialisation (`music_assistant_models/media_items/metadata.py:18-27`, `:59-60`).
**Copy.** Re-basing `current_media.image_url`, and taking https artwork directly.
**Avoid.** A fixed 512 and the legacy form: round the displayed pixel size up to the next allowed
bucket (D10).

### 2.10 Media session, service and notification (local player)
**They do.** One foreground service for every player. A Wi-Fi lock `WIFI_MODE_FULL_HIGH_PERF` is held
only while something plays (`MainMediaPlaybackService.kt:147-177`). **No wake lock at all**, despite
`WAKE_LOCK` in the manifest (grep: no `PowerManager`). Audio focus, `ACTION_AUDIO_BECOMING_NOISY`, the
call-mode listener and ducking all live in the sink (`sk/AudioTrackSink.kt:113-176`). Notification tap
opens `musicassistant://app/players` (`MediaNotificationManager.kt:33-43`). Dismissing the notification
while something plays re-focuses the playing player and shows a toast (`MainMediaPlaybackService.kt:179-197`).
A "switch player" custom action cycles through players (`SharedMediaSessionManager.kt:762-766`,
`MainDataSource.kt:397-404`).
**Copy.** Pause This phone on output loss with a debounce, the Wi-Fi lock only while playing, and the
sink's focus handling as vendored.
**Avoid.** The switch-player action (D5). Avoid the hand-built notification and receivers:
`DefaultMediaNotificationProvider` does this. **[inf]** `WIFI_MODE_FULL_HIGH_PERF` is documented as
non-functional from API 34; check `WIFI_MODE_FULL_LOW_LATENCY` behaviour with the screen off in M3.
Add the partial wake lock PLAN asks for: theirs relies on `AudioTrack` keeping the CPU up **[inf]**.

### 2.11 Android Auto browse tree
**They do.** A second service, `AndroidAutoPlaybackService : MediaBrowserServiceCompat`, exported with
`android.media.browse.MediaBrowserService`, sharing the session (`AndroidAutoPlaybackService.kt:40-68`,
manifest `:112-125`, `res/xml/automotive_app_desc.xml` `<uses name="media"/>`). `onGetRoot` always
returns a root with `SEARCH_SUPPORTED` and list-item content style (`:201-224`). Root tabs come from
a car-tabs setting: Home, Artists, Albums, Playlists, Podcasts, Radio, Audiobooks, AI Radio. Each
collection has Recent, Favourites, New and By name sub-lists (`docs/ANDROID-AUTO.md:31-50`). Items
load through `fetchAllPages` with **no cap** (`aa/auto/AutoLibrary.kt:418-452`), cached 5 min and
cleared on reconnect (`:99-104`, `:358-371`, `AndroidAutoPlaybackService.kt:242-284`). Root hints such
as `BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT` are never read (grep). **Car = local player only**:
every browse play goes to the phone, after leaving any group (`AutoLibrary.kt:871-901`). While the car
is connected and no local player exists, the session is deactivated, so the app cannot take the car's
media slot for a remote speaker (`SharedMediaSessionManager.kt:206-244`, `docs/ANDROID-AUTO.md:27`).
Car detection: `androidx.car.app.connection.CarConnection`, projection only
(`aa/services/AndroidCarConnectionMonitor.kt:22-35`). Hosts that may isolate the session:
`com.google.android.projection.gearhead` and `com.google.android.gms.car` (`AndroidAutoPlaybackService.kt:340-343`).
Browse artwork goes out as `content://<app>.autoartwork/art/<AES-GCM token>` URIs, because the host
fetches icons in its own process and cannot follow split-tunnel VPN routes or `mawebrtc://`
(`.claude/architecture.md:366-372`, `aa/auto/AndroidAutoArtworkProvider.kt:40-140`).
**Copy.** "The car plays in the car" (D4). Always return a root. Clear the cache on reconnect. Keep
queue ids stable. Detect the car the way Media3 1.11.1 does internally: query
`content://androidx.car.app.connection`, column `CarConnectionState`, and listen for the broadcast
`androidx.car.app.connection.action.CAR_CONNECTION_UPDATED` (`m3/AndroidAutoConnectionStateObserver.java:34-38`).
No `androidx.car.app` dependency is needed. **[inf]** Value 2 means projection, and Android 11+ may
need a `<queries><provider android:authorities="androidx.car.app.connection"/></queries>` entry,
which is not a permission.
**Avoid.** Two services, settings for car tabs and car actions, podcast, audiobook and AI tabs, and
unbounded lists: cap each list (Recent and Favourites at 100) and honour the root hints **[inf]**. M4
tree: four tabs (Recently played, Playlists, Albums, Artists). Start with plain `http` artwork URIs;
if the Desktop Head Unit shows blank art, add a small non-exported provider keyed by `proxy_id`. No
encryption is needed, because `proxy_id` is already opaque (D13).

### 2.12 "This phone": how the local player registers and shows up
**They do.** The module (`:sendspin-kmp`) connects through the server at `<wsUrl>/sendspin` with the
user token (`ca/data/LocalPlayerEndpoints.kt:107-120`). The player id is the Sendspin identity, the
device's X25519 public key. It is persisted as `sendspin_effective_player_id` once the module reports
`Connected` (`SettingsRepository.kt:459-471`, `ca/data/LocalPlayerAdapter.kt:191-193`). The default
name is "My Phone" (`SettingsRepository.kt:476`, `:840`). Until the server lists the player, a
*synthetic* stand-in is injected (`LocalPlayerAdapter.kt:547-588`). When the module reports
`ServerRefreshNeeded`, the app re-fetches players and queues (`MainDataSource.kt:421-424`,
`LocalPlayerAdapter.kt:296`). The local player is pinned first in every list (`MainDataSource.kt:893-895`)
and has no volume slider, by design. A local pause is a `hold()`: silent at once, with the server
pause following (`.claude/architecture.md:324`). **Module location:** sink, decoders, audio thread,
clock, connectivity and a key store are all in `sk/` (`AudioTrackSink.kt` 250 lines,
`AndroidDecoders.kt` 286, 687 lines in total). The module's own manifest adds `INTERNET` and
`ACCESS_NETWORK_STATE` (`sendspin-kmp/src/androidMain/AndroidManifest.xml`). Its dependencies: Ktor
client and websockets, kermit, atomicfu, cryptography-kotlin (JDK provider), BouncyCastle and Concentus
(`sendspin-kmp/build.gradle.kts:51-75`). None of these are on our allowlist today.
**Copy.** "This phone" first, a stand-in row until the server lists it, a refresh after registration,
and the identity from the module's key store.
**Avoid.** The name "My Phone": use the device's own name, which the server shows to everyone. Avoid
their Settings section for port, path, codec and buffer: defaults only.

## 3. Manifest and network security entries

Theirs come from `androidApp/src/main/AndroidManifest.xml` (line numbers) and
`androidApp/src/main/res/xml/network_security_config.xml`. They target SDK 36 and set minSdk 28
(`gradle/libs.versions.toml:3-5`).

| Entry | Official app | Verdict for Amsel (permission budget, PLAN rule 5) |
| --- | --- | --- |
| `INTERNET` | yes (`:4`) | Keep. |
| `ACCESS_NETWORK_STATE` | merged from the sendspin module manifest | Keep (in the budget, and the vendored module needs it). |
| `ACCESS_LOCAL_NETWORK` | absent (target 36) | Required: we target 37. Ask at runtime on the Connect screen. |
| `WAKE_LOCK` | declared (`:5`), never used | Keep, and actually hold a partial wake lock while This phone plays. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | yes (`:6-7`) | Keep. Media3's AAR does not add them, so declare them ourselves. |
| `CAMERA` + `uses-feature camera` (required, no `required="false"`) | yes (`:8-10`) | Never. Outside the budget; it serves a WebRTC remote-ID feature we do not build. |
| `RECORD_AUDIO` + `uses-feature microphone` | yes, for announcements (`:11-13`) | Never. |
| `POST_NOTIFICATIONS` | absent | Absent. Media-session notifications are exempt (PLAN). |
| `<base-config cleartextTrafficPermitted="true">` | yes | Copy (PLAN Risks): home servers are plain http on 8095. |
| Trust anchors `system` + `user` | yes | Copy: covers self-signed home CAs, and OkHttp and Coil both honour it. |
| mTLS client certificate via `KeyChain` | yes (`.claude/architecture.md:246-248`) | Out of scope for v1 (PLAN). |
| `allowBackup="true"` + `backup_rules.xml` + `data_extraction_rules.xml` excluding `AppSecrets.xml` | yes (`:17-19`) | `allowBackup="false"` (D12). |
| `<meta-data android.app.category = MEDIA>` | yes (`:28-30`) | Use `android:appCategory="audio"` on `<application>` instead **[inf]**. |
| `com.google.android.gms.car.application` → `<automotiveApp><uses name="media"/>` | yes (`:31-33`) | Copy in M4. |
| Exported browser service + `android.media.browse.MediaBrowserService` filter | yes (`:112-125`) | One Media3 `MediaLibraryService` with `androidx.media3.session.MediaLibraryService` and `android.media.browse.MediaBrowserService` actions, `foregroundServiceType="mediaPlayback"`. |
| Second service `MainMediaPlaybackService` (`mediaPlayback`) | yes (`:126-129`) | No (D1). |
| `MEDIA_PLAY_FROM_SEARCH` filters, `VoicePlayDispatchActivity`, `shortcuts.xml` App Actions | yes (`:86-111`) | No. Gemini ignores App Actions (their own doc). |
| OAuth scheme `musicassistant://auth/callback`, deep links `musicassistant://app/...`, App Link `music-assistant.io/app/*` | yes (`:43-85`) | No. Not ours to claim, and no deep-link surface in v1. |
| `FileProvider` for log sharing, `AndroidAutoArtworkProvider` | yes (`:130-146`) | No log sharing. Artwork provider only if the DHU needs it (D13). |
| `launchMode="singleTask"`, `configChanges` listing everything | yes (`:34-38`) | Standard activity. Compose handles configuration changes; no need to opt out of all of them. |

## 4. Decisions (proposed lines for `docs/DECISIONS.md`)

| # | Decision | Settled by |
| --- | --- | --- |
| D1 | One Media3 `MediaLibraryService` and one session serve the app, the notification, Bluetooth and Android Auto. No second service, no shared ref-counted session. | Q2 |
| D2 | `RemotePlayer` reports `DeviceInfo(PLAYBACK_TYPE_REMOTE, 0..100)` with set and adjust device-volume commands, so volume keys and the system panel move the speaker. | Q1 |
| D3 | This phone's volume control is the phone's media volume. The server's gain push stays inside the vendored sink, as in the official app. | Q2 |
| D4 | While Android Auto projection is connected, plays from the car go to This phone, whatever speaker is selected. | Q1 |
| D5 | No "switch speaker" action in the notification or the car; speakers change only in the Play on sheet. | Q2 |
| D6 | The notification and lock screen show the speaker's name for a remote speaker (artist line "Artist · Kitchen"). | Q1 |
| D7 | Reconnect ladder 0, 0.5, 1, 2, 4, 8, 15, 30, 60, 120 s, then every 120 s with no ceiling while the app is visible or a speaker plays; waits for the network; Retry resets it. Commands wait up to 10 s for the session. | Q2 |
| D8 | After sign-in, create a long-lived token with `auth/token/create`, named "Amsel on <device model>"; store only that. | Q1 |
| D9 | Delete the stored token only on `auth` errors 21 or 23; keep it on timeouts, socket loss and other errors. | Q1 |
| D10 | Artwork URL is always `/imageproxy/<proxy_id>?size=<bucket>`, the bucket being the next of 80/160/256/512/1024 at or above the drawn pixel size. | Q4 |
| D11 | Load queue items only for the selected speaker, when Up next is shown. | Q4 |
| D12 | `android:allowBackup="false"`: no backup rule files to keep in sync, and the Keystore token cannot be restored anyway. | Q2 |
| D13 | Android Auto browse artwork starts as plain `artworkUri`s; a `proxy_id`-keyed content provider is added only if the Desktop Head Unit shows missing art. | Q2, then evidence |

## 5. Facts the lead must know

- PLAN.md says the decoder and sink live outside the module, in `player/local/`. That is outdated:
  they are in `sendspin-kmp/src/androidMain/.../sendspin/android/`, so vendoring the module brings
  them along. The module also drags in Ktor, kermit, cryptography-kotlin, BouncyCastle and Concentus.
  The dependency allowlist must take them, or the M0 spike must replace Ktor.
- Their remote media session is legacy `MediaSessionCompat` with local playback. They chose not to
  support hardware volume for remote speakers. Nothing proves headphone buttons reach a remote-only
  session; the M1 spike is the first real test.
- Media3 1.11.1 already implements remote volume (`VolumeProviderCompat`) and "foreground while
  playing" for any `Player`. A `SimpleBasePlayer` wrapping a Music Assistant queue gets both for free.
- Their Android Auto is phone-only and deactivates the session in the car without a local player.
  Before M3 ships This phone, Android Auto has nothing to play on. That fits M4 coming after M3.
- `auth/login` tokens expire (30 days sliding, 90 days hard), and the official app lives with
  re-logins. `auth/token/create` gives 365 days with no renewal. **[inf]** A yearly re-login, or a
  silent re-create before expiry, is still open.
- Only `musicassistant://`, http and https pass the server's return-URL check. An Amsel custom scheme
  is refused, so Home Assistant sign-in must use the WebView flow (`docs/API-NOTES.md` §3.4).
- `/imageproxy?path=&provider=` returns 400 on 2.10.8 and dev, so ignore the official fallback.
- Background restriction ("Allow background usage" off) kills the media foreground service. The
  official app detects it with `ActivityManager.isBackgroundRestricted()` (`ca/utils/BackgroundUsageGuard.kt`).
  If the M3 two-hour test fails on a real phone, check this first.
