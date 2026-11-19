# Music Assistant Android App — Build Plan

Nov 1, 2026 · @Ben

## Vision and principles

Build a native Android player for Music Assistant that is simple, lightweight and seriously good looking, the three things the existing apps fail at. Anyone in the house can pick it up and use it without a word of explanation. It does one job: find something in your library and play it, on the phone or on any speaker. The bar is Lissen: self-explanatory, calm, fast, and a pleasure to open. The end result of this plan is a finished, signed beta APK.

Eight principles decide every argument during the build. The first outranks all the others.

1. **Nobody needs it explained.** If someone in your household needs help with a task, the app is wrong, not the person. Every action is a visible, labelled control, and gestures are only shortcuts for buttons already on screen. No tutorials, tooltips or onboarding tours.
2. **Familiar beats clever.** Use the layout people already know from every music app: three labelled tabs, a mini player above them, a full-screen player behind it. Surprise belongs in the artwork, not the navigation.
3. **Playback first.** Resume, play an album, or move music to another speaker in two taps or fewer from launch.
4. **One way to do each thing.** Speakers change in one place, settings in one place, and no setting switches between two UIs.
5. **Beautiful, through restraint.** Looks are the reason this app exists, so they are a requirement from the first screen, not a final polish pass. Artwork, type, spacing and motion carry the look, held to the beauty bar in Design direction. No badges, feature tiles or decoration.
6. **Native, not themed.** Material 3 components, dynamic colour, predictive back, system media controls. It should feel like Google could have shipped it.
7. **Fast on a mid-range phone.** Speed is a feature with numbers attached (see Performance budget), measured before each release.
8. **Every feature pays rent.** A feature ships only if it serves daily listening. Admin and server configuration stay in the Music Assistant web UI, and the codebase stays small enough for Kiebitz to hold in its head.

## How decisions are made

Kiebitz makes every product, design and behaviour decision itself, and never asks the owner which option they prefer. Taste is not the tiebreaker; these four questions are, in this order:

1. **What would a first-time user expect?** The conventions of Android itself and of mainstream music apps win, because they need no explanation.
2. **Which option has the fewest elements, steps and rules?**
3. **Which is calmer and better looking?**
4. **Which is faster and lighter?**

Each decision gets one line in `docs/DECISIONS.md`: what was chosen and which question settled it. Evidence overrides reasoning: if the household test or the first-time-user walkthrough shows confusion, the decision changes.

Kiebitz stops for the owner only for what it cannot do itself: access, credentials, and two fifteen-minute household tests. The plan ends with a finished, signed beta APK.

**Already decided**

