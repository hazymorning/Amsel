# Music Assistant API notes

Every Music Assistant (MA) command and event Amsel v1 uses, with arguments, return shapes,
schema gates and quirks. Add an entry whenever the app uses a new command, after checking
`/api-docs` on the test server (`docs/PLAN.md`, rules in `AGENTS.md`).

**Verified against** (2026-11-01): server `dev` @ `acea6207` (API schema **100**), Docker
`ghcr.io/music-assistant/server:beta` = **2.11.0b7** (schema **99**), tag and Docker image **2.10.8**
(schema **65**); models package `music-assistant/models` 1.1.232 (dev pin) and 1.1.205 (2.10.8 pin).
Command signatures for both releases come from each running server's `/api-docs/commands.json`.

**Markers.** **[live]** seen on a running 2.10.8 *and* 2.11.0b7 server through a WebSocket probe
(local-files library, Snapcast player). **[src]** read in source. **[inf]** inferred, check it
before relying on it. Paths are server-repo paths at `dev` unless marked `@2.10.8`; `models:` paths
are in `music-assistant/models@1.1.232`; `client:` is `/home/user/ref/client/music_assistant_client/`;
`mobile-app` files are under `/home/user/ref/mobile-app/composeApp/src/commonMain/kotlin/io/music_assistant/client/`.

## How a session works

1. Find the server: Android NSD for `_mass._tcp`, TXT `base_url`, or the typed address. Confirm it with `GET /info` (no auth).
2. Open `ws(s)://host:8095/ws`. The first frame is `server_info` (no `message_id`). Read `schema_version` and pick the gates in §1.
3. No token yet: `auth/login {username, password, device_name}` works before auth and returns a session token; Home Assistant: `auth/authorization_url`, a WebView, and the token in the return URL (§3.4).
4. Send `auth {token}`. From the success reply on, the server pushes **every** event to this socket. There is no subscribe command.
5. First sign-in only: `auth/token/create {name}` gives a long-lived token (365 days). Store it and use it in step 4 from then on.
6. Once per session: `auth/scopes`, look up `user.role`, hide what the role may not do (§3.5).
7. Load `players/all` and `player_queues/all`, then keep both current from `player_*` and `queue_*` events.
8. Library index: page `music/{playlists,albums,artists,audiobooks,podcasts,radios}/library_items` (limit 500) into Room, the kinds side by side, for each kind whose count or favourite count differs from the index's (or after another server or schema). Patch it from `media_item_*` and favourite events; re-sync on `music_sync_completed`.
9. Every command is `{message_id, command, args}`. Match replies by `message_id`, join `partial:true` chunks, and turn `error_code` into a plain sentence.
10. On a dropped socket reconnect with backoff and start again at step 2. Events are not replayed, so re-fetch players and queues. `auth` error 23 means the token is gone: show sign-in.

## 1. Versions and schema gates

`schema_version` comes from `music_assistant/constants.py:57` (`API_SCHEMA_VERSION`);
`min_supported_schema_version` is `MIN_SCHEMA_VERSION = 28` on both releases (`constants.py:65`).
The server never bumps the schema for a backport, so 2.10.8 (65) has nothing listed above 65.
Bump commits found with `git log -G 'API_SCHEMA_VERSION' -- music_assistant/constants.py`.

