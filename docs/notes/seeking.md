# Seeking: how others do it, what the server does, what Amsel does

Written for 2026.11.11, after the household's test found seeks, skips and chapter steps unusable
and a server went down during one. Sources read: the official app (`music-assistant/mobile-app`
@ `5f7d960`), Lissen (`GrakovNe/lissen-android` @ `573897c`), Absorb (`pounat/absorb` @ `00085b4`),
the server at `2.10.9` and `2.11.0b8`. Measured live on the test servers (2.10.9, 2.11.0b7) with a
three-hour book in six files and one as a single m4b, both from Audiobookshelf 2.37.2, on the fake
Sendspin speaker.

## 1. The others

| App | Plays through | A seek is | Where the position shows |
| --- | --- | --- | --- |
| Lissen | ExoPlayer on Audiobookshelf's files, each chapter one media item (`LissenMediaSourceFactory`) | `player.seekTo(chapter, ms)`, at once; a pause is kept (`MediaRepository.seekTo`) | ExoPlayer's own |
| Absorb | just_audio on Audiobookshelf's files | at once; a pause is kept (`_seekedWhilePaused`); a skip counts the speed in | the target for up to 8 s, until the stream is there (`activeSeekTarget`) |
| Official app, This phone | Music Assistant, Sendspin | `players/cmd/seek`, one per tap; a skip or a chapter step is a seek from the position shown (`PlayerRequestFactory.resolve`) | the target, standing, until Sendspin has audio, or 5 s if the server never reported it (`PlayerPositionTracker` freeze) |
| Official app, a speaker | Music Assistant | `players/cmd/seek`, one per tap | whatever the server reports (`MainDataSource.playerAction`) |

Lissen and Absorb never ask a server to move: that is why they feel immediate. Amsel plays a
download the same way (`LocalPlayback`). Everything else goes through Music Assistant, where the
official app is the measure.

## 2. The server

- `player_queues/seek` is `play_index(current_index, position)`: the stream is built anew and
  **starts playing**, whatever the queue did before (`player_queues/controller.py`).
- Until the new stream runs it reports the target, then **the old stream's time on top of the
  target** (7.4 s played, sought to 600: `607.4`), 0.35 s later the right time. Both releases.
- A position outside what the old buffer holds, or more than 20 s ahead of it, gets a new buffer and
  decoders; the old ones are **kept for 300 s** (`audio_buffer.py`: `is_valid`,
  `_monitor_inactivity`).

| 20 taps 0.5 s apart, 537 s per tap, 2.10.9, from 0 ffmpeg and 416 MB | seeks sent | ffmpeg after | memory after |
| --- | --- | --- | --- |
| one seek per tap, as Amsel until 2026.11.10, the official app and the web UI send them | 20 | 33 | 2.61 GB, 2.53 GB 30 s later |
| through `SeekLane` | 1 | 4 | 793 MB |

## 3. Amsel

- **One place for where a queue is** (`Seeks`, in `Speakers`): a seek shows its target at once,
  standing; reports count again a second after the server's answer, the latest that fits the
  target; one that never fits gives way after 30 s. Now Playing, the notification and the car read
  the same.
- **One seek at a time a speaker** (`SeekLane`): taps within 600 ms are one seek; the next waits for
  the answer; a step counts from the place shown.
- **A resting speaker is sent nothing**: the place is parked and the next play starts there, so a
  seek keeps a pause as in Lissen and Absorb, and costs the server nothing.

Proof kept in the build: `SeeksTest` (the recorded traces), `SeekLaneTest`, `SpeakerCommandsTest`,
`RemotePlayerSpokenTest`; against real servers in CI `LiveSeekTest` and the seek steps of
`FirstRunJourneyTest` (the whole app, through the session). By hand, with a long book:
`MA_URL=… MA_SEEK_URI=library://audiobook/2 MA_SEEK_TAPS=20 MA_SEEK_GAP_MS=500 ./gradlew
:playback:testDebugUnitTest --tests '*LiveSeekTest*'`.

Not done: books and episodes straight from Audiobookshelf on This phone without a download
(`docs/PARKING-LOT.md`). Until then a seek on This phone costs what it costs the official app: one
new stream.