| Topic | Decision | Why |
| --- | --- | --- |
| Household accounts | Each person signs in with their own Music Assistant user; a shared account also works | Music Assistant keeps speaker access and music sources per user ([user management](https://www.music-assistant.io/settings/user-management/)) |
| Build order | Speakers first (M1), phone playback in M3; the beta has both | M1 proves the server layer and media session on the least code |
| Server versions | 2.10 and 2.11, gated on the schema number | The current and previous release, as the official app supports |
| Podcasts and audiobooks | In the beta, shown only when the library holds some | The owner listens to Audiobookshelf through Music Assistant (explicit wish) |
| Android Auto | In the beta (M4) | It comes almost free with the media service the app needs anyway |
| Outside the home | Any `https://` address entered at setup works; no relay | Keeps setup and code simple |
| Distribution | Signed APK on GitHub Releases, installable directly or through Obtainium, under the Apache-2.0 licence. Install notes cover Android Auto's "Unknown sources" setting | Needs no store account and matches the vendored module's licence |
| Name | A short working name picked in M0 after checking F-Droid and Play for clashes, without "Music Assistant" in it | Avoids confusion with the official app; the display name can change later, the package name cannot |

**What Kiebitz needs from you**

- An empty GitHub repository, with Kiebitz connected and allowed to push and merge.
- A Music Assistant account to test against, besides the test server it runs in CI.
- Two fifteen-minute household tests: after M2, and on the release build before the beta ships.

## Why the existing apps fail, and the rules that stop us repeating them

All three apps fail the same way: they treat the phone as a second Music Assistant web UI and keep adding to it. Lissen avoids this on purpose: its README says it is not a clone of the official Audiobookshelf app and does not try to copy all of its features ([Lissen](https://github.com/GrakovNe/lissen-android)).

| App | Stack | What goes wrong | Lesson for us |
| --- | --- | --- | --- |
| [Official mobile app](https://github.com/music-assistant/mobile-app) | Kotlin Multiplatform + Compose Multiplatform, Android and iOS, Apache-2.0 | Its design goals ask for ten actions to be reachable from almost any state, so every screen carries player switching, grouping, search and settings. Its own [parity report](https://github.com/music-assistant/mobile-app/blob/main/.claude/feature-parity-report.md) tracks about 35 web features still to add. One UI also has to serve two platforms. | Reuse its protocol work, which is solid. Never adopt its goal of parity with the web UI. |
| [MassDroid](https://f-droid.org/packages/net.asksakis.massdroidv2/) | Native Android, MIT, 9 MiB | Adds a local recommendation engine, Smart Mix, Genre Radio, "Follow Me" room detection, audiobooks, podcasts, TV, plus Wikipedia and MusicBrainz lookups. It asks for location, microphone, activity recognition, NFC, Bluetooth pairing and start-at-boot. | If a feature duplicates the server's job or needs a scary permission, it does not belong. |
| [Ensemble](https://github.com/CollotsSpot/Ensemble) | Flutter, MIT, about 24–27 MiB | Covers audiobooks, podcasts, radio and grouping. Users report that Bluetooth headphone buttons and the notification controls do nothing ([issue #91](https://github.com/CollotsSpot/Ensemble/issues/91)). | Media session integration has to be native and first-class, or the app fails at the basics. |

Six rules keep this app from drifting the same way. Kiebitz treats them as hard constraints.

1. **The server does the work.** No client-side recommendations, metadata enrichment, scrobbling or smart mixes. If Music Assistant does not offer it, the app does not either.
2. **No parity chasing.** The web UI stays the place for admin and rare tasks. The app offers "Open in web UI" instead of rebuilding them.
3. **Feature test.** A feature ships only if the owner would use it weekly, it needs no new permission, it fits an existing screen, and someone in your household can use it without being told how. Everything else goes to a parking-lot file.
4. **Settings budget.** The settings screen fits on one phone screen without scrolling. When tempted to add a toggle, pick the better default.
5. **Permission budget.** INTERNET, ACCESS\_NETWORK\_STATE, ACCESS\_LOCAL\_NETWORK, FOREGROUND\_SERVICE, FOREGROUND\_SERVICE\_MEDIA\_PLAYBACK and WAKE\_LOCK. Apps targeting Android 17 need ACCESS\_LOCAL\_NETWORK at runtime for any connection to a home server, so the Connect screen asks for it. No notification permission: media-session notifications are exempt. Nothing else, ever.
6. **Size and speed budgets** are split: size, permissions and dependencies fail the build in CI, and speed is measured before each release (see Performance budget).

## Platform decision

**Native Android: Kotlin, Jetpack Compose, Material 3 and Media3.** It is the same stack Lissen uses ([build file](https://github.com/GrakovNe/lissen-android/blob/main/app/build.gradle.kts)). It is the only option where the media session, notification, lock screen, Bluetooth buttons and Android Auto come from the platform rather than a bridge. You only want Android, so cross-platform buys nothing and costs polish.

| Option | Who uses it | Verdict |
| --- | --- | --- |
| Kotlin + Jetpack Compose + Media3 | Lissen, MassDroid | **Choose.** First-class MediaSession and Android Auto, smallest APK, native motion and predictive back. Kiebitz writes this stack fluently. |
| Kotlin Multiplatform + Compose Multiplatform | Official app | Reject. Pays for iOS you do not need, and shared UI drifts from Android-native details. |
| Flutter | Ensemble | Reject. Own renderer, larger APK, media controls through plugins. |
| React Native or a web wrapper | none of note | Reject. Worst fit for background audio. |

The dependency list is short on purpose, and Kiebitz may not add to it without a written reason in the PR.

| Layer | Choice | Why |
| --- | --- | --- |
| UI | Compose + Material 3. [1.4.0 is stable; 1.5.0 is in beta](https://developer.android.com/jetpack/androidx/releases/compose-material3), so start on 1.4.0 and move to 1.5 once stable. Experimental opt-ins only inside the theme package | Native components, dynamic colour, motion scheme |
| Navigation | [Navigation 3](https://developer.android.com/jetpack/androidx/releases/navigation3), stable 1.2.0 | Type-safe, back stack you own |
| Media | [Media3](https://developer.android.com/jetpack/androidx/releases/media3) session, stable 1.11.1 | One MediaSession drives every system surface |
| Network | OkHttp (WebSocket and HTTP) + kotlinx.serialization | The API is one WebSocket; no Retrofit needed |
| Images | Coil 3 on the same OkHttp client | Small, Compose-native, shared cache |
| Storage | Room for the library index, DataStore for preferences, Keystore-backed storage for the token | Instant library on launch |
| Local audio | The official app's Sendspin module (Apache-2.0), plus its Android decoder and AudioTrack sink, which live outside the module | No ExoPlayer or FFmpeg; the player holds its own wake and Wi-Fi locks |
| DI | Plain constructor injection with one `AppGraph` | About ten singletons; no Hilt or Koin build cost |
| Quality | ktlint, JUnit, Roborazzi screenshot tests, Macrobenchmark with Baseline Profiles | Keeps the budgets honest |

Not allowed: Hilt, Koin, Retrofit, RxJava, Accompanist, Lottie, icon or font libraries, Firebase, analytics or crash SDKs, and any alpha library. Set `minSdk` 28 (Android 9, as Lissen does) and target the newest SDK.

## How Music Assistant works from the app's side

The app is a thin client: one authenticated WebSocket for commands and live events, HTTP for artwork, and a second WebSocket (Sendspin) only when the phone itself plays audio. The server owns the library, the queues and the players.

**API.** The server listens on port 8095 ([server](https://github.com/music-assistant/server)). It offers a WebSocket API, which the project recommends for live updates, plus an RPC-style HTTP API that takes the same commands ([client library notes](https://feed.nuget.org/packages/WateryTart.MusicAssistant)). Since 2.7, each server generates its own API reference at `http://<server>:8095/api-docs` ([discussion](https://github.com/orgs/music-assistant/discussions/3802)). That page is the source of truth; the official Python client and the web frontend's TypeScript API are the working examples.

**Auth.** From server schema 28 every connection needs a token. The flow is username and password, then a long-lived token named after the device ([Python client](https://github.com/music-assistant/client)). Release 2.7 added users, per-user providers, per-speaker access and Home Assistant single sign-on ([2.7 notes](https://www.music-assistant.io/blog/2025/12/17/music-assistant-2-7/)). In 2.11, write actions are gated by scopes and custom roles, so the app reads `auth/scopes` and hides what the user cannot do ([parity report](https://github.com/music-assistant/mobile-app/blob/main/.claude/feature-parity-report.md)).

**Versions.** The current stable line is 2.10 (v2.10.8), released 26 August 2026 on a three-month cadence ([2.10 notes](https://www.music-assistant.io/blog/2026/08/26/music-assistant-2-10/)). 2.11 is in beta; its dev branch reports API schema 96 against 65 for 2.10.6. On that cadence 2.11 should be stable around the end of November, during M0 or M1. So build against 2.11 from M1 and keep 2.10 working through schema checks, matching the official app's policy of supporting the current and previous release.

**Players and queues.** Every speaker, group and the phone itself is a Music Assistant player with its own queue. Play actions go to a queue (`player_queues/play_media`); the server streams to the speaker. Transferring a queue between players is a core move.

**Gotchas the official app already learned** (from its [architecture notes](https://github.com/music-assistant/mobile-app/blob/main/.claude/architecture.md)):

- The server caps a message at about 9 MB. Never request a huge `limit`; page large lists.
- `playlist_tracks` and `podcast_episodes` stream in batches on 2.10 but return 500-item pages on 2.11.
- `play_media` answers only after playback starts, which can take tens of seconds. Send it from an app-wide scope, update the UI optimistically, allow one request per queue and drop double taps.
- On 2.11 favourites are per user with three states (like, dislike, none), and arrive in a separate `favorite_updated` event.
- Sorting moves to `sort_field` and `sort_direction` with `music/sort_options`; `order_by` is deprecated.

**The phone as a speaker (Sendspin).** Sendspin is Music Assistant's own open protocol for synchronised playback, and the built-in web player uses it ([Sendspin docs](https://www.music-assistant.io/player-support/sendspin)). A player client needs end-to-end Noise encryption, a pairing model, and a Kalman-filter clock sync ([spec](https://github.com/Sendspin/spec)). The wire format changed between server 2.10.6 and 2.10.7. The official app keeps all of this in a pure-Kotlin `:sendspin` module with no UI or DI dependency, fully unit-tested, that speaks both wire versions. It connects through the server at `<ws url>/sendspin`. **Decision: vendor that module rather than write our own.** It is the hardest and least visible part of the app, and theirs already works.

Vendoring is more than one copy. The Android decoder and AudioTrack sink live in the official app's `player/local/` folder, not in the module, and the module is built with Kotlin Multiplatform tooling. M0 includes a spike that lists the module's build plugins and dependencies and decides whether to turn it into a plain Kotlin module. Without ExoPlayer, the player must hold a wake lock and a Wi-Fi lock itself, or playback stalls with the screen off.

**Out of scope for v1:** remote access through Music Assistant's WebRTC relay. Outside the home, use a VPN or a reverse proxy with HTTPS.

## v1 scope

v1 is music first, with audiobooks and podcasts where the library holds them: three tabs, one player screen, one speaker picker, and the system integrations that make it a daily driver.

| Surface | What it does | What it deliberately leaves out |
| --- | --- | --- |
| Connect | Finds the server by itself, then one sign-in with a Home Assistant account or username and password. See Core flows. | Ports, TLS switches or tokens on screen; multiple servers; WebRTC remote access |
| Home | Sections, as many as the person adds, each a sentence they set: "Show [what] as [how]" (`docs/notes/home-sections.md`), Continue listening first once there are books or podcasts. Home edits itself in place. | Server recommendation rows, a settings page for Home |
| Library | One list with Playlists, Albums and Artists chips (Audiobooks and Podcasts when the library holds some), one sort button and an A–Z scrubber. Served from the local cache. | Tracks tab, genres, radio, multi-select |
| Search | One field, results as you type, your library's matches first. No toggles. | Source pickers, search history |
| Album, artist, playlist pages | Large art, Play and Shuffle, track list, "more by this artist"; an artist's page ends with the server's similar artists, where it has some. | Reviews, versions, discography, bios |
| Book and podcast pages | A book: Resume, time left, chapters. A podcast: its episodes, each with how far it is played. Mark as finished or played. | Speed, bookmarks, series, transcripts |
| Now Playing | Full-screen art, title, seek bar, transport, Up next, the speaker button, volume. Opens from the mini player. For a book or an episode: back 15 and forward 30, previous and next by chapter, Chapters. | Visualizer, waveform, stream-quality panel |
| Queue | View, tap to jump, drag to reorder, swipe to remove, "play next". | Save queue as playlist, queue settings |
| Speaker picker | A speaker button showing its name opens the "Play on" sheet: every speaker and what it is playing, "This phone" first. See Core flows. | Swiping between speakers, DSP, speaker settings |
| Item actions | A visible menu on every page and Now Playing, with long-press on any row or tile as the shortcut: Play and Shuffle for an album, a playlist or an artist (owner, 2026.11.18), Play next, Add to queue, Favourite, Add to playlist, Go to artist or album; where the server has them, Endless mix, Similar songs and, in Now Playing, the DJ (owner, 2026.11.22). | Refresh metadata, edit, export |
| Settings | Account, theme (system, light, dark), "Open web UI", about. | Everything else |
| System | Notification and lock-screen controls, Bluetooth buttons, volume keys for the active player, Android Auto browsing, one home-screen widget in three starting sizes, its form following its size, with none to four buttons, a look and a speaker each person sets (`docs/notes/widget.md`, owner, 2026.11.50 and 2026.11.51). | More widgets, Wear OS, Android TV |

**After the beta, and only if they pass the feature test:** lyrics, sleep timer, grouping speakers, radio.

**Never:**

- Server, provider, player, DSP or user administration
- Recommendation engines, mixes or genre radio made on the phone, or any client-side "intelligence" (the server's own endless mix and similar songs and artists are offered where it has them)
- Metadata enrichment from Last.fm, Wikipedia, MusicBrainz or anything else
- Location, Bluetooth-fingerprint or other presence features
- Party mode, music quiz, AI Radio DJ configuration
- Telemetry, analytics or ads
- Settings that switch between alternative layouts

## Core flows: nothing to explain

Four flows carry almost all daily use, so they get exact specs instead of general rules. The test for each one: hand the phone to someone in your household, say nothing, and watch.

&#91;embedded content: wireframe · Home and the Play on sheet\]

The wireframe fixes structure, not styling: what sits where, and which controls are always visible.

### Setup: three screens, under a minute

The official app opens on host, port and a TLS checkbox ([settings notes](https://github.com/music-assistant/mobile-app/blob/main/.claude/settings-screen.md)). This app never asks for anything it can find out itself.

1. **Welcome.** One line, "Play your Music Assistant music on any speaker in the house", and one button, "Find my server". Android's local network prompt appears after this, so the reason is already on screen.
2. **Your server.** Music Assistant servers announce themselves on the network as `_mass._tcp` ([Dart client](https://pub.dev/documentation/music_assistant/latest/auth_helpers/discoverServers.html)), so the app finds them with Android's built-in service discovery. One server is picked automatically; several appear as a list. If nothing turns up within five seconds, one field appears, "Server address". It accepts what people actually type, such as `192.168.1.20` or `music.local`, and adds `http://` and port 8095 itself.
3. **Sign in.** "Sign in with Home Assistant" comes first when the server offers it, then username and password. Errors are plain, such as "That password didn't work", never a status code. The app then keeps a long-lived token and never asks again.
4. **Home.** No more questions. Music plays on this phone until someone picks a speaker with the speaker button, and the app remembers the last choice. Builds before M3, which cannot play on the phone yet, ask once instead.

Ports, TLS switches, tokens and certificates never appear on screen. Typing an `https://` address covers TLS; anything rarer is out of scope.

### Home: what you want to hear, in a layout you already know

Beta 1's Home was fixed (Jump back in, Your playlists, Recently added). After beta 1 the owner
asked for a customisable Home (`docs/DESIGN.md`, "Owner decisions"): sentence sections,
specified in `docs/notes/home-sections.md`. A new person gets Recently played, Smart playlists and
New in your library as rows, and a Pinned grid with the first pin. A brand-new library shows "Your
albums" in place of Recently played; an empty one shows one line, "Add music in Music Assistant",
with a button that opens it. No server recommendation rows.

### Browsing: three tabs, one list, obvious pages

- **Tabs.** Home, Search, Library, in that order, with labels always visible.
- **Library.** One list with three chips at the top: Playlists, Albums, Artists. One sort button offers Recently played, Recently added and A–Z. Albums show as a grid of artwork, title and artist. Long A–Z lists get a letter scrubber.
- **Pages.** An album page shows artwork, title, the artist as a link, big Play and Shuffle buttons, then the tracks. Artist pages show five popular tracks, then albums. Playlist pages look like album pages.
- **Search.** Results update as you type: a top result, then songs, albums, artists and playlists, with your library's matches first. No toggles or source pickers.
- **Back** always goes up one level, and each tab remembers where you were.

### Speakers: a button with a name on it

The official app switches players with a sideways-swiping pager ([parity report](https://github.com/music-assistant/mobile-app/blob/main/.claude/feature-parity-report.md)), which hides the most important control in the app. Here the speaker is a button, always with the speaker's glyph: a pill with its name, such as "Kitchen", at the start of Now Playing's footer, and a light disc beside play on the mini player, whose top line names the speaker (owner, 2026.11.14 and .15). These are the only places speakers change.

Tapping it opens a sheet titled "Play on": "This phone" first, then every speaker with what it is playing, and a tick marks the current one. Tapping a speaker makes it the one the app plays on and controls, and what this phone has open goes along, playing or paused. Music that plays on a speaker may be someone else's: before it moves or is replaced, the sheet asks (owner, 2026.11.12). "Transfer playback" in Now Playing's ⋯ menu lists the other speakers and moves the music to the one picked without asking.

Volume for the current speaker sits on Now Playing, and the phone's volume buttons control it too. Grouping lives in this same sheet and nowhere else: Group opens the speakers that can play along, each with a tick, and while some do, Ungroup beside it lets them all go at once (owner, 2026.11.18).

### The household test

A person runs it twice, after M2 and on the release build. Give the app to someone who has not seen it, say nothing, and ask for five things:

1. Set the app up.
2. Play a particular album in the kitchen.
3. Move the music to another room.
4. Find a playlist and play it shuffled.
5. Skip a song and turn the volume down.

Any question, wrong tap or long pause is a bug. File it with what the person tried first, then make that first attempt work. Never fix it with a hint, tooltip or tutorial. The one exception is what the owner keeps out of sight on purpose, so the screens stay plain (Settings, Edit Home, the long press): each is said once in a tip on Home (`DESIGN.md`, Tip).

## Design direction

Artwork is the colour, type is the structure, and everything else stays quiet. Lissen's F-Droid listing sums it up as a minimalist app designed for one task ([F-Droid](https://f-droid.org/packages/org.grakovne.lissen/)); this app applies that to music and holds every screen to the beauty bar below.

**Visual language** (v3: the owner's decisions in `docs/DESIGN.md` override the plan where they
differ, and `docs/DESIGN.md` holds every value)

- **Colour.** One fixed paper-and-ink palette on every phone (no wallpaper colour). Colour comes from the covers: the dock under the mini player takes the playing cover's tint, Now Playing its gradient, pages a wash. One accent per screen.
- **Type.** One bundled typeface, Google Sans Flex, on one fixed scale with bold titles. No all-caps.
- **Shape.** Covers and artist images are softly rounded by size (4 to 16 dp); a container holding a cover is concentric with it; every control shares one 12 dp radius.
- **Layout.** A 4 dp grid with 24 dp margins. List rows 72 dp tall with 56 dp covers. Album grid of two columns on phones, more on wider screens.
- **Depth.** Tonal surfaces instead of shadows. Only the mini player and sheets sit above the page.
- **Icons.** Phosphor in its Regular weight (Fill only for transport, the selected tab and a favourite), copied in as vector drawables for only the icons used. No icon library.

**Motion**

- Album art is a shared element from grid to album page to Now Playing.
- Tapping the mini player opens Now Playing. Dragging it up does the same, and Back or a visible close arrow brings it down.
- Use the Material 3 motion scheme's springs. No decorative animation. Skeleton placeholders only on the very first load; after that the cache paints instantly.

**Interaction rules**

- Tap an album to open it. Tap a track to play from there on the current speaker.
- Every page and Now Playing has a visible menu for more actions. Long-press on a row or tile opens the same sheet as a shortcut, never as the only way in: a row opens its page, where the menu is.
- Gestures only ever repeat a visible button. No swiping between speakers. Swipe-to-skip on the dock's player row repeats Now Playing's previous and next; swipe-to-remove in Up next repeats Remove in the item's actions.
- Use the words people use: "speaker", not "player"; "Play on", not "transfer queue"; "Up next" for the queue; "music service", not "provider". The glossary lives in `DESIGN.md` and every string follows it.
- Icons carry a text label unless they are universal: play, pause, skip, back, search.
- Every tap gives visible feedback within 100 ms, and the server catches up behind it. Errors are plain sentences in a snackbar with Retry, never a code or a dialog.
- Confirmation dialogs only for destructive actions, such as removing a track from a playlist.
- Accessibility is part of done: TalkBack labels, 48 dp touch targets, layouts that survive 200% font scale, AA contrast.

**The beauty bar**

Every screen passes this list before it merges. It is what turns a tidy app into a good-looking one.

- **Artwork is always sharp.** Request it at the screen's pixel density, keep it square, and fade it in over a soft placeholder. Never a stretched image or a broken-image icon.
- **Nothing jumps.** Artwork and rows have fixed sizes, so content loads without moving the layout.
- **Now Playing is the showpiece.** Large artwork with generous margins, a background that takes its colours from the artwork and shifts smoothly between tracks, and controls with plenty of air around them.
- **Edge to edge.** Content draws behind the status and navigation bars with correct insets, in light and dark.
- **One accent per screen.** Everything else is neutral or comes from the artwork.
- **Type on a fixed scale.** No one-off sizes, tabular figures for times, and long titles end in an ellipsis instead of wrapping awkwardly.
- **Everything on the grid.** Edges line up across sections, and spacing comes only from the 8 dp scale.
- **Smooth motion.** Transitions keep up with the screen's refresh rate with no dropped frames, checked by the frame-timing benchmark.
- **Designed states.** Empty, loading, offline and error screens get the same care as the main ones, never default text.
- **Finishing details.** A custom adaptive app icon with a themed variant, the system splash screen with no extra one, and light haptics on play, pause and speaker changes.

**Keeping Kiebitz on taste**

1. Kiebitz copies Lissen's store screenshots from its MIT-licensed repository into `docs/design/reference/`. It can read images, so it compares its own screens with them.
2. This section becomes `docs/DESIGN.md`, with the tokens (colours, type scale, spacing, corner radii) and the glossary written down once.
3. M0 builds the design system and every core screen with sample data before any server code, so the look is settled first.
4. Every UI pull request carries screenshot tests in light, dark and large-font variants.
5. When a screen looks busy, the fix is removing something, not restyling it.

## Architecture

One Media3 session sits at the centre, and its player is whichever speaker you selected. The app screen, the notification, headphone buttons and Android Auto all send commands to that session, so they behave identically. For phone playback that makes headphone buttons reliable, which is exactly where Ensemble fails. For speakers, Android may send headphone buttons to whichever app last played audio on the phone, so the M1 spike settles what works.

&#91;embedded content: architecture · 5 modules, one MediaSession\]

The UI never talks to a speaker directly. Swapping speakers swaps the player behind the session; nothing else changes.

- **`:api`** owns the single WebSocket: login, reconnect with backoff, request and response matching, event stream, and schema gates. It knows nothing about screens.
- **`:data`** turns events into state. The library index lives in Room for instant lists and local filtering; Home's layout is a small JSON document per server in DataStore and its sections' last items a JSON file; everything else is held in memory. The index fills through a paged full sync on first connect, stays current from media-item events, and re-syncs fully when the server or its schema changes. Optimistic changes are reconciled when the server's event arrives.
- **`:playback`** holds the MediaLibraryService. `RemotePlayer` wraps a Music Assistant player and queue behind Media3's player interface, including remote volume. `PhoneSpeaker` runs the vendored Sendspin module for This phone, under the same `RemotePlayer`. A `PlayDispatcher` sends every `play_media`, one per queue at a time.
- **`:app`** is Compose screens and ViewModels exposing immutable state, with Navigation 3 for the back stack.
- **`:sendspin`** is copied from the official app at a pinned commit, licence intact, together with its Android decoder and sink. A sixth module, `:baselineprofile`, holds the benchmarks and never ships.

## Performance budget

Speed is specified as numbers, and the cheap checks run on every pull request. Benchmarks run on an emulator in CI to catch regressions; absolute timings come from a real phone. For scale: Lissen's APK is 14 MiB, MassDroid's 9 MiB and Ensemble's 24–27 MiB on F-Droid.

| Metric | Budget | Measured by | When |
| --- | --- | --- | --- |
| Release APK size | ≤ 15 MB | CI size check | Every PR, fails the build |
| Manifest permissions | The allowlist only | CI check of the merged manifest | Every PR, fails the build |
| Dependencies | Allowlist only | CI diff against `docs/dependencies.txt` | Every PR, fails the build |
| Cold start to first frame | ≤ 500 ms | Macrobenchmark startup metric | Before each release |
| Launch to cached Home | ≤ 800 ms | Macrobenchmark trace section | Before each release |
| Library grid scroll | < 1% janky frames | Macrobenchmark frame timing | Before each release |
| Tap to visible feedback | ≤ 100 ms | Macrobenchmark on the play tap | Before each release |
| Idle network in background | None after 60 s when nothing plays | Android Studio network inspector | Before each release |

How the app meets them:

- **Baseline and startup profiles** generated from four journeys: launch, open an album, scroll the library, open Now Playing. R8 full mode and resource shrinking on.
- **Cache first.** The library index lives in Room, Home keeps its last snapshot and each page what it showed last time, so screens paint from disk and then refresh from the server.
- **Right-sized images.** Request artwork at display size, cache it in Coil, and prefetch the next and previous track's art.
- **Compose hygiene.** Immutable UI models, lazy lists with stable keys and content types, no work during composition.
- **Connection discipline.** The WebSocket stays open in the foreground and during playback, closes after 60 s idle in the background, and reconnects with backoff. Updates come from server events, never polling.
- **Lazy start.** Nothing heavy in `Application.onCreate`. Sendspin starts only when "This phone" is the selected player.

## Milestones

Six milestones take the app from an empty repository to a finished, signed beta APK. Each ends with a gate the build has to pass, and only two of those gates need you, for a fifteen-minute household test.

&#91;embedded content: milestones · M0 to M5, one gate each\]

M0 builds the design system and every core screen with sample data first, so the look is settled before any server code exists. M1 comes before the library because it proves the WebSocket layer, the player abstraction and the media-session approach on the least code. Each milestone breaks into roughly four to eight issues, and the next one starts only when the previous gate passes.

The same milestones as text, so the Markdown export for Kiebitz keeps them:

| Milestone | What ships | Gate |
| --- | --- | --- |
| M0 · Foundations and design system | Repo, `AGENTS.md` and docs, CI gates, Kiebitz's GitHub app, design tokens, every core screen built with sample data and screenshot-tested, Sendspin vendoring spike | Screens pass the beauty bar, the design critic and the first-time-user walkthrough; CI green |
| M1 · A better remote | Setup with server discovery, speaker button and Play on sheet, mini player and Now Playing for a speaker, transport, volume, background-controls spike (go/no-go) | Walkthrough passes; works against test servers on 2.10 and 2.11 |
| M2 · Library and search | Home, Library from the Room cache, album, artist and playlist pages, search, play actions, queue editing, favourites | Household test 1 passes |
| M3 · This phone plays | Vendored Sendspin as This phone with its decoder and sink, wake and Wi-Fi locks, audio focus, headphone buttons, notification | Two hours of screen-off playback with no gaps or crashes |
| M4 · System polish | Android Auto browsing, notification controls for speakers, add to playlist, scope-gated actions, accessibility pass | Every budget met; Android Auto checked on the Desktop Head Unit |
| M5 · Beta release | Beauty-bar pass on every screen, app icon, release signing, signed APK on GitHub Releases with install notes | Household test 2 passes on the release build; beta published |

## Running the project with Kiebitz

Kiebitz builds the app and makes its decisions by the rules in "How decisions are made", from an empty repository to the published beta. Each milestone becomes a handful of GitHub issues, each issue one pull request, and a pull request merges only when the automated gates and three reviewers have passed it.

One warning from the field: Ensemble's README says it was built with AI coding tools, and it describes itself as minimalistic ([Ensemble](https://github.com/CollotsSpot/Ensemble)). The tool does not supply taste. The decision rules, the beauty bar and the gates below do.

**Files to create before any app code**

| File | Holds |
| --- | --- |
| `AGENTS.md` | Under 100 lines: what the app is, the decision rules, the six rules, the module map, build and test commands, the definition of done, how to work in the repo. Points to the docs below. |
| `docs/PLAN.md` | The working sections of this document as Markdown: vision, decisions, scope, core flows, architecture, milestones and budgets. Rationale and sources stay in this doc. |
| `docs/DESIGN.md` and `docs/design/reference/` | Design tokens, the glossary, the beauty bar and reference screenshots |
| `docs/DECISIONS.md` | One line per decision: what was chosen and which question settled it |
| `docs/API-NOTES.md` | Every Music Assistant command the app uses, its arguments, schema gates and quirks. Kiebitz adds an entry whenever it uses a new command. |
| `docs/dependencies.txt` | The dependency allowlist that CI enforces |
| `docs/PARKING-LOT.md` | Ideas that failed the feature test, kept so they stop coming back |

**Loop for every feature**

1. Kiebitz turns each milestone row into issues with acceptance criteria.
2. It writes a short plan into the issue before building. Complex pieces, such as the player abstraction and the Sendspin integration, get a one-page design note.
3. It builds on a branch with tests and screenshot tests, then opens a pull request.
4. CI runs build, unit tests, lint, screenshot tests, and the size, permission and dependency checks, and attaches a debug APK.
5. Kiebitz records any decision in `DECISIONS.md`.
6. Once everything is green, Kiebitz merges. You can comment on anything, but you are never a required reviewer. Its GitHub app is set up in M0, so mentioning it in an issue or pull request also works.

**Rules in `AGENTS.md`**

- Never ask the owner to choose between options. Decide with the questions in "How decisions are made", add the line to `DECISIONS.md`, and move on.
- No new dependency, permission or setting without a reason in the pull request; CI blocks the first two.
- Check the test server's `/api-docs` before calling a command, then record it in `API-NOTES.md`.
- One screen is one file plus one ViewModel. Keep files under about 300 lines. No `!!`.
- Done means: tests pass, screenshots in light, dark and large font, the beauty bar met, TalkBack labels, no new warnings.

**A test server.** Kiebitz needs a real Music Assistant to test against. Run the official Docker image in CI with a local-files provider and a few freely licensed tracks, and record WebSocket sessions as fixtures for fast unit tests. If headless first-run setup proves awkward, fall back to the fixtures plus manual checks against your own server.

**First session.** Ask Kiebitz to read `docs/PLAN.md` and `docs/DESIGN.md`, then work through the milestones in order until the beta is published, planning each milestone before building it.

## Risks

The biggest risk is not technical: it is the slow slide into a busy, generic UI. The technical risks are moving server and protocol versions.

| Risk | Why it is real | Mitigation |
| --- | --- | --- |
| Scope creep | It is how all three existing apps went wrong | The six rules, the never list, the parking lot |
| Generic, AI-looking design | Default Material screens look like every other app | Reference screenshots, `DESIGN.md`, screenshot tests, the household test |
| Sendspin changes underneath us | Spec is at 1.0 release candidate; the wire changed between 2.10.6 and 2.10.7 | Vendor the official module at a pinned commit, update it deliberately each server release, ship local playback after remote control is solid |
| Music Assistant API moves every quarter | Schema went from 65 (2.10.6) to 96 on the 2.11 dev branch | Support current and previous release only, gate on schema, keep recorded fixtures per version, maintain `API-NOTES.md` |
| Controls for a remote speaker in the background | Android restricts foreground services; a notification for a speaker the phone is not playing on needs care | A go/no-go spike early in M1. Fallback: controls in the app and in the notification while a speaker plays, without headphone buttons |
| Licence obligations from the vendored module | It is Apache-2.0 | Keep its licence and notice files; license the app Apache-2.0 as well |
| Plain-HTTP servers and self-signed certificates | Android blocks unencrypted traffic by default since Android 9, and most home servers run plain http on port 8095 | Allow cleartext in the network security config (it cannot be limited to private addresses) and trust user-installed CAs. The Connect screen says HTTPS is the safe path outside the home. No client certificates in v1 |

## Sources

Pages opened while researching this plan, as of 1 November 2026.

- Existing apps: [official mobile app](https://github.com/music-assistant/mobile-app), its [architecture notes](https://github.com/music-assistant/mobile-app/blob/main/.claude/architecture.md), [feature parity report](https://github.com/music-assistant/mobile-app/blob/main/.claude/feature-parity-report.md) and [settings screen notes](https://github.com/music-assistant/mobile-app/blob/main/.claude/settings-screen.md); [MassDroid on F-Droid](https://f-droid.org/packages/net.asksakis.massdroidv2/); [Ensemble](https://github.com/CollotsSpot/Ensemble), [Ensemble on F-Droid](https://f-droid.org/packages/com.collotsspot.ensemble/), [Ensemble issue #91](https://github.com/CollotsSpot/Ensemble/issues/91)
- Lissen: [README](https://github.com/GrakovNe/lissen-android), [build file](https://github.com/GrakovNe/lissen-android/blob/main/app/build.gradle.kts), [F-Droid listing](https://f-droid.org/packages/org.grakovne.lissen/)
- Music Assistant: [server](https://github.com/music-assistant/server), [Python client](https://github.com/music-assistant/client), [WateryTart client notes](https://feed.nuget.org/packages/WateryTart.MusicAssistant), [Dart client server discovery](https://pub.dev/documentation/music_assistant/latest/auth_helpers/discoverServers.html), [API docs discussion](https://github.com/orgs/music-assistant/discussions/3802), [first-run authentication](https://www.music-assistant.io/first-run/), [user management](https://www.music-assistant.io/settings/user-management/), [2.7 release notes](https://www.music-assistant.io/blog/2025/12/17/music-assistant-2-7/), [2.10 release notes](https://www.music-assistant.io/blog/2026/08/26/music-assistant-2-10/)
- Sendspin: [player docs](https://www.music-assistant.io/player-support/sendspin), [specification](https://github.com/Sendspin/spec)
- Android: [Compose Material 3 releases](https://developer.android.com/jetpack/androidx/releases/compose-material3), [Media3 releases](https://developer.android.com/jetpack/androidx/releases/media3), [Navigation 3 releases](https://developer.android.com/jetpack/androidx/releases/navigation3), [local network permission](https://developer.android.com/privacy-and-security/local-network-permission), [notification permission](https://developer.android.com/about/versions/13/changes/notification-permission), [Output Switcher](https://developers.google.com/cast/docs/android_sender/output_switcher)