| Schema | What arrives (only rows that matter to Amsel) | Source |
| --- | --- | --- |
| 28 | Every connection needs `auth` with a token | `client: client.py:294` |
| 31 | `MediaItemImage.proxy_id` and `/imageproxy/<id>` | `client: client.py:197` |
| 33 | `locale` argument on `auth` | `client: client.py:309` |
| 42 | `time` command (server clock, unauthenticated) | commit d14d437d |
| 52 | `play_media(shuffle=)`; 40 added `start_from_beginning` | mobile-app `PlayMediaSupport.kt` |
| 63 | `music/search(providers=)` | mobile-app `SearchSourcesSupport.kt` |
| **65** | **2.10.8** | `/info` [live] |
| 71 | Personal MA playlists with `Playlist.access` | commit 58289dab |
| 74 | Custom roles: `auth/roles`, `auth/role/*`; `auth/scopes` lists them too | commit f0340787 |
| 78 | Favourites per user; `favorite` is `true`/`false`/`null` (like/dislike/none); `music/favorites/set_item`; event `favorite_updated` | commit 779b4127 |
| 86 | Typed sort `sort_field`/`sort_direction` on `library_items`, `music/sort_options`; `order_by` deprecated | commit 7d2c9371 |
| 87 | `server_info.unsupported_install` | commit ec33c011 |
| 89 | `album_tracks` takes `search`, `sort_*`, `limit`/`offset` | commit 4a712e6d |
| 92 | Artist listings (`artist_albums`, `artist_tracks`, `top_tracks`) sort, search and page | commit abf5d0a4 |
| 93 | `playlist_tracks` and `podcast_episodes` return one page (`limit` default 500, `offset`) instead of streaming | commit 95691456 |
| 95 | `play_media(sort_field, sort_direction)` | commit 1e873486 |
| 96 | `favorite` / `album_types` filters on sub-listings (mobile app's "2.11" gate) | commit 430066a4 |
| 98 | `random_seed` keeps random order stable across pages | commit 44bd993b |
| **99** | **2.11.0b7**: `music/playlists/move_playlist_track` | commit 4018f2d6 |
| **100** | `dev`: collection metadata over all items (audiobooks, not used) | commit bb1e971c |

**Gate on the exact number per feature** (`>= 78` favourites model, `>= 86` sorting, `>= 89` album
tracks paging, `>= 92` artist listing `limit`, `>= 93` playlist paging), never on the version string. Unknown arguments are
**silently dropped** by older servers (`helpers/api.py:215-246`, `strict=False`): sending
`sort_field` to 2.10.8 returns the default order with no error [live], so a missing gate
fails quietly.

## 2. Connection and messages

### 2.1 HTTP routes the app uses (`controllers/webserver/controller.py:355-401`)

| Route | Auth | Use |
| --- | --- | --- |
| `GET /info` | none | `server_info` JSON, same fields as §2.2; check a typed address or a discovered one [live] |
| `GET /ws` | `auth` command | The API socket |
| `GET /imageproxy/<proxy_id>?size=&fmt=` | none | Artwork (§8) [live] |
| `GET /sendspin` | first frame `{"type":"auth","token":…}` | Sendspin proxy for "This phone" (`webserver/sendspin_proxy.py:50-113`, close code 4001 on bad auth). Use the API socket's token: the server binds the Sendspin player to API sockets holding the same token, which is how the app sees events for its own private player (`webserver/controller.py:560-582`) |
| `GET /api-docs`, `/api-docs/commands.json` | none | Generated reference; `commands.json` is the machine-readable signature list |

The port is 8095 by default. `POST /auth/login` and `POST /api` exist but the app does not
need them: everything goes over the socket.

### 2.2 `server_info`, the first frame (`mass.py:485-501`, `models: api.py:85-123`)

| Field | Type | Since | Notes |
| --- | --- | --- | --- |
| `server_id` | str | 28 | Stable per install; key the stored token and the Room cache by it |
| `server_version` | str | 28 | `"2.10.8"`, `"2.11.0b7"` |
| `schema_version` | int | 28 | 65 / 99 / 100 |
| `min_supported_schema_version` | int | 28 | 28 |
| `base_url` / `internal_url` | str | 28 / 32 | Aliases of each other. Can be a container-internal address (`http://172.17.0.2:8095` [live]); do not use it for images |
| `external_url` | str or null | 32 | Configured public URL |
| `name` | str | 29 | e.g. `"Music Assistant (02102dd16fcf)"` |
| `status` | `starting`/`running`/`stopping`/`stopped` | 29 | Seen `starting` right after boot [live] |
| `onboard_done` | bool | 28 | Web UI onboarding wizard state, not "has users" |
| `homeassistant_addon`, `has_remote_access`, `unsupported_install` (87) | bool | | Not needed |

Detect it as the frame with `server_id` and `server_version` and no `event` or `message_id`
(frontend `plugins/api/index.ts:313`). The server sends it again only inside a
`core_state_updated` event.

If the server has no users yet it sends `{"message_id":"connection","error_code":503,"details":"Setup required"}`
and closes (`websocket_client.py:189-197`). Show "Finish setting up Music Assistant" with "Open web UI".

### 2.3 Frames (`models: api.py:27-63`, `models: event.py`) [live]

```json
-> {"message_id": "<any unique string>", "command": "players/all", "args": {"return_unavailable": true}}
<- {"message_id": "...", "result": [...], "partial": false}
<- {"message_id": "...", "result": [500 items], "partial": true}      // more follows, same message_id
<- {"message_id": "...", "error_code": 22, "details": "You do not have permission to perform this action."}
<- {"event": "queue_time_updated", "object_id": "ma_kitchen", "data": 5.64}
```

- Each command runs as its own task (`websocket_client.py:333`), so replies arrive **out of order**. Map by `message_id` only.
- `partial`: an async-generator command sends 500-item chunks with `partial:true`, then a final `partial:false` frame holding the rest (possibly `[]`). Concatenate all of them (`websocket_client.py:346-356`, same @2.10.8 `:287-297`). On 2.10 this applies to `playlist_tracks`.
- `details` is localised to the connection locale and differs from the source text (e.g. "Authentication is required." [live]). Log it, never show it, branch on `error_code`.
- A lookup that finds nothing may return `result: null` instead of an error (`player_queues/get` with an unknown id [live]).
- A missing required argument returns `error_code` 999 with a Python message [live].
- The server pings every 25 s (`heartbeat=25`, `websocket_client.py:79`); OkHttp answers pings by itself. It queues at most 512 unsent frames per client and drops a client that does not read (`:66`, `:430-435`).

### 2.4 Error codes the app maps (`models: errors.py`)

| Code | Name | App reaction |
| --- | --- | --- |
| 2 | MediaNotFoundError | "That's no longer available" |
| 3 | InvalidDataError | Bug or bad sort field (2.11 rejects a field the listing does not offer [live]) |
| 9 | UnsupportedFeaturedException | Hide the control |
| 10 / 11 | PlayerUnavailable / PlayerCommandFailed | "Kitchen isn't responding" |
| 12 | InvalidCommand | Unknown command: a missing schema gate, or a plugin that is not loaded (§7.11). Also a change the queue refuses in its state: shuffle or repeat on a dynamic queue (§6.1), a seek past the end (§7.9) [live] |
| 20 | AuthenticationRequired | Command sent before `auth` [live] |
| 21 / 6 | AuthenticationFailed / LoginFailed | Sign-in failed |
| 22 | InsufficientPermissions | Missing scope (§3.5) [live] |
| 23 | InvalidToken | Token revoked or expired: back to sign-in [live] |
| 24 | ResourceBusyError | 2.11: a play action still held the speaker's lock after 120 s, the group's and again its own (§6.3); 2.10 never sends it |
| 25 | RateLimited | Too many sign-in attempts |
| 503 | (setup) | Server not set up, `message_id` is `"connection"` |
| 999 | anything else | Generic "Something went wrong" |

## 3. Sign-in

### 3.1 Commands (`controllers/webserver/auth.py`; `websocket_client.py:460-533` for `auth`)

| Command | Args | Returns | Gate | Notes |
| --- | --- | --- | --- | --- |
| `auth/providers` | none | `[{provider_id, provider_type, requires_redirect, supports_remote_app}]` | 28; `supports_remote_app` 2.11 | No auth needed. `builtin` always; `homeassistant` only while MA's Home Assistant plugin provider is loaded (`auth.py:995-1011`, `:2314-2341`). [live: builtin] |
| `auth/login` | `username`, `password`, `provider_id="builtin"`, `device_name` | `{success:true, access_token, user:{user_id, username, display_name, role}}` | 28 | No auth needed. **Failure is a normal result**, `{success:false, error, translation_key}` (no `translation_key` on 2.10) [live]. Token is a session token named `device_name` (`auth.py:1013-1081`) |
| `auth` | `token` (alias `access_token`), `locale` (33+) | `{authenticated:true, user: User}` | 28 | Error 20 without token, 23 bad token; the socket stays open after a failed `auth` and a second `auth` works [live]. Re-auth on an authenticated socket is allowed [src] |
| `auth/token/create` | `name`, `user_id?` | token string (JWT) | 28 | Long-lived. **Error 22 for role `guest`** [live] (`auth.py:1314-1355`) |
| `auth/token/revoke` | `token_id` | null | 28 | Closes every socket authenticated with that token (`auth.py:747-779`) |
| `auth/scopes` | none | `{role_id: [scope, ...]}` | 28; custom roles 74 | §3.5 [live] |
| `auth/logout` | none | null, usually never delivered | 28 | Revokes the current token; the server closes the socket (code 1000) before the reply arrives [live] (`auth.py:1792-1813`) |
| `auth/authorization_url` | `provider_id`, `return_url?`; 2.11 adds `code_challenge?`, `code_challenge_method?`, `redirect_target="server"` | `{authorization_url}`; 2.11 adds `state`, `expires_at`; on failure `{authorization_url:null, error}` | 28 | No auth needed (`auth.py:1094-1170`, @2.10.8 `:1027-1062`) |

### 3.2 Tokens (`auth.py:92-96`, same @2.10.8 `:75-79`) [live]

| Kind | Made by | Lifetime | Renews |
| --- | --- | --- | --- |
| Session | `auth/login`, HA callback, `POST /setup` | 30 days after last use, hard cap 90 days after creation | Yes, on each `auth` |
| Guest session | same, user role `guest` | 1 day | No |
| Long-lived | `auth/token/create` | 365 days | No |

Tokens are HS256 JWTs. Observed claims: `sub`, `jti` (equals `token_id` in `auth/tokens`),
`iat`, `exp`, `username`, `role`, `token_name`, `is_long_lived`; 2.11 adds `iss`, `aud` [live].

### 3.3 Username and password, exact sequence

1. Socket open, `server_info` read.
2. `auth/login {username, password, device_name: "Amsel (<Build.MODEL>)"}`. `success:false` means "That password didn't work"; keep `error` for the log.
3. `auth {token: access_token}`.
4. `auth/token/create {name: "Amsel (<Build.MODEL>)"}`; store the result in Keystore-backed storage, keyed by `server_id`. On error 22 (guest) keep the session token instead; a guest signs in again after a day, a server rule the app cannot change.
5. `auth {token: <long-lived>}` on the same socket, so later revocation of the session token cannot drop it.
6. Optional tidy-up: `auth/token/revoke {token_id: <jti of the session token>}`. Left alone it expires 30 days after its last use.
7. Rotation, so nobody is asked again after a year: when the stored token's `exp` claim is under 30 days away, run steps 4-6 on a live session, revoking the old long-lived token by its `jti` [inf: design built only from the verified commands above].

### 3.4 Sign in with Home Assistant

What the server offers: `auth/providers` lists `{"provider_id":"homeassistant","provider_type":"homeassistant","requires_redirect":true,...}`
when MA's Home Assistant plugin is set up [src]. The OAuth exchange with HA happens on the server,
with the server's own PKCE pair; the client only ever sees the finished MA token.

1. `auth/authorization_url {provider_id: "homeassistant", return_url: "http://localhost/amsel-auth"}`. The result's `authorization_url` is HA's `/auth/authorize` page. Its `redirect_uri` is `<MA base_url>/auth/callback?provider_id=homeassistant`, or the External URL when the sign-in started there (`controller.py:262-291`). A pending sign-in lives 10 minutes, is single use, and at most 100 can wait (`webserver/README.md`, `helpers/login_flow.py`).
2. Load `authorization_url` in an in-app `android.webkit.WebView` with JavaScript **and DOM storage** on: the callback page calls `localStorage.setItem` before it redirects (`helpers/resources/oauth_callback.html:271-283`).
3. The person signs in to HA. HA sends the browser to MA's callback; MA finds or creates the user (creation only if self-registration is allowed), makes a **session** token, and serves a page that after 500 ms navigates to `return_url?code=<MA token>` (`controller.py:1232-1292`, `redirect_validation.py:117-142`).
4. In `WebViewClient`, catch the navigation whose URL starts with the return URL, read the `code` query parameter, close the WebView, and continue at step 3 of §3.3.

Why this shape: `return_url` must be http(s) or start with `musicassistant://`; any other scheme is
refused with `{"authorization_url": null, "error": "Invalid return_url"}` (`helpers/redirect_validation.py:19-26`, `:62-71`, same @2.10.8).
`musicassistant://auth/callback` belongs to the official app (mobile-app `auth/OAuthCallback.kt`);
claiming it would make Android ask which app should open it. `localhost` is always a trusted
return URL on both releases, so the server shows no consent page, and the WebView never loads it.
The phone must reach HA's URL and MA's `base_url`. If MA's `base_url` is an address the phone
cannot reach (container-internal, for example), the callback fails: fall back to username and password.

### 3.5 Scopes (`webserver/helpers/auth_middleware.py:37-68`; @2.10.8 `:37-68`) [live]

`auth/scopes` on both releases returns `admin: ["*"]` and, for the other builtin roles:

| Scope | Gates in v1 | guest | user | service |
| --- | --- | --- | --- | --- |
| `library.read` | All `music/*` reads, search | yes | yes | yes |
| `players.read`, `queues.read` | `players/all`, `player_queues/all`, `items` | yes | yes | yes |
| `players.control` | `players/cmd/*` (transport, volume, power) | yes | yes | yes |
| `queues.control` | `player_queues/*` writes incl. `play_media`, `transfer` | yes | yes | yes |
| `library.write` | `music/favorites/*`, `add_playlist_tracks`, `remove_playlist_tracks`, `move_playlist_track` | **no** | yes | yes |
| (role not guest) | `auth/token/create` | **no** | yes | yes |

`user` also holds `config.*.read`, `providers.read`, `system.read`, `users.invite`, and on 2.11
`config.providers.own`. Gate on the scope, never the role name: 2.11 has admin-made custom roles
(schema 74), which always hold the guest scopes plus what was granted. An unknown role grants
nothing, so the gate fails closed. A missing scope answers error 22 [live]. Admin `"*"` grants all.

### 3.6 Revocation

The server closes the socket when the token is revoked, the user is disabled or deleted, or the
user's role or scopes change (`controller.py:515-530`, webserver `README.md`). After such a close
the next `auth` returns 23: clear the token, show sign-in. The `auth_session` event exists in the
enum, but the server never sends it (no `signal_event` for it in `dev`) [src].

## 4. Discovery (`controllers/discovery/controller.py:277-299`, same @2.10.8 `:256-280`) [live]

Service type `_mass._tcp.local.`, instance name `<server_id>._mass._tcp.local.`, port = the
webserver port (8095), SRV target `mass.local.`. TXT = `server_info.to_dict()`, every value a
string; booleans are Python-style `True`/`False`; a null value is a bare key (`external_url`).

| TXT key | Example [live] |
| --- | --- |
| `server_id` | `f13566951f1b40ccb6e676dbca8161c8` |
| `server_version`, `schema_version`, `min_supported_schema_version` | `2.10.8`, `65`, `28` |
| `base_url`, `internal_url` | `http://172.17.0.2:8095` |
| `name`, `status`, `onboard_done`, `homeassistant_addon`, `has_remote_access` | `Music Assistant (…)`, `running`, `False`, `False`, `False` |
| `unsupported_install` | 2.11 only |

**Gotcha:** every MA server announces the same SRV host `mass.local.`, so with two servers on one
network their A records merge (two servers seen with both IPs each [live]). Resolve the candidate
as TXT `base_url` first, then the NSD host and port, and keep the first whose `GET /info` returns
the same `server_id`.

## 5. Players

### 5.1 Listing and which speakers to show

`players/all {return_unavailable=true, return_disabled=false, provider_filter?, return_protocol_players=false}`
returns `[Player]` (`players/controller.py:443-470`); `players/get {player_id, raise_unavailable?}` one.
Non-admin users only see the players in their `player_filter`; events for other players are not
sent to them (`websocket_client.py:597-616`).

Show a speaker when `enabled && available && !hide_in_ui && !needs_setup`, `type` is one of
`player`, `stereo_pair`, `group`, and it is not part of another group (`synced_to == null`,
`active_group == null`). `private` players belong to one device (a web or app Sendspin player);
show only the one this app created. Same rules as frontend `helpers/players.ts:98-145`; playback
target types from `helpers/config_entries.py:29-33`.

### 5.2 Player fields used (`models: player.py:336-475`) [live]

| Field | Type | Notes |
| --- | --- | --- |
| `player_id` | str | Also the id of its queue (`ma_kitchen` [live]) |
| `name` | str | `display_name` is a deprecated alias |
| `type`, `provider` | str | `provider` is a provider instance id |
| `available`, `enabled`, `hide_in_ui`, `private`, `needs_setup` | bool | `hide_player_in_ui` is a deprecated alias list |
| `playback_state` | `idle` / `paused` / `playing` / `unknown` | `state` is a deprecated alias |
| `powered` | bool or null | Serialised `true` when `power_control == "none"`. `false` when its power control says off, such as a Home Assistant entity chosen as power control in MA's Home Assistant provider (any of `media_player`, `switch`, `input_boolean`; on = not in HA's off states). The queue can still say `playing` meanwhile, and MA ends it only after 15 s off (`EXTERNAL_POWER_OFF_STOP_DELAY`): Amsel shows a speaker that is off as idle at once |
| `power_control`, `volume_control`, `mute_control` | str | `"none"` means not controllable: hide the control (frontend `VolumeControl.vue:106`, `PlayerCard.vue:148`) |
| `volume_level` (0-100), `volume_muted` | int, bool | |
| `group_members`, `synced_to`, `active_group` | list, str | Group state; `group_childs` is a deprecated alias. On 2.11 a leader's `group_members` can be empty (a group joined below the visible players) or out of date (after members leave) while each member's `synced_to` is right: Amsel reads a group from its members' `synced_to` [live: 2.11.0b7] |
| `group_volume`, `group_volume_muted` | int, bool | A group's or sync leader's loudest powered member (`models/player.py:1649`), the level to show for one with `group_members`; a group has no `volume_level`. A lone speaker's own volume only when it sets it natively: one whose volume runs through another control sends `group_volume` 0 beside its real `volume_level` [live: `players_all`]; `group_volume` is sent as 0 when nothing has volume |
| `active_source` | str | The queue id when MA plays; otherwise a native source id from `source_list`. The server prefers the MA source unless the speaker reports another one playing (a line-in or TV input "takeover", `models/player.py` `__final_active_source`) |
| `source_list` | `[{id, name, passive, can_play_pause, can_seek, can_next_previous, …}]` | The speaker's own inputs and services (Optical In, TV). While one plays, `current_media` is what the speaker reports, often the input's name as `title` and no `queue_item_id`. MA's Home Assistant provider never reads an entity's `source`; it judges MA's playback by `media_content_id` alone, so a speaker whose own input takes over can stay "playing" MA's last item. A power control that is off while the input plays is the reliable signal (`powered`) |
| `current_media` | `{uri, media_type, title, artist, album, album_artist, image_url, palette, duration, source_id, queue_item_id, elapsed_time, elapsed_time_last_updated}` | What plays now, whatever the source. `image_url` is an absolute imageproxy URL built on `base_url` (rebuild it, §8). `palette` = `{background_dark, background_light, primary, accent, on_dark, on_light}` as `[r,g,b]` or null, on both releases [live] |
| `elapsed_time`, `elapsed_time_last_updated` | float | Position = `elapsed_time + (now - last_updated)` while playing; `time` gives the server clock |
| `supported_features` | set of str | `power`, `volume_set`, `volume_mute`, `pause`, `seek`, `next_previous`, `enqueue`, `play_media`, `set_members`, `select_source`, … |

