# Music Assistant test server

A throwaway, headless Music Assistant (MA) server in Docker with a tiny synthetic library,
plus the scripts that record the WebSocket fixtures in `api/src/testFixtures/resources/fixtures/`.
No browser step anywhere: onboarding, the music source and the library sync all go through
the server's own HTTP API.

Needs: Docker, `python3` (3.10 or later, standard library only) and `curl`. The test library is
made with the `ffmpeg` on the PATH (with libmp3lame and libvorbis) or, where there is none, with
the server image's own, so CI installs nothing.

| Script | Does |
| --- | --- |
| `make-library.sh [dir]` | Writes the test library (default `build/test-library`, git-ignored): 3 artists, 4 albums, 12 tracks (FLAC, MP3, Ogg Vorbis, 12 to 20 s sine tones), cover art as `folder.jpg` or embedded, artist images, one `.m3u` playlist. About 1 MB, CC0. |
| `start.sh <tag> [library-dir]` | Starts `ghcr.io/music-assistant/server:<tag>` as container `amsel-ma-<tag>`, library mounted read-only at `/media`, anonymous `/data` volume. Waits for `/info` to say `"status": "running"` (about 20 s), then prints the base URL. Env: `PORT` (default 8095), `NETWORK=host`, `NAME`, `PULL=1`. |
| `setup.py [--url URL]` | Creates the admin `amsel` / `amsel-test-pw`, adds the local files source for `/media`, syncs, checks there are 12 tracks, prints a long-lived token on stdout (logs go to stderr). Safe to run twice: on an onboarded server it logs in instead. Takes about 2 s. |
| `fake_player.py` | A fake speaker: a minimal Sendspin player that registers with the server and throws the audio away. `MA_TOKEN=… ./fake_player.py --name Kitchen --id amsel-fake-kitchen` |
| `record_fixtures.py --out DIR [--radio-url URL] [--only radios]` | Records request/response pairs over the WebSocket into `DIR` (needs `MA_TOKEN` or `--token`). `--radio-url` is a stream the server can reach: it adds it as the radio station "Amsel Test Radio", lists, favourites and plays it. `--only radios` records just that section into an existing `DIR` and adds the files to its `_meta.json`. |
| `record.sh <tag> <dir>` | The whole loop from a clean server: start, setup, serve one library file as a radio stream on the container's own loopback, record into `api/src/testFixtures/resources/fixtures/<dir>`, stop. About one minute. |
| `stop.sh <tag>` | Removes the container and its data volume. |
| `ma_client.py` | Shared stdlib helpers: JSON over HTTP, `POST /api` JSON-RPC, a small RFC 6455 WebSocket client. |

## Pinned versions

The plan supports the current and previous release, gated on the schema number. These are
the images the fixtures were recorded from (`latest` and `beta` pointed at the same digests on
1 Nov 2026):

| Fixture dir | Image tag | Digest | `server_version` | `schema_version` | `min_supported_schema_version` |
| --- | --- | --- | --- | --- | --- |
| `2.10` | `2.10.9` (= `latest`) | `sha256:4ac0d5844363fbbefd6a61251df09d96cd546e05e01b8c1cc1a28803f0935e07` | `2.10.9` | 65 | 28 |
| `2.11` | `2.11.0b7` (= `beta`) | `sha256:db852af728e33ed69216800e58f455f1977ca759847e6a203086523382319be9` | `2.11.0b7` | 99 | 28 |

Use the exact tags, never `latest`/`beta`, in CI so a release cannot change the server under a
passing build. When 2.11 goes stable, re-record `2.11` from the stable tag.

## For humans

```sh
cd scripts/test-server
./start.sh 2.11.0b7                       # prints http://localhost:8095
export MA_TOKEN="$(python3 setup.py)"
python3 fake_player.py --name Kitchen &   # optional: a speaker to play on
python3 fake_player.py --name "Living Room" --id amsel-fake-living-room &
# web UI: http://localhost:8095, sign in as amsel / amsel-test-pw
# API reference of this exact server: http://localhost:8095/api-docs
./stop.sh 2.11.0b7
```

An Android emulator reaches the host's port 8095 at `http://10.0.2.2:8095`. Run two versions
side by side with `PORT=8096 ./start.sh 2.10.9`.

## For CI

The `live-server` job in `.github/workflows/ci.yml` starts the server, signs in and runs the fake
speakers.

The image is about 3.2 GB unpacked (756 MB to 775 MB compressed), so pulling it is the slow part; the
server itself is ready about 20 s after `docker run`. A matrix over `2.10.9` and `2.11.0b7`
covers both supported versions.

Unit tests should not need Docker: they read the recorded fixtures.

