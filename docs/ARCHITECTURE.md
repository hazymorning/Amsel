# Architecture

| Module | Holds |
| --- | --- |
| `:api` | Pure Kotlin/JVM: `MaClient` (the connection loop, `ConnectionState`), `MaSocket` (one WebSocket), `MaApi` (commands), `MaAuth` (probe, sign-in), `ServerAddress`, `ImageUrls`, `localCleartext()` |
| `:data` | `DataGraph`, which builds `Session`, `PrefsStore` (DataStore), `KeystoreTokenCipher`, `ServerDiscovery`, `LibraryRepository` (the Room index), `SpeakerRepository`, `HomeRepository`, `ExtrasRepository`, `PageCache` and the downloads (`DownloadRepository`, `AbsClient`) |
| `:playback` | `PlaybackService` (one Media3 `MediaLibraryService`), `RemotePlayer`, `PhoneSpeaker`, `LocalPlayback` (ExoPlayer for downloads), `DownloadService`, `BrowseTree` (Android Auto), `PlayDispatcher`, `SeekDispatcher`, `MaSpeakerCommands`, `DataPlaybackGraph` |
| `:app` | `AmselApp`, `AppGraph`, `MainActivity`, screens and ViewModels, app-wide controllers (`app/`), widgets (`widget/`, `WidgetController`) |
| `:sendspin` | The official app's Sendspin module, vendored unchanged (`sendspin/VENDORED.md`); only `:playback` uses it and its Ktor client |

`:app` → `:playback` → `:data` → `:api`. `AmselApp` builds one `AppGraph` per process: the app
scope, the one `OkHttpClient`, `DataGraph`, and `DataPlaybackGraph` as `PlaybackGraph.instance`
before Android can create `PlaybackService`. Server behaviour and its evidence: `docs/API-NOTES.md`.

## Data flow

- **Reading.** Each connect reloads players and queues into `SpeakerRepository` and syncs the
  library index; events keep both current. Screens collect the repositories' flows. What the phone
  holds paints first and the server's answer replaces it: the index, `PageCache` (a page's item and
  first 100 rows, per server), Home's snapshot (`files/home.json`), covers in Coil's cache.
- **Commands** go through `MaSpeakerCommands`, `PlayDispatcher` and `SeekDispatcher`: one play and
  one seek at a time per speaker. A tap shows at once until the speaker reports it.
- **Media session.** One `MediaLibrarySession` for the life of `PlaybackService` serves the app,
  notification, lock screen, Bluetooth, Android Auto and widgets. Its player, swapped with
  `session.player`, is a `RemotePlayer` for `DataPlaybackGraph.sessionTarget`: the chosen speaker,
  or This phone while its music goes on and the chosen speaker shows nothing. A remote speaker is
  `PLAYBACK_TYPE_REMOTE`, so volume keys move it; the timeline is the queue's current and next item.
  A play from the car first selects This phone, which the car hears.
- **This phone.** `PhoneSpeaker` is a `LocalOutput` under the same `RemotePlayer`: commands go to
  the server, audio comes over Sendspin. A download plays from its files in `LocalPlayback`'s
  ExoPlayer, swapped into the session, and needs no socket.
- **Session end** (sign-out, a refused token, a server set up anew) clears speakers, index, Home,
  pages, downloads, the Audiobookshelf sign-in, recent searches, bookmarks, widgets and covers.
- **Other hosts.** Audiobookshelf's API for downloads (`AbsClient`); GitHub's `releases/latest`
  for updates (`UpdateController` installs only an APK with Amsel's package name and signing key).

## Connection lifecycle

`Session` holds one socket and keeps it open while the app is in front (`ProcessLifecycleOwner`),
This phone is audible or paused by a call, or, until `Session.stale`, the session's speaker plays or
is starting, or This phone, chosen, holds paused music. It closes 60 s after none of these holds.
`Session.wake()` opens it for the notification, a headset, the car and widget taps, and it closes
60 s after the last of them. Commands wait up to 10 s for a connection. A lost socket retries after
1, 2, 4, 8 and 16 s, then every 30 s; a new network, the screen coming on or Retry try at once.
After two minutes without a connection `Session.stale` turns true: speakers show paused where they
were and no longer keep the socket, which closes 60 s later and This phone's Sendspin with it.
Opening the socket replaces a long-lived token with under 30 days left. While the socket is closed,
`WidgetRefreshJobService` runs every 30 min (with a network, battery not low) and reads the speakers
over a socket of its own (`Session.readOnOwnSocket`).

## Token and plain http

- Only a long-lived token is kept, sealed with an AES-GCM Keystore key (`KeystoreTokenCipher`),
  with its `server_id`; Audiobookshelf's refresh token likewise. Backups are off.
- Token and password go only to a server whose first frame names that `server_id` (at Setup, the
  probed one); nothing before that frame counts. Another id twice on the checked network signs out;
  anywhere else it means offline.
- Android allows cleartext to all hosts or none, so the network security config allows it, and
  every client made from `AppGraph.http` (Coil, Sendspin's Ktor engine, `AbsClient`) enforces this:
  `localCleartext()` connects plain http only to a loopback, link-local, private, 100.64.0.0/10 or
  fc00::/7 address, never through a proxy; `plainHttpOnlyWhen()` refuses it on mobile data without
  a VPN. Redirects between https and http are not followed; TLS trusts system CAs only.
- A typed address without a scheme gets plain http only when its host is local by its form
  (`isLanHost`), otherwise https; an untrusted certificate never falls back to http. The Home
  Assistant sign-in page loads plain http only from such a host.
- `CheckedNetwork` keeps the network where the server last answered as itself, until the phone
  restarts. Over plain http, only there does Sendspin, whose first frame is the token, keep its
  server through a reconnect, and only there is Audiobookshelf reached (`Session.reachesHome`).