### 5.3 Player commands (all `players.control`, all return null, same on both releases) [live]

| Command | Args | Notes |
| --- | --- | --- |
| `players/cmd/play`, `pause`, `play_pause`, `stop` | `player_id` | Routed to the group leader, an external source's own controls, or the active MA queue (`players/controller.py:660-720`, `next` at `:845-869`) |
| `players/cmd/next`, `previous` | `player_id` | Same routing |
| `players/cmd/seek` | `player_id`, `position` (int seconds) | Same routing (`:738-755`) |
| `players/cmd/volume_set` | `player_id`, `volume_level` | One speaker's own level, also inside a group; Amsel's volume sheet sets a group member's with it (`players/controller.py:920`) |
| `players/cmd/volume_up`, `volume_down` | `player_id` | Server-side step; group-aware (`:940-955`) |
| `players/cmd/set_members` | `target_player`, `player_ids_to_add`, `player_ids_to_remove` | The leader (or group player) must list `set_members` in `supported_features`; `can_group_with` on the player names who may join. Amsel's Play on groups with it |
| `players/cmd/ungroup_many` | `player_ids` | Lets each player go from the group it is synced to (`players/cmd/ungroup` for one). Not used: for a member of a group player it stops the whole group player, on 2.10.9 one unavailable id ends the rest, and for a leader that plays it hands the lead on. Amsel lets a group go as the web UI does, `set_members` on the leader with its members in `player_ids_to_remove` [server source, live: 2.10.9, 2.11.0b7] |
| `players/sleep_timer/set`, `clear` (`get`) | `player_id`, `seconds` (set only) | 2.11: the server stops the player `seconds` from now and returns when, in Unix seconds; `sleep_timer_expires_at` on the player and `player_sleep_timer_updated` follow it. An older server answers with an error, and Amsel pauses the speaker itself while it runs |
| `players/cmd/group_volume`, `group_volume_up`, `group_volume_down` | `player_id`, `volume_level` (set only) | A group or sync leader moves its members together, keeping their balance; a lone speaker takes it as `volume_set` (`:974-1035`, same in 2.10). `volume_set` on a sync leader changes the leader alone |

Use `players/cmd/*` for transport, as the web UI does (frontend `plugins/api/index.ts:2286-2370`):
it also covers external sources such as Spotify Connect. Use `player_queues/*` for queue editing.