## Fixtures

`api/src/testFixtures/resources/fixtures/<2.10|2.11>/<command_with_underscores>[__variant].json`, each

```json
{"request": {"message_id": "5", "command": "music/albums/get", "args": {...}},
 "response": {"message_id": "5", "result": {...}, "partial": false},
 "partials": [ ... only when the server streamed the answer in several frames ... ],
 "notes": "only when something about the recording is worth knowing"}
```

- `request` is `null` for messages the server pushes: `server_info.json` (the first frame on
  every connection) and `event_<event>.json` (one example of each event type seen).
- Errors are recorded too: `players_all__unauthenticated.json` (code 20),
  `auth__invalid_token.json` (code 23), `music_albums_get__not_found.json` (code 2),
  `auth_login__wrong_password.json` (a normal result with `"success": false`).
- `http_info.json` is the unauthenticated `GET /info`.
- `_meta.json` lists the versions, the recording time, the files and how many events of each
  type arrived.
- Every JWT is replaced by `<redacted-jwt>`; nothing else is edited. Ids (`server_id`,
  `user_id`, player ids such as `up35c29dbb`, `queue_item_id`, image `proxy_id`), timestamps
  and the container address in URLs (`http://172.17.0.4:8095`) change on every recording.

Re-record after a server update with `./record.sh 2.10.9 2.10` and `./record.sh 2.11.0b7 2.11`,
then review the diff: it is the list of API changes.

## How the headless setup works

Checked against the source inside both images (`/app/venv/lib/python3.14/site-packages/music_assistant`).

1. **First admin.** `POST /setup` with JSON
   `{"username", "password", "display_name", "device_name"}` (username at least 2 characters,
   password at least 8) creates the admin and returns `{"success": true, "token": "<jwt>", "user": {...}}`.
   The token is short-lived (30 days, renewed on use). Once a user exists the same call
   answers `"Setup already completed"`, with HTTP 400 on 2.10.9 and 409 on 2.11.0b7. 2.10.9
   ignores `display_name`.
2. **Login on later runs.** `POST /auth/login` with
   `{"provider_id": "builtin", "credentials": {"username", "password"}, "device_name"}`.
   Over the WebSocket the same is the unauthenticated command `auth/login`
   `{"username", "password", "device_name"}`, which returns `access_token`.
3. **Long-lived token.** Command `auth/token/create` `{"name"}`: one year, never renewed.
4. **Commands over HTTP.** `POST /api` with `{"message_id", "command", "args"}` and
   `Authorization: Bearer <token>` returns the bare result (no envelope). Errors are plain text
   with a status: 400 bad JSON, unknown command or invalid data, 401 no or bad token, 403
   missing scope, 404 player unavailable (2.11 only), 500 for everything else, including
   "media item not found", which the WebSocket reports as `error_code` 2. Before the first user
   exists `/api` answers 503 "Setup required". The app uses the WebSocket; `/api` is only for
   these scripts.
5. **Music source.** Adding a provider goes through a setup flow on both versions
   (`config/providers/save` only edits an existing one):
   `config/providers/setup` `{"provider_domain": "filesystem_local"}` returns a step with
   `"type": "form"` and a `flow_id`; `config/flows/submit`
   `{"flow_id", "values": {"content_type": "music", "path": "/media"}}` returns
   `"type": "finish"` with `result.instance_id`. On 2.11 the folder must be inside a storage
   location the server found in its mount table; a Docker bind mount at `/media` counts.
   Adding the first provider also marks onboarding done (`onboard_done: true`), so
   `config/onboard_complete` is not needed.
6. **Sync.** `music/sync` `{"providers": [instance_id]}` returns the background tasks;
   `tasks/get` `{"task_id"}` until `status` is `success`. The provider also syncs by itself
   when it loads; with 12 tracks it is done in under a second.

## Speakers on a test server

A fresh server has **no players at all** (`players_all__no_players.json` is `[]`, on both
versions). The built-in web player is a Sendspin client running in the browser, so it only
exists while someone has the web UI open (inferred from the Sendspin proxy code; not tried
here), and Chromecast, DLNA and AirPlay find nothing from inside a container.

`fake_player.py` fills the gap. It opens the server's authenticated Sendspin proxy
`ws://<server>:8095/sendspin`, sends `{"type": "auth", "token", "client_id"}`, gets
`{"type": "auth_ok"}`, then speaks the legacy unencrypted Sendspin wire: a `client/hello`
first frame with `"version": 1`, `client_id`, `name`, `"supported_roles": ["player@v1"]` and
`"player@v1_support": {"supported_formats": [{"codec": "pcm", ...}], "buffer_capacity"}`,
then `client/state` with `player.volume`, `muted` and `supported_commands`. The sendspin
provider accepts unencrypted clients while its `allow_legacy_clients` option is on, which is
the default on both versions (`providers/sendspin/provider.py`, `CONF_ALLOW_LEGACY_CLIENTS`
default `True`). The server logs a "non-compliant client" warning for it; that is expected.