**A pause or a stop while a play action runs is taken and then overrun** [live, 2.10.9 and 2.11.0b7,
the fake Sendspin speaker]. The server answers the pause at once and changes nothing; the play
action finishes and the speaker plays on. Seen for `players/cmd/play` after a pause and for
`play_media` on a stopped speaker, paused 0.05 to 0.3 s after they were sent; the action took 0.6
to 2 s there, and takes longer on a real speaker. A pause sent once the play is answered holds.
The reports come before that answer: `queue_updated` with `play_action_in_progress` true within
10 ms of the play, then the player and the queue as `playing` with the flag false, then the play's
answer. So Amsel sends the pause, and where a play is on its way (its own play, skip or pick in Up
next, a `play_media`, or the queue's flag) sends it again once that is over, unless playback was
asked for meanwhile (`SeekLane`, `MaSpeakerCommands.rest`). On 2.11.0b7 a pause right after
`next` or `seek` holds by itself: it cancels the `play_index` the server had scheduled.

### 5.4 This phone (Sendspin)

| Command | Args | Returns | Scope | Notes |
| --- | --- | --- | --- | --- |
| `sendspin/pair_web_player` | `pairing_token` | null, or an `error_code` frame | `players.control` | Used only by PhoneSpeaker's silent pairing (`approvePairing`): approves the phone's Sendspin client over the trusted control socket. The server pairs only a built-in app/web player (`device_info.product_name` "Mobile Application", hence ours). `providers/sendspin/provider.py:951-1010`, registered at `:1109`; details in `docs/notes/sendspin-vendoring.md` |

The server unregisters a disconnected Sendspin client and drops its queue
(`providers/sendspin/provider.py:1783-1798`, `player_queues/controller.py:1473-1490`): at once
when the client says goodbye with `user_request`, which the module does whenever its config
turns null (another speaker, the idle close, signing out), otherwise after aiosendspin's 180 s
cleanup delay (`aiosendspin/server/client.py`, `CLIENT_CLEANUP_DELAY`). On the next join it
registers the player again, after polling for the hello every 100 ms (`provider.py:1652-1755`),
and restores the queue from its cache (`player_queues/controller.py:1356-1398`). So a command
for This phone waits until its Sendspin client is connected and the server lists it again (an
entry kept from before the idle close does not count: `Speakers.gone`), and a "Play on" move
away from the phone keeps it connected until the transfer has read its queue.

That cache is written only when the queue's "significant" state changes: `cache_significant`
strips `elapsed_time`, `elapsed_time_last_updated` and `playback_speed`
(`player_queues/state.py` `_VOLATILE_CACHE_FIELDS`, the same in 2.11.0b7), so a seek is never
saved, and a pause only once the queue has rested 5 s (`QUEUE_CACHE_SAVE_DELAY`, debounced; a
player's removal cancels the pending write). The restored queue stands where it was at the last
such write, and `resume` plays from there (`_last_played_position`; the queue's `prev_state` went
with it) [live, 2.10.9 and 2.11.0b7, Audiobookshelf, a three-hour book]: started in chapter 3,
sent to chapter 25 and paused at 7214.0 s, it came back at 615.1 s.

Not only the idle close does this: every join of This phone's Sendspin client registers the player
anew (`_handle_client_added` unregisters an existing player object first, the same in 2.11.0b7),
so any lost connection (Wi-Fi to a VPN) rebuilds the queue from the cache [live, 2.10.9, a client
registered as Amsel's, "Mobile Application"]: started in chapter 1, sent to chapter 25 and
playing, the connection lost at 7225 s; after the join the queue stood idle at 127 s, and
`players/cmd/play` played 131 s. The server's bookmark is written on pause and each time the
time crosses a multiple of 30 s (`PLAYBACK_REPORT_INTERVAL_SECONDS`), never on a seek, so
`play_media` resumes at most 30 s back, but before a seek made in those 30 s (the same case:
125 s). The official app never closes its session on purpose (`KtorServiceClient`: in the
background it only declines to reconnect a dropped transport while nothing plays), but a lost
connection rebuilds its phone's queue as well. Amsel keeps the session open while music is paused
on the phone (`Session.setHolding`); when the phone's client leaves it notes the book or episode
and where it was (`Speakers.left`), and after the next join the rebuilt queue is parked at that
place (`Speakers.rejoined`, `Seek.Parked`): it shows there and a play is a seek there, until the
queue plays again or holds something else. A client-side fix that remembered places across runs
(`Stand`, 2026.11.6, removed in 2026.11.9) took a chapter picked after the phone came back for the
old save and sent the book back there.

## 6. Queues

### 6.1 Queue fields used (`models: player_queue.py`, `queue_item.py`) [live]

| Field | Notes |
| --- | --- |
| `queue_id` | Equals the player id |
| `active` | The queue is the active source of its player |
| `display_name`, `available` | |
| `items` | **Count** of items, not a list |
| `state` | `idle` / `paused` / `playing` |
| `shuffle_enabled`, `repeat_mode` (`off`/`one`/`all`) | |
| `is_dynamic` | A mix the server keeps flowing (a dynamic playlist such as "Infinite Mix", or an endless mix, §7.11): shuffle is forced on, and `player_queues/shuffle` and `player_queues/repeat` with another value are refused with error 12 (an internal error over the REST bridge). On 2.10.9 as on 2.11.0b7 [live] |
| `sources` | `[ItemMapping]`: what the queue was filled from, `[]` when empty, on both releases; an endless mix names itself here (§7.11) [live] |
| `current_index`, `index_in_buffer` | Items at or before `index_in_buffer` are committed to the player and cannot move (§6.2) |
| `elapsed_time`, `elapsed_time_last_updated`, `playback_speed` | Position of `current_item` |
| `current_item`, `next_item` | `QueueItem` or null |
| `ended` | Played to the end, items kept |
| `extra_attributes.play_action_in_progress` | `true` while a play action runs, on both releases (`constants.py:980`) [live] |
| `radio_source`, `dont_stop_the_music_enabled` | Deprecated aliases |

`QueueItem`: `{queue_id, queue_item_id, name ("Artist - Title"), duration, sort_index, media_item, image, available, streamdetails, origin (2.11)}`.
`media_item` is a full `Track` (artists and album as ItemMapping). `index` is not filled in
`player_queues/items` (all `0` [live]): use `offset + position in page`. `streamdetails` is large;
ignore it.

### 6.2 Queue commands (`player_queues/controller.py`; read = `queues.read`, write = `queues.control`)

| Command | Args | Returns | Gate | Notes |
| --- | --- | --- | --- | --- |
| `player_queues/all` | none | `[PlayerQueue]` | 28 | [live] |
| `player_queues/items` | `queue_id`, `limit=500`, `offset=0` | `[QueueItem]` | 28 | Plain slice; page it (`:347-352`) [live] |
| `player_queues/play_media` | §6.3 | null | 28 | |
| `player_queues/play_index` | `queue_id`, `index` (int or `queue_item_id`), `seek_position=0`, `fade_in=false` | null | 28 | "Tap to jump" [live] |
| `player_queues/move_item` | `queue_id`, `queue_item_id`, `pos_shift=1` | null | 28 | Positive = down, negative = up, `0` = to the front of upcoming. **Fails for an item at or before `index_in_buffer`** (`:618-640`) [live for a valid move]. Up next drags use the shift; ⋮ "Play next" in Up next uses `0` (a played song falls back to `play_media` "next") |
| `player_queues/delete_item` | `queue_id`, `item_id_or_index` | null | 28 | Pass the `queue_item_id` [live] |
| `player_queues/shuffle` | `queue_id`, `shuffle_enabled` | null | 28 | [live] |
| `player_queues/repeat` | `queue_id`, `repeat_mode` | null | 28 | [live] |
| `player_queues/transfer` | `source_queue_id`, `target_queue_id`, `auto_play?` | null | 28 | "Play on": moves items and position; `auto_play` defaults to "source was playing". Refuses non-playback targets; ungroups a grouped target first (`:1229-1280`). With `auto_play: false` it answers at once and the target rests on the item with the source's `elapsed_time` (603.0 for a book paused at 603) and `resume_pos`; a play then starts there. The source is left empty [live, 2.10.9 and 2.11.0b7] |
| `player_queues/clear` | `queue_id` | null | 28 | Empties the queue but keeps its `resume_pos`: a `play_media` with `add` after it sets the new item current without playing (`elapsed_time` 0), and a plain play then starts that item at the old queue's `resume_pos` (a book added after one paused at 608 s played from 608). Amsel parks the place it wants, so its play is a seek [live, 2.10.9 and 2.11.0b7] |
| `player_queues/play`, `pause`, `play_pause`, `next`, `previous`, `seek`, `stop`, `resume` | `queue_id` (+ `position` for `seek`) | null | 28 | Queue-only equivalents; prefer `players/cmd/*` |

### 6.3 `player_queues/play_media` (`player_queues/controller.py:552-616`; `@2.10.8` same name)

| Arg | Type | Gate | Notes |
| --- | --- | --- | --- |
| `queue_id` | str | 28 | Player id |
| `media` | uri, MediaItem dict, ItemMapping dict, or a list of these | 28 | Send uris; a list plays in order [live] |
| `option` | `play` / `replace` / `next` / `replace_next` / `add` | 28 | `play`: insert at current position and play. `replace`: replace the queue, play from 0. `next`: insert after the current or buffered item. `replace_next`: insert there and drop the rest. `add`: append (`models: enums.py:387-402`). Default (`null`) follows the queue's setting [inf] |
| `start_item` | track uri, or a chapter's `position` | 28 | Start an album or playlist at this track: "tap a track to play from there". For a book, the chapter's `position` as a string (§7.9) |
| `shuffle` | bool | 52 | "Shuffle" button; only for `play`/`replace` |
| `start_from_beginning` | bool | 40 | Podcasts only, not used |
| `sort_field`, `sort_direction` | SortField, SortDirection | 95 | Play the album or playlist in the order the page shows |
| `radio_mode`, `sort_by` | | | Deprecated on 2.11 |

The reply comes only after the play action finishes. It holds the speaker's playback lock
(`player_queues/helpers.py:77-117`). On 2.11 a second play action waits for it strictly, up to 120 s
for the group's lock and again for its own, then fails with error 24 (`players/constants.py:11`,
`players/controller.py:2672-2690`); 2.10 runs it without the lock after 30 s. On a Snapcast test player it answered in 0.16-0.20 s
[live]; on Cast and AirPlay speakers it can take much longer (mobile-app `.claude/architecture.md`) [inf].
So: send it from an app-wide scope, one per queue, show the pending state from the queue's
`extra_attributes.play_action_in_progress`, and drop double taps. Sequence seen after a `replace`
[live]: `queue_items_updated`, about 9 `queue_updated`, 4 `player_updated`.

## 7. Library

### 7.1 Item shapes (`models: media_items/media_item.py`) [live]

- **uri** = `<provider>://<media_type>/<item_id>` (`models: helpers.py:135-137`). Library items use `library://album/1`. Provider items keep raw ids, spaces included: `filesystem_local--GvtMQCEi://track/Test Artist/First Album/01 - Alpha.mp3`. Parse with `split("://", 1)` then `split("/", 1)` (`helpers/uri.py:81-120`). Never URL-encode a uri inside JSON args.
- **Every item**: `item_id`, `provider` (`"library"` or a provider instance id), `name`, `version`, `sort_name`, `uri`, `external_ids`, `is_playable`, `media_type`, `provider_mappings`, `metadata`, `favorite`, `position`, `date_added`.
- **Album** adds `year`, `artists` [ItemMapping], `album_type`. **Track** adds `duration`, `artists`, `album` (ItemMapping), `disc_number`, `track_number`, `last_played`. **Artist** adds `artist_type`. **Playlist** adds `owner`, `is_editable`, `is_dynamic`, `supported_mediatypes`, `access` (71+).
- **ItemMapping** (nested refs and `recently_played_items`): `item_id, provider, name, version, sort_name, uri, external_ids, is_playable, media_type, available, image, year`. `image` is one `MediaItemImage` or null; the server fills it with the item's first `thumb` only (`models: media_item.py`, `ItemMapping.from_item`).
- **Whole items nested** in place of an ItemMapping carry `metadata.images` and no `image` [live]: an album's artists on `music/albums/get` and `music/item_by_uri`, the album of a queue's track (`player_queues/items`, `current_item`), a music service's song's album and artists (`similar_tracks`), and an episode's podcast (`podcast_episodes`, `podcast_episode`, `item_by_uri`, the queue; `in_progress_items` sends none). Library listings and search nest ItemMappings; there only a track's album has an `image`, never an artist. `ItemMapping.cover` reads either.
- **provider_mappings**: `[{item_id, provider_domain, provider_instance, available, in_library, audio_format, url, details}]`. An item is available if any mapping is available on a loaded provider.
- **metadata.images**: `[{type, path, provider, remotely_accessible, proxy_id}]`. `type` is `thumb`, `landscape`, `fanart`, `logo`, `clearart`, `banner`, `cutout`, `back`, `discart` or `other`, which an unknown one reads as (`models: enums.py`, `ImageType`). The recorded libraries hold only `thumb`, and `fanart` on the `test` music service's artists [live]; the rest come from metadata providers such as fanart.tv and TheAudioDB. The cover (`coverOf`) is the first `thumb`, else a `fanart` or `landscape`, else any but `banner`, `clearart`, `discart` and `cutout`, which a square crop would spoil.
- **Summary items.** `library_items` returns slim items by default (`summary=true`, both releases: `media/base.py:603`, @2.10.8 `:492`): fields that are null are **omitted**, `metadata` holds little more than `images`. `summary=false` gives full items. `get`, sub-listings and search return full items.
- **favorite**: 2.10 `bool`, household-wide. 2.11 (78+): `true` like, `false` dislike, `null` no choice, **for the calling user only**; omitted from summary items when null [live].
- **Built-in playlists.** A library always holds MA's own smart playlists (provider `builtin`, `owner: "Music Assistant"`, `is_editable: false`): "All favorited tracks", "Random Artist (from library)", "Random Album (from library)", "500 Random tracks (from library)", "Recently played tracks", "Recently added tracks", "Infinite Mix (library)", "Infinite Mix (favorites)" [live]. The two "Infinite Mix" ones have `is_dynamic: true`. The owner can switch each off in the builtin provider's settings (`providers/builtin/constants.py:41-64`).
- **Telling smart playlists apart** (`MediaItem.isSmartPlaylist`): a provider mapping with `provider_domain: "builtin"` **and** `is_editable: false`. Neither half is enough alone: a playlist made in MA (`music/playlists/create_playlist`) is also `builtin` with `owner: "Music Assistant"`, but `is_editable: true` (`providers/builtin/__init__.py:277-347`, same @2.10.8); a local `.m3u` playlist is `is_editable: false` too ("Amsel Mix" [live]). Library items carry `provider: "library"`, so the provider shows only in `provider_mappings`, which summary items include [live]. `music/playlists/library_items {provider: "builtin"}` filters server-side on both releases [live], but returns the MA-made playlists as well. Amsel keeps an `isSmart` column in the library index (`LibraryRepository.smartPlaylists` and `ownPlaylists`).
- **The Smart Playlists plugin** (`providers/smart_playlist`, a beta plugin in 2.10 and 2.11) adds each rule-based playlist it makes to the library: `owner: "Smart Playlist"`, `is_editable: true`, a provider mapping with `provider_domain: "smart_playlist"` (`smart_playlist/__init__.py:718-723`). `MediaItem.isPluginSmartPlaylist` checks the domain; such playlists also count as smart, and the index keeps a `smartPlugin` column. Home's Smart playlists show the plugin's when there are any, the built-in set otherwise.
- **Radio** (`media_type: "radio"`) adds `duration: 0`, `is_dynamic: false` and on 2.11 `is_endless: true`; no artists, album or year. Its cover is `metadata.images[0]` with a `proxy_id` like any other item [live].

### 7.2 `music/{albums|artists|playlists|tracks|radios}/library_items` (`media/base.py:590-675`)

| Arg | 2.10 (65) | 2.11 (99/100) | Notes |
| --- | --- | --- | --- |
| `favorite` | bool | bool | 2.11: `true` likes, `false` dislikes of the caller |
| `search` | str | str | Server-side name filter |
| `limit`, `offset` | 500, 0 | 500, 0 | Page by 500 |
| `order_by` | `sort_name` (default), `name`, `timestamp_added`, `last_played`, `play_count`, `year`, … each with `_desc` (@2.10.8 `media/base.py:117-145`) | deprecated, still mapped by `legacy_sort` (`music/listing.py:29-39`) [live] | |
| `sort_field`, `sort_direction` | ignored [live] | 86+ | Field must be offered for the type, or error 3 [live] |
| `album_artists_only` | artists only | artists only | Library > Artists |
| `album_types`, `genre`, `provider`, `reachable_via`, `artist_type`, `collapse_collections` | yes | yes | Not needed |
| `played_only` | yes | yes | Recently played, Artists you play most (§7.4) |
| `summary` | `true` | `true` | |
| `random_seed` | no | 98+ | |
| `user` | | | Impersonation, admin only |

Counts: `music/{type}/count {favorite_only?, album_artists_only? (artists)}` returns int.
`music/sort_options {listing}` (86+, `music/controller.py:1121-1128`) returns
`[{field, supports_direction, default_direction, label_key}]`, default first. Observed [live]:
`library_albums` = sort_name, name, timestamp_added, timestamp_modified, last_played, play_count,
favorite_timestamp, year, artist_name, random, random_play_count; `library_artists`,
`library_playlists` and `library_radios` = the same without year and artist_name
(`music_sort_options__library_radios.json`).

**The Library sort button:**

| Amsel choice | 2.11 (86+) | 2.10 |
| --- | --- | --- |
| A–Z | `sort_field: "sort_name", sort_direction: "asc"` | `order_by: "sort_name"` |
| Recently added | `"timestamp_added", "desc"` | `order_by: "timestamp_added_desc"` |
| Recently played | `"last_played", "desc"` | `order_by: "last_played_desc"` |

### 7.3 Pages and sub-listings (all `library.read`; ids are `item_id` + `provider_instance_id_or_domain`, `"library"` for library items)

| Command | Extra args | Returns | Gate | Notes |
| --- | --- | --- | --- | --- |
| `music/albums/album_tracks` | `in_library_only`; 89+: `search, sort_field, sort_direction, limit=500, offset, favorite (96)` | `[Track]` | 28 | 2.10 returns all in one message |
| `music/artists/artist_albums` | `provider_filter?`; 92+: paging/sort; 96: `album_types, favorite` | `[Album]` | 28 | Library artist: a library-only database read on both releases (`artists.py@2.10.8:779-797`). There is **no `in_library_only`** on any artist listing; only `album_tracks` and `artist_audiobooks` take it [live: commands.json] |
| `music/artists/artist_tracks` | as above; 92+ `limit` (default 500) | `[Track]` | 28 | Full tracks, about 2.2 KB each [live]. 2.10 has no `limit` and sends every track of the artist |
| `music/artists/top_tracks` | `provider_filter?`; 92+ paging/sort | `[Track]` | 28 | **Empty for local files** [live]: the first 5 of `artist_tracks` stand in. **The slow call**, see §7.3.1 |
| `music/playlists/playlist_tracks` | `force_refresh`, `allow_dynamic_tracks`; 93+: `limit=500` (0 = all), `offset`, `search`, `sort_*`, `favorite`, `strict_provider_instance` | list of `Track` (or radio/episode) with 1-based `position` | 28 | **2.10 streams** 500-item `partial` chunks and ignores `limit`/`offset`; **2.11 answers one page** (`media/playlists.py:214-300`, @2.10.8 `:127-159`). Items are provider items, not `library://` [live] |
| `music/get_library_item` | `media_type, item_id, provider_instance_id_or_domain` | library item | 28 | Library row for a provider item (2.10 un-favourite) |
| `music/item_by_uri` | `uri` | item | 28 | |

#### 7.3.1 The artist page: where the time goes

`top_tracks` for a library artist asks each streaming service the artist is mapped to for its
top tracks (one network call per service), then looks up every returned track's library row
(`get_provider_artist_toptracks`, both releases). On **2.10** it also asks, in parallel and on
**every request**, each metadata or plugin provider that ranks top tracks (Last.fm
recommendations, which resolves each track through music-service searches)
(`artists.py@2.10.8:552-607`). **2.11** keeps the assembled listing for 15 minutes
(`listing_cache.py`, `LISTING_CACHE_EXPIRATION`) and asks metadata providers only when the music
services return nothing (`artists.py@2.11.0b7:1743-1787`). The other three calls are library
database reads [src].

What Amsel did until beta 2, and does now:

| | Before | Now |
| --- | --- | --- |
| Albums | Shown only after the popular songs had answered | Painted from the library index at once (albums whose first artist is this one), then the server's list on arrival |
| Popular songs | `top_tracks`, then, if empty, `artist_tracks` **after** it: every track of the artist (2.2 KB each) for five rows | 92+: `top_tracks` and `artist_tracks` at once, both `limit: 5`. 2.10: as before, since it has no `limit` and the stand-in is asked only when needed. `top_tracks` gets 10 s; slower or failing, the stand-in shows |
| Library tab | Kinds synced one after another: artists last, after every album page. A provider sync's re-sync ran inside the event loop | Playlists, albums, artists and radio stations sync side by side; the recently-played ranks alongside the full listing. The re-sync runs outside the event loop, 2 s after the last `music_sync_completed` (§9) |

Measured 2026-11-01 over the WebSocket on the local-files test library (3 artists, at most 7
tracks each): median of 15 calls per command, then 60 replayed page visits per version, with the
command sequence of before and now (scripts in the session scratchpad, not kept) [live]:

| Command, library artist | 2.10.9 median (first call) | 2.11.0b7 median (first call) |
| --- | --- | --- |
| `music/item_by_uri` | 1.1 ms (3.4) | 1.3 ms (2.1) |
| `music/artists/top_tracks` | 2.3 ms (**110.5**) | 1.3 ms (**121.4**, then from the cache) |
| `music/artists/artist_tracks` | 2.8 ms (4.6) | 2.1 ms (3.0) |
| `music/artists/artist_albums` | 1.8 ms (2.6) | 1.5 ms (1.7) |
| Page replay: server's albums shown, before / now | 6.3 / 3.5 ms | 7.3 / 4.9 ms |
| Page replay: popular songs shown, before / now | 6.3 / 6.3 ms | 7.3 / 6.8 ms |

With local files the server answers in milliseconds; an artist's first `top_tracks` is the only
call that costs, even with no music service to ask. On a server with streaming services that
call is a network round trip per service plus a library lookup per returned track, uncached on
2.10 [inf from the source above; the offline test server cannot show it]. Before, everything
below the header waited for it, and for local files for a second, full listing after it. Now the
header and the albums paint from the index in the first frame and only the popular songs wait.
An artist with 300 library tracks sent about 650 KB for five popular rows; on 2.11 it is now
about 11 KB [inf: 2.2 KB per full track, measured].

### 7.4 Home sections

Each section's option (`docs/notes/home-sections.md`) asks the library index where it can, and the
server for what changes with every play. The index answers at once and is kept current by events.

| Option | Where | Notes |
| --- | --- | --- |
| Recently played | `music/recently_played_items {limit: 13, media_types: ["album","playlist","artist","radio"], user_initiated_only: true}` | Returns `[ItemMapping]`, newest first, for the calling user (`music/controller.py:1130-1222`, same args @2.10.8). Default `fully_played_only=true`. Playlists and artists are credited when `play_media` resolves them; albums when one of their tracks counts as played (`player_queues/media_resolver.py:1435-1455`, `playback_tracker.py:899-913`). MA's own "Recently played" row uses these args without radio (`providers/recommendations/__init__.py:247-258`). Items carry no artists or owner: each takes the index's row when it has one. Empty: the index's albums, titled "Your albums". Android Auto's Recently played keeps albums, playlists and artists |
| Played here | `music/recently_played_items {…, queue_id: "<player id>"}` | The same list, only what last played on that speaker: the play log keeps one row per item and person with the queue it last played on, and `queue_id` filters on it (`music/controller.py:825-920` @2.10.9, `:1131-1236` @2.11.0b7). A speaker's queue id is its player id. A speaker nothing was played on answers `[]`; Home then shows the whole history |
| Recently played playlists, albums, artists, stations | `music/{type}/library_items {played_only: true}` with the "Recently played" sort | `played_only` filters to items played at least once on both releases |
| Artists you play most | `music/artists/library_items {played_only: true, album_artists_only: true}`, sorted by `play_count` descending: 86+ `sort_field: "play_count", sort_direction: "desc"`, before it `order_by: "play_count_desc"` | Same schema gate as the Library sort (§7.2) |
| Smart playlists, Your playlists | Library index: `smartPlaylists(limit)`, `ownPlaylists(limit)` | the Smart Playlists plugin's, or MA's built-in set when there are none (§7.1); Your playlists are the user's and the music services' |
| New in your library, Favourite albums, Random albums, Favourite playlists, artists and stations, All stations | Library index: `firstRows(type, sort, limit)`, `favorites(type, limit)`, `random(type, limit)` | Random albums shuffle the index's albums, again only on pull to refresh. Stations are empty unless added in MA |
| Pinned | Library index by uri, else `music/item_by_uri` | A pin neither answers keeps its last entry, so it never vanishes offline |

Each option keeps 13 items (one more than any layout shows, so a section knows when to offer See
all). See all asks the same source for 500.

### 7.5 Search: `music/search` (`music/controller.py:610-800`)

Args: `search_query` (str), `media_types` (default all), `limit` (**per type**, default 25),
`library_only` (deprecated on 2.11 in favour of `providers: ["library"]`), `providers` (63+).
Returns `{artists, albums, genres, tracks, playlists, radio, audiobooks, podcasts, sound_effects}`
of full items [live]. The library is searched first and provider duplicates of library items
are dropped, but the lists are **interleaved** across sources with `zip_longest` (`:756`), so
the library is not first. For "your library's matches first", stable-sort each list by
`provider == "library"`. An empty query returns empty lists; results are cached per query and user.
Amsel asks for `media_types: [artist, album, track, playlist, radio, audiobook, podcast]`; stations come back under the
singular key `radio`.

### 7.6 Favourites (`music/controller.py:1575-1686`; scope `library.write`)

| Command | Args | Gate | Notes |
| --- | --- | --- | --- |
| `music/favorites/add_item` | `item` (uri or item) | 28 | Adds a provider item to the library first. 2.10 emits `media_item_updated` with `favorite: true`; 2.11 emits `favorite_updated` [live] |
| `music/favorites/remove_item` | `media_type`, `library_item_id` | 28 | Needs the **library** id; for a provider item get it with `music/get_library_item` |
| `music/favorites/set_item` | `item` (uri or item), `favorite` (`true`/`false`/`null`) | 78 | Like / dislike / clear. Absent on 2.10 [live] |

### 7.7 Playlist editing (`media/playlists.py`; scope `library.write`)

| Command | Args | Returns | Gate | Notes |
| --- | --- | --- | --- | --- |
| `music/playlists/add_playlist_tracks` | `db_playlist_id` (library id), `uris` | `BackgroundTask` | 28 | Runs later. Uris already in the playlist are **silently skipped** [live]. Only for `is_editable` playlists (error 3 otherwise) |
| `music/playlists/remove_playlist_tracks` | `db_playlist_id`, `positions_to_remove` | `BackgroundTask` | 28 | **1-based** `position` values from `playlist_tracks` (`providers/filesystem_local/__init__.py:1260-1263`, `builtin/__init__.py:615-617`). An out-of-range position fails inside the task, not in the reply [live] |
| `music/playlists/move_playlist_track` | `db_playlist_id`, `from_position`, `to_position` | null | 99 | Not in v1 scope |
| `music/playlists/create_playlist` | `name`, `media_types?`, `provider_instance_or_domain?` | `Playlist` | 28 | Not in v1 scope |

`BackgroundTask` = `{id, name, status: pending|running|success|partial_success|failed, progress, last_error, metadata{task_domain, playlist_id,…}}`.
Completion arrives as a `tasks_updated` event whose list holds that `id` with a final status
(`models: enums.py:591-596`), on every connection of the user who sent the command
(`webserver/websocket_client.py:661-672`). A server restart drops the task without a word, so
Amsel stops waiting when the connection that sent it closes, and whether the task ran is then unknown. Re-fetch `playlist_tracks` then; no item event announces the new
contents [inf].

### 7.8 Radio stations (`media/radio.py`; scope `library.read`; same signatures on both releases [live: commands.json])

| Command | Args | Returns | Gate | Notes |
| --- | --- | --- | --- | --- |
| `music/radios/library_items` | as §7.2 (`favorite`, `search`, `limit`, `offset`, sort) | `[Radio]` | 28 | `[]` on a library without stations [live]. Sort fields as `library_radios` (§7.2); 2.10 takes `order_by` [live] |
| `music/radios/count` | `favorite_only?` | int | 28 | `0` on the test library [live] |
| `music/item_by_uri` | `uri` (`library://radio/1`) | `Radio` | 28 | [live] |
| `player_queues/play_media` | `media: ["library://radio/1"]`, `option: "replace"` | null | 28 | Plays like any uri [live]. The queue then holds one item with `media_item.media_type: "radio"` and `duration: null`; the speaker's `current_media` has `media_type: "radio"`, `duration: null`, no artist or album (`player_queues_get__radio.json`, `players_get__radio.json`) |
| `music/favorites/set_item` (78+) / `add_item` | `item: "library://radio/1"` | null | 28 | Like any item; `library_items {favorite: true}` then lists the station [live] |
| `builtin/add_radio` | `url`, `name`, `image_url?` | `Radio` (builtin provider item) | 28 | Scope `library.write`. Adds a stream URL as a station; the library sync adds it about 1 s later as `library://radio/<n>`. Admin task, **not for Amsel** (web UI); the fixture recorder uses it |

The test library has no stations, so `record_fixtures.py --radio-url` adds one: `record.sh` serves
one library file on the container's own loopback (`python3 -m http.server --bind 127.0.0.1`) and
adds `http://127.0.0.1:8777/01%20-%20Tailwind.mp3` as "Amsel Test Radio". `--only radios` adds just
these fixtures to an existing fixture directory. Fixtures: `music_radios_*`, `builtin_add_radio`,
`music_item_by_uri__radio`, `player_queues_{play_media,get,stop}__radio`, `players_get__radio`,
`music_favorites_{add_item,set_item}__radio`, `music_sort_options__library_radios` (2.11).

### 7.9 Audiobooks and podcasts (2026-11-02; local files and Audiobookshelf 2.37.2 on 2.10.9 and 2.11.0b7 [live])

- **Audiobook** adds `publisher`, `authors` and `narrators`, `duration` (seconds, the whole book), `fully_played` and `resume_position_ms` (the calling user's; omitted from summary items while null), and `metadata.chapters` `[{position, name, start, end}]` in seconds, on `get` and on the queue item only. Authors are library artists in 2.11 (`artist_type` author or narrator), plain strings from 2.10 local files, artist objects from Audiobookshelf on both. A chapter's `position` is 1-based for chapter files and Audiobookshelf, 0-based for one file with embedded chapters; `end` may be null [live].
- **Podcast** adds `publisher` and `total_episodes` (0 for local files). **PodcastEpisode** adds `podcast` (ItemMapping or full Podcast), `position`, `duration`, `episode_number`, `season` (85+), the progress fields and `metadata.release_date`. `position` is 1..n for local and RSS feeds, but **Audiobookshelf sets it to the publish time in epoch milliseconds** (about 1.7e12, `providers/audiobookshelf/parsers.py:209-215`), past Int. Audiobookshelf episode ids hold a space: `"<podcast id> <episode id>"`.
- Amsel reads `position` as a Long and every number leniently (`Lenient.kt`); a nested item that does not decode is null, so a queue is never dropped for its current item.

| Command | Args | Returns | Gate | Notes |
| --- | --- | --- | --- | --- |
| `music/audiobooks/library_items`, `music/podcasts/library_items` | as §7.2 | summary items | 28 | Sort fields as albums' without `year` and `artist_name`; audiobooks add `duration` [live] |
| `music/podcasts/podcast_episodes` | 93+: `offset`, `limit` (500, 0 = all), `sort_field` (`position`), `sort_direction` (desc), `fully_played`, `search` | `[PodcastEpisode]` with the caller's progress | 28 | 2.10 streams 500-item partials, oldest first, and ignores all but the ids [live]; `MaApi.podcastEpisodes` sorts and pages there |
| `music/podcasts/podcast_episode` | ids | one episode, chapters included | 28 | |
| `music/in_progress_items` | `limit` (10) | `[ItemMapping]`, last played first | 28 | Books and episodes with progress and not finished, without position or duration; episodes only if played in the last 7 days (`music/controller.py:1367-1373`). Books arrive as `library://audiobook/N`, episodes as provider uris [live] |
| `music/recommendations/items` | `provider: "recommendations"`, `item_id: "latest_episodes"` | full episodes | 2.11 | The newest episode of every library podcast, newest first, with progress; refreshed every six hours, so empty for a while after the first start [live]. 2.10 answers `[]` |
| `music/mark_played`, `music/mark_unplayed` | `media_item`: `{item_id, provider, name, media_type}` | null | 28 | **An object, never a uri** (error 999 on both [live]). Scope `library.write`. Unplayed deletes the caller's row: both progress fields read null afterwards [live] |
| `player_queues/play_media` | a book uri, `start_item` | null | 28 | Without `start_item` the server resumes from its playlog or Audiobookshelf's, whichever is newer, and from 0 once finished; `start_item: "3"` started 3 s into chapter 3 [live]. A podcast uri queues every episode from the oldest unplayed on; an episode uri plays that one, resumed |
| `players/cmd/seek` | `position` (int s) | null | 28 | Past the duration: error 12 [live]. Back 15 s and forward 30 s are seeks clamped to `[0, duration - 1]` here, not `player_queues/skip`, which errors past the end on 2.10 |

- **Queue.** `playback_speed` (set in Music Assistant, 0.5 to 3, kept per item) moves the position: `elapsed_time + (now - elapsed_time_last_updated) * playback_speed`. Chapters come only with `current_item.media_item`; nothing on the server names the current chapter: it is the last whose `start` is at or before the position.
- **Events.** `playlog_updated` `{uri, media_type, fully_played, seconds_played, userid}` comes on pause, stop, end, a queue change and every mark, **for every user, to every socket**: apply it only when `userid` is the signed-in user's or null. Its uri is the library uri for a book (`library://audiobook/2`) and the provider's uri for an episode, from a play and from an Audiobookshelf sync alike [live]. `media_item_played` comes every 30 s while playing (`is_playing: true`).
- **Seek** (`player_queues/seek`, also behind `players/cmd/seek`) cuts the position to whole seconds and restarts the stream through `play_index`, which takes a position of 0 on a book or episode as "resume from the bookmark": seek a spoken item to at least 1 s, and round a chapter's start up. It answers once the new stream runs, which can take long: inside one file of a multi-file book the server seeks by decoding up to the place (`get_multi_file_stream`: concat demuxer, `-ss` after `-i`), 9.5 s for 3500 s into an hour-long 32 kbit/s file [live, 2.10.9]; meanwhile it reports the target with `play_action_in_progress`, or 0. A seek therefore stands at its target until the command is answered, then until a report fits it, 30 s at most (`Seek.Flying`). Item events (`media_item_updated`, `media_item_added`) are built without a session user, so their `resume_position_ms` and `fully_played` are the last listener's, whoever that was (`audiobooks.py` `base_query`).
- **Position after a seek or a play** [live, 2.10.9, Sendspin and the fake player, local files and Audiobookshelf]: the queue first reports the target (`play_action_in_progress` true), then for 0.3 to 1 s the old stream's time added to the target (a seek 20 s after the stream started reported 1020.27 for 1000; a book started at chapter 25 reported its old place, then 1024.34, then 959.25), and only then `queue_time_updated` with the target. In flow mode `_get_flow_queue_stream_index` gives 0.0 while the new stream has logged no item and the player still plays, and `on_player_elapsed_time_corrected` ignores the transition flag. The same on 2.11.0b7 [live: 7.6 s played, a seek to 600 reported 607.58, then 600.25 0.35 s later]. `Speakers` shows the target, standing still, until the command is answered and a report fits it: at the target, or past it by no more than the time since the command went out (`Seek.Flying.fits`). Where the old stream was only a second or two old, its time on top of the target fits too (a song 2.1 s in, sought to 4 s, reported 6.15 on both releases, found by the app's journey test), so no report ends a seek in the first second after the answer: the latest one counts then (`Seek.SETTLE_MS`), which also covers a report that came in the same moment as the answer with none after (a paused queue's seek: `playing 2000.25`, then the answer).
- **A seek starts playback, and costs the server a stream** [live and source, 2.10.9 and 2.11.0b7]. `player_queues/seek` is `play_index(current_index, seek_position)`: a paused or stopped queue plays after it. The new position gets a new `AudioBuffer` and decoder unless it lies in what the old buffer holds or within 20 s ahead of it (`audio_buffer.py` `is_valid`, `SEEK_WAIT_THRESHOLD`); the old buffer and its ffmpeg processes are not stopped but left to `_monitor_inactivity`, which clears a buffer 300 s after its last read. Measured on 2.10.9 with a six-file book from Audiobookshelf on a Sendspin speaker, from 0 ffmpeg and 416 MB: twenty seeks 0.5 s apart and 537 s apart in the book left 33 ffmpeg processes and 2.61 GB (2.53 GB 30 s later, gone after five minutes); the same twenty taps through `SeekLane` sent one seek and left 4 processes and 793 MB. Seeks 30 s apart mostly reuse the buffer (twenty took it from 2 processes and 643 MB to 7 and 1.03 GB). So Amsel sends one seek at a time, taps that follow one another within 600 ms as one, and none to a queue that does not play: the place is parked and goes out with the next play (`SeekLane`, `Seeks`).
- **`start_item`** names a chapter by `position`, and the server takes the first chapter with that position in its whole stored list: after a merge (the same book from files and from Audiobookshelf, or an Audiobookshelf edit) a current chapter's position can name a stale one elsewhere (chapter 25 started chapter 26). `MediaItem.startItemFor` picks a position whose first chapter starts there. A chapter starts 0.5 s early (`_get_resume_position` subtracts 500 ms).
- **Audiobookshelf** [live]. A multi-file book is one item on one timeline: `elapsed_time`, seeks and chapter starts are whole-book seconds and cross file boundaries. Progress goes to Audiobookshelf every 30 s while playing and on pause, but the provider **drops any update for an item within 8 s of its previous one, marks included** (`audiobookshelf/helpers.py` `ProgressGuard`); 2.11's next sync from Audiobookshelf (about 10 s after a change there) then sends Audiobookshelf's state back as `playlog_updated` with `userid: null`, one per progress row, and undoes such a mark. 2.10.9 sent nothing within 15 s of a change made in Audiobookshelf and kept the old progress in its items, while `play_media` already resumed from the new one. After a chapter edit in Audiobookshelf the server appends the new chapters to the stale ones (`MediaItemMetadata.update` merges lists; dev `audiobooks.py` replaces only images and collections); `MediaItem.chapters` keeps the last chapter at each position and drops a stored one that overlaps the chapter before it. Each 2.11 sync also re-sends every Audiobookshelf progress row, changed or not, so the index writes only what changed.
- **Not used yet:** `player_queues/set_playback_speed`, `start_from_beginning`, `music/artists/artist_audiobooks`, and the server's bookmarks (branch `audiobook_bookmarks`, schema 102 there): Amsel keeps bookmarks on the phone and follows a chapter's end with the plain timer.

### 7.10 Audiobookshelf for downloads (2026-11-08; Audiobookshelf 2.37.2 [live])

Music Assistant streams Audiobookshelf's books but cannot hand out their files, so downloads use Audiobookshelf's own API. An item there is `provider_mappings[provider_domain == "audiobookshelf"].item_id`; an episode's is `"<podcast id> <episode id>"` (`audiobookshelf/parsers.py`). The provider's url and login are setup values, which `config/providers {include_values: true}` leaves out on 2.10.9 and 2.11.0b7 [live].

| Request | Returns | Notes |
| --- | --- | --- |
| `POST /login {username, password}`, header `x-return-tokens: true` | `user.accessToken` (1 h), `user.refreshToken` | A server before 2.26 sends only `user.token`, which never runs out |
| `POST /auth/refresh`, header `x-refresh-token` | both tokens again | The refresh token rotates: keep the new one. 404 before 2.26; 401 once revoked |
| `GET /api/items/{id}?expanded=1` | `media.tracks[]` (`startOffset`, `duration`, `contentUrl` `/api/items/{id}/file/{ino}`, `metadata.ext`, `metadata.size`), `media.chapters[]` (`start`, `end`, `title`), `media.metadata` (`title`, `authorName`), a podcast's `media.episodes[]` (`id`, `title`, `audioTrack`) | Tracks in timeline order; one m4b or many mp3s [live] |
| `GET /api/items/{id}/file/{ino}/download` | the file | `Range: bytes=N-` answers 206 [live] |
| `GET /api/items/{id}/cover` | the cover | 404 when there is none |
| `GET`, `PATCH /api/me/progress/{id}[/{episode id}]` | `currentTime`, `duration`, `isFinished`, `lastUpdate` (ms) | GET is 404 before the first play; PATCH takes `currentTime`, `duration`, `progress`, `isFinished` |

### 7.11 Extras: endless mix, similar artists and songs, the DJ (2026-11-12; 2.10.9 and 2.11.0b7 [live])

Each is offered only where the server has what it takes, read from `providers` (`Extras` in `:data`). None needs a schema gate: the commands and the two plugins are in both releases.

| Command | Args | Returns | Scope | Notes |
| --- | --- | --- | --- | --- |
| `providers` | none | `[{type, domain, instance_id, name, available, supported_features, is_streaming_provider, lookup_key}]` | `providers.read` | A guest holds it. Lists only what is loaded: a plugin that is switched off or was never set up is absent, and its commands answer error 12. `providers_updated` carries the new list [live] |
| `player_queues/play_media` | `media: ["radio_playlist://playlist/<seed uri>"]`, `option: "replace"` | null | `queues.control` | Plays the endless mix of a song, album, artist or playlist, made by the built-in plugin `radio_playlist`. The seed uri goes in raw |
| `music/playlists/playlist_tracks` | `item_id: <seed uri>`, `provider_instance_id_or_domain: "radio_playlist"` | `[Track]` | `library.read` | A sample of what the mix would hold, nothing played; 0 to 1 s here |
| `music/artists/similar_artists`, `music/tracks/similar_tracks` | ids, `limit: 25` | `[Artist]`, `[Track]` | `library.read` | 2.11 adds `search`, `sort_*`, `offset`, `random_seed`; not sent |
| `ai_radio/hosts/list` | none | `[{id, name, instructions, tts_engine, language, …}]`, by name | `config.providers.read` | A guest lacks it: error 22 [live]. A host has nothing to show but `name` |
| `ai_radio/queue_dj/status` | none | the DJ of every queue the user may use | `queues.control` | |
| `ai_radio/queue_dj/set` | `queue_id`, `host_id` (string or `null`) | the same map after the change | `queues.control` | Unknown host: error 3 [live] |

- **The mix's queue** has `is_dynamic: true`, `shuffle_enabled: true` and one `sources[]` entry with `provider: "radio_playlist"`, `item_id` = the seed uri, `name` = `"<seed's name> Endless Mix"`, English in every language (`providers/radio_playlist/__init__.py:84`). `is_dynamic` alone is also true for the "Infinite Mix" playlists (`PlayerQueue.endlessMixOf`).
- **Always `option: "replace"`**, never `radio_mode` (deprecated; it gives the same queue on both releases and a warning in the server's log). Never `add` or `next` with a mix uri: what plays stays, but the whole queue turns into a mix, shuffle forced on, with the mix as one more source [live: `sources` = the album, the mix].
- **The sample comes first** (`playEndlessMix`). `[]`: the seed has no songs. One song: a song with nothing like it, which would play over and over. Fewer than two different songs start nothing. Error 2 on the sample: the seed is gone. No answer in 5 s: the server is looking for similar songs, so the mix is started. Started for a seed without songs, `play_media` answers error 2 "There is nothing to play here." only after it has emptied the queue, which stays `is_dynamic` with `items: 0` until `player_queues/clear` [live].
- **Repeat is carried in and locked.** A mix started with `repeat_mode: "all"` keeps it, and `player_queues/repeat` with another mode answers error 12, as `shuffle` with `false` does, until `player_queues/clear` or a plain `replace`; the value the queue already has is accepted [live]. So `repeat` goes `off` before the mix starts, and where a mix already holds another mode the queue is cleared first.
- **Play next and Add to Up next in a mix.** `play_media` with `next` and one song inserts that song after the current or buffered item. `add`, or `next` with anything but a song, makes the item one more source and rebuilds what is coming from all sources. A plain source leaves `sources` once all its songs are in the queue (`player_queues/managed_pool.py`, `_retire_sources`): during the command on a server where none of them was played lately, at the next refill about 5 s later otherwise [live: fresh servers, and ones played on].
- **Similar: the request.** `limit` is always sent: 2.11 answers 25 at most whatever is asked, 2.10.9 as many as asked (`limit: 100` gave 25 and 100) [live]. Never `limit: 0`, which is "all" on 2.11 and nothing for a library artist on 2.10 (`artists.py@2.10.9:895`). Never `allow_lookup`: the server then searches other music services and writes what it finds into the library's track (`media/tracks.py:2098`) [src].
- **Similar: the rows.** Songs are the music service's own items (`provider` = its instance id, uri `<instance>://track/<id>`, whole nested artists and album), also where the library holds the song, and play by uri like any other [live: the `test` service]. The song asked for can be among them under another id (`withoutSeed`). Artists are the library's where it holds one, else the service's [src]; no source that works offline has any. Local files answer `[]` to both; an unknown library id is error 2 [live]. A music service that fails can answer `similar_tracks` with its own error; `similar_artists` of a library artist never does [src].
- **Who has similar ones.** `supported_features` in `providers` holds `similar_tracks` or `similar_artists`. Last.fm (`lastfm_recommendations`, loaded by default) declares both, so the flags say who may be asked, not that anything comes back.
- **The DJ's commands exist only while the plugin `ai_radio` is loaded**; otherwise each answers error 12 on both releases [live]. It loads only with an AI engine and a speech engine from other plugins (`openai_compatible`, `openai_tts`, `hass`).
- **DJ state.** 2.11: `{queue_id: {host_id, station_id}}`; 2.10: `{queue_id: "host_id"}`; a queue without a DJ has no key, so all off is `{}` [live]. `station_id` not empty: a show put the host there [src]. `host_id` is always sent, `null` for off. A guest may read and set it but cannot list the hosts [live].
- **DJ changes.** 2.11 sends `provider_event` with `object_id: "ai_radio"` and `data: {"event": "queue_dj_updated"}` after every `set`, also one that changed nothing; 2.10.9 sends nothing [live], so Amsel reads the state again before it shows it. Nothing on the queue object tells of the DJ. What it says is a queue item with `media_item.media_type: "sound_effect"` and `provider: "ai_radio"` [live, 2.11.0b7].

Fixtures: `player_queues_get__endless_mix`, `music_tracks_similar_tracks`, `music_artists_similar_artists`, `ai_radio_*`, `event_provider_event__queue_dj_updated` (2.11). Live: `LiveServerTest` on the `test` music service `setup.py` adds (§11), and `LiveDjTest` where the plugin is loaded.

## 8. Images

Build every artwork URL as `<connection base>/imageproxy/<proxy_id>?size=<s>` (`metadata/images.py:327-362`,
identical @2.10.8 `:332-367`) [live]:

- `proxy_id` (64 hex chars) is on every `MediaItemImage` the server sends, including `remotely_accessible` ones. Schema 31+.
- `size` must be one of **0, 80, 160, 256, 512, 1024** (0 = original); anything else is HTTP 400 [live] (`metadata/constants.py:137`). Round the needed pixel size **up** to the next allowed value, as the web UI does (frontend `helpers/utils.ts:277-288`).
- `fmt` is optional: `jpg`, `png`, `jpeg` (flattens transparency onto white), `svg`. Default keeps the source type; transparent images may come back as PNG.
- **No auth**: 200 without any header [live]. Response has `Cache-Control: max-age=31536000`, so Coil's disk cache can keep it.
- Use the address the app connected with, not `server_info.base_url` (§2.2). `current_media.image_url` is already a full URL on `base_url`: put its path from `imageproxy` on the connection base and ask the size anew. An outside source's own image address (not under `imageproxy`) is not fetched.
- The id stays valid for a year after its last use (`metadata/constants.py:133`). A 404 means "no image": show the placeholder.
- **Which picture** (`MediaItem.cover`): the item's own (`coverOf`, §7.1), else its `image`, else its album's, else its podcast's; a nested item's is its `image`, else its own `metadata.images`. A `QueueItem` has an `image` of its own besides: the server's choice.
- **The server's own stand-ins** [src]: `QueueItem.image` is the item's first `thumb`, for a track its album's before its own, for an episode its podcast's after its own (`models: queue_item.py`, `get_image`); `current_media.image_url` is the same picture at size 512 (`models/player.py:3184-3232`). `get_image_url_for_item` (`metadata/images.py:187-238`, @2.10.8 `:195-243`) goes on to an album's first artist and a track's first artist, but only the Music Quiz plugin calls it. Nothing stands in for an artist's own picture: Amsel's index gives an artist without one the covers of its albums (`albumCovers`).

## 9. Events

All events reach an authenticated socket automatically (`websocket_client.py:529-530`,
`:585-691`). Frame: `{"event", "object_id", "data"}`. Ignore event names the app does not know; newer servers add them.

| Event | object_id | data | Notes |
| --- | --- | --- | --- |
| `player_added` | player_id | `Player` | |
| `player_updated` | player_id | `Player` (full) | Volume, state, `current_media`, … [live] |
| `player_removed` | player_id | null | |
| `queue_added` | queue_id | `PlayerQueue` | |
| `queue_updated` | queue_id | `PlayerQueue` (full, incl. `current_item`) | Frequent: about 40 during a dozen commands [live]. A removed player's queue arrives as `idle` here; there is no queue-removed event |
| `queue_items_updated` | queue_id | **`PlayerQueue`, not the items** | Re-fetch with `player_queues/items` [live] (`player_queues/controller.py:1814-1822`) |
| `queue_time_updated` | queue_id | float, elapsed seconds | Sent on seeks or jumps of more than 2 s and when the player's real position drifts (`player_queues/controller.py:1433-1471`, `playback_tracker.py:250-266`), not every second: extrapolate from `elapsed_time_last_updated` [live] |
| `media_item_added` / `media_item_updated` / `media_item_deleted` | item uri | the item | 2.11 sends `favorite: null` in all of them; keep the row's own favourite (`websocket_client.py:652-659`) |
| `favorite_updated` | item uri | `{uri, media_type, item_id, favorite, user_id}` | 78+. Only the user's own changes are sent [live] |
| `media_item_played`, `playlog_updated` | item uri | play info | Refresh Home's sections, 2 s after the last; not for `media_item_played` with `is_playing: true` (every 30 s while anything plays) or another user's `playlog_updated` (§7.9) |
| `music_sync_completed` | null | null | Provider syncs **suppress per-item events**; refresh the library index on this one (`music/controller.py:3409-3422`, `:3476-3482`; @2.10.8 `:2864`, `:2924`). One arrives per provider sync, so wait 2 s for the burst to settle, and never sync inside the event loop: a full sync takes seconds and the events behind it (players, queues) would wait, or be dropped once 512 are buffered |
| `tasks_updated` | null | `[BackgroundTask]` of this user | §7.7 |
| `providers_updated` | null | the list `providers` answers | A plugin or a music service came or went: Amsel reads again what the server offers (§7.11) [live] |
| `provider_event` | provider instance id | `{event}` | 2.11's AI Radio plugin: `queue_dj_updated`, `hosts_updated`. No state, re-fetch (§7.11). 2.10.9 sends none [live] |
| `core_state_updated` | null | `server_info` | e.g. `status` → `stopping` |

Also sent but not used: `player_config_updated`, `player_options_updated`, `setup_flow_updated`, `dashboard_*`, `sync_tasks_updated`.
`application_shutdown` is deprecated, and `auth_session` is never sent.
A personal playlist the user can no longer see arrives once as `media_item_deleted` (2.11, `:694-717`).

## 10. Limits and gotchas

1. **Message size.** The server sets no outbound size limit (`websocket_client.py` sends whatever it serialises); the official app's notes say "about 9 MB" (`mobile-app .claude/architecture.md:66`), not confirmed in server source. Inbound frames use aiohttp's default `WebSocketResponse` limit, 4 MiB [inf]. Page every list at 500 and never send `limit: 0`.
2. **Paging.** `library_items`, `player_queues/items` and the 2.11 sub-listings default to 500. 2.10 `playlist_tracks` streams `partial` chunks and ignores `limit`.
3. **`play_media` blocks** until the play action ends (§6.3); on 2.11 error 24 after 120 s per lock behind another action.
4. **Deprecated, still accepted on 2.11:** `order_by` (use `sort_field`), `play_media(radio_mode, sort_by)`, `library_only` on search, and the aliases `display_name`, `state`, `group_childs`, `hide_player_in_ui`, `radio_source`, `dont_stop_the_music_enabled`.
5. **Favourites switch models at 78:** on 2.10 `favorite:false` means "not a favourite"; on 2.11 it means dislike and `null` means none.
6. **`base_url` may be unreachable** from the phone (container address [live]). Use it only as a discovery hint, verified by `/info`.
7. **Search interleaves** library and provider results (§7.5).
8. **Queue reorder** cannot touch items at or before `index_in_buffer`.
9. **`auth/logout` closes the socket** before replying; treat the close as success.
10. **Long-lived tokens expire after 365 days without renewal**; a guest cannot make one at all.
11. **Commands may be refused while `status` is `starting`** [inf]: wait for `core_state_updated` with `running` before the first sync.
12. **Built-in smart playlists** fill the playlist list (§7.1); tell them apart by their `builtin` mapping and `is_editable: false`, never by owner alone.
13. **`top_tracks` is the slow artist call** (§7.3.1): never let the rest of a page wait for it.

## 11. Test server recipe (what worked on both images)

1. `docker run -d -p 8095:8095 -v <data>:/data -v <music>:/media ghcr.io/music-assistant/server:<tag>`. 2.11 only accepts music folders inside a detected storage location, and in a container that means a mounted volume (`/media` worked; `/data/music` was refused). 2.10.8 accepts any path.
2. `POST /setup {"username","password","display_name"}` creates the first admin and returns `{success, token}` [live].
3. Add a local-files library with `config/providers/setup {provider_domain: "filesystem_local"}`, then `config/flows/submit {flow_id, values: {content_type: "music", path: "/media"}}`. `config/providers/save` refuses new providers ("only possible through the setup flow") [live].
4. A real player with no hardware: `config/providers/setup {provider_domain: "snapcast"}` starts the built-in Snapserver; then `snapclient --host 127.0.0.1 --player file:filename=null --hostID kitchen` inside the container. On a host without IPv6, first change `#bind_to_address = ::` to `bind_to_address = 0.0.0.0` in `/etc/snapserver.conf`, or setup fails with error 5 [live].
5. A guest user for scope tests: `auth/user/create {username, password, role: "guest"}`.
6. Similar songs without a music service: `config/providers/setup {provider_domain: "test"}` adds the image's own test service at once (instance `test`, no form), and `config/providers/save {provider_domain: "test", instance_id: "test", values}` with every `num_*` at 0 and every `library_sync_*` false keeps it out of the library; left as set up, its first sync 10 s later adds 500 songs, 5 podcasts and 20 books. `similar_tracks {item_id: "0_0_0", provider_instance_id_or_domain: "test"}` then answers `0_0_1` to `0_1_5`, and they play (60 s of silence each) [live]. `setup.py` does this.
7. The DJ without an AI: `openai_compatible` (flow values `service: "custom"`, then `base_url`; then save `models`) and `openai_tts` (`base_url`, `model`, `response_format`) set up against any address that answers `GET /models`, `GET /audio/voices` and `POST /audio/speech`, then `ai_radio` with `ai_engine` and `tts_engine` from its form's options. `config/providers/remove {instance_id}` takes each away again, `ai_radio` first [live].

## Sources

Server (`/home/user/ref/server`, dev @ acea6207, tag 2.10.8): `music_assistant/constants.py`,
`mass.py`, `helpers/api.py`, `helpers/uri.py`, `helpers/redirect_validation.py`,
`helpers/resources/oauth_callback.html`, `controllers/webserver/{controller.py, websocket_client.py, auth.py, README.md, helpers/auth_middleware.py, helpers/auth_providers.py}`,
`controllers/discovery/controller.py`, `controllers/players/{controller.py, constants.py}`,
`controllers/player_queues/{controller.py, helpers.py, media_resolver.py, playback_tracker.py}`,
`controllers/music/{controller.py, listing.py, sorting.py, media/base.py, media/playlists.py, media/albums.py, media/artists.py}`,
`controllers/metadata/{images.py, constants.py}`. Models (`music-assistant/models` 1.1.232 and
1.1.205): `api.py`, `event.py`, `enums.py`, `errors.py`, `auth.py`, `player.py`,
`player_queue.py`, `queue_item.py`, `favorite_update.py`, `media_items/{media_item.py, metadata.py, summary.py}`.
Clients: `/home/user/ref/client/music_assistant_client/{client.py, connection.py, auth_helpers.py, constants.py}`;
`/home/user/ref/frontend/src/{plugins/api/index.ts, helpers/utils.ts, helpers/players.ts}`;
`/home/user/ref/mobile-app/{.claude/architecture.md, composeApp/src/commonMain/kotlin/io/music_assistant/client/auth/, .../api/Request.kt, .../data/model/server/}`.
Live probes: `/api-docs/commands.json` of both servers, WebSocket scripts against both, mDNS browse from inside the containers.