The server registers it as a hidden protocol player and about 15 s later wraps it in a
visible "universal player" (`provider: "universal_player"`, `player_id` = `up` + 8 random
hex characters) that has a queue. It then plays for real: `play_media` streams PCM to it,
the queue advances in real time, volume and mute commands come back as `server/command` and
are acknowledged.

## Gotchas

- **Pause is stop on Sendspin.** Sendspin players do not advertise `pause` in
  `supported_features`; the server then turns `player_queues/pause` into a stop, so the queue
  goes to `"state": "idle"` with `current_item` and `resume_pos` kept
  (`player_queues_get__paused.json`). `player_queues/play` resumes from there. This applies to
  the phone itself as a Sendspin player too.
- **`play_media` returns `null`** once playback starts: 0.2 to 0.4 s here with local files and
  a fake speaker. Real speakers and streaming sources are slower.
- **2.11 omits unset fields**: albums in 2.11 lists have no `favorite` key unless the calling
  user set one; in 2.10 it is always `true`/`false`. On 2.11 `music/favorites/set_item`
  `{"item": uri, "favorite": true|false|null}` sends `favorite_updated`
  `{"uri", "media_type", "item_id", "favorite", "user_id"}`; 2.10 has only
  `music/favorites/add_item` and sends no `favorite_updated` event.
- **Sorting**: 2.10 has no `music/sort_options`; it takes `order_by` (`"sort_name"`,
  `"timestamp_added_desc"`, `"last_played_desc"`). 2.11 takes `sort_field` + `sort_direction`
  and lists the valid ones per listing in `music/sort_options` `{"listing": "library_albums"}`.
- **Recently played** (`music/recently_played_items`) listed the playlist right after
  `play_media`, but not the album that had played for about 10 s, even with
  `fully_played_only: false`. Playlists are credited when they are enqueued; albums only once
  a track of theirs has been reported played.
- **Nine playlists, not one.** The built-in provider adds eight generated playlists
  ("All favorited tracks", "Random Artist (from library)", "Infinite Mix (library)", ...) next
  to the library's own "Amsel Mix". Filter on `provider_mappings` if only real ones are wanted:
  a smart playlist has a `builtin` mapping and `is_editable: false` (a playlist made in MA is
  `builtin` too, but editable).
- **No radio stations.** The test library has none (`music/radios/library_items` is `[]`). A
  station needs a stream the server can reach; nothing outside the container is needed:
  `docker exec -d amsel-ma-<tag> python3 -m http.server 8777 --bind 127.0.0.1 --directory "/media/Aurora Tone/Second Wind (2023)"`,
  then `builtin/add_radio {"url": "http://127.0.0.1:8777/01%20-%20Tailwind.mp3", "name": "Amsel Test Radio"}`
  (what `record.sh` does). It shows up as `library://radio/1` about a second later and plays on a
  fake speaker like any other uri.
- **Artwork** is never a plain URL for local files: `metadata.images[]` has a relative `path`,
  `"remotely_accessible": false` and a `proxy_id`. Fetch
  `GET /imageproxy/<proxy_id>?size=<n>&fmt=jpg`; `size` must be one of 0, 80, 160, 256, 512,
  1024 (`controllers/metadata/constants.py`). Player `current_media.image_url` is already a full
  imageproxy URL.
- **Multi-artist tracks**: the tag `Cirrus feat. Aurora Tone` becomes two artists, and the
  queue item is named `Aurora Tone/Cirrus - Jet Stream`.
- **Addresses**: with published ports the server advertises its container address
  (`base_url: "http://172.17.0.x:8095"`), which the host cannot always reach; `NETWORK=host`
  makes it advertise the host's address instead.
- **Offline container**: Smart Fades fails to download its model and the metadata providers
  (MusicBrainz, fanart.tv, Wikipedia, ...) cannot reach the internet. Both only log warnings;
  nothing in the library depends on them.
- **`auth` is not in `/api-docs`**: the WebSocket handler answers it itself. Events reach a
  connection only after `auth` succeeds.
- **One server per name**: `start.sh` removes an old container of the same name first, so the
  data is always fresh. Containers of other names on the same port make `docker run` fail.
- `ffprobe` prints "EOI missing, emulating" for the generated JPEGs; the files are valid (they
  end in `FF D9`), it is an ffmpeg quirk.
