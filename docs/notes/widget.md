# Widget: what plays, and four buttons of one's own (owner's wish, 2026-11-19)

One widget for the home screen, half the screen wide and about square: 2 × 2 cells
(`targetCellWidth`, `targetCellHeight`), larger when dragged. It shows the speaker in use and what
plays there over its cover, and four buttons that each person sets. No new permission and no new
library: plain `RemoteViews`. Glance would add itself and WorkManager and draws `RemoteViews` all
the same.

## Look

- The cover of what plays fills the widget in its own colours, softly blurred: Amsel blurs a small
  copy (96 px) and the launcher scales it up. The words take ink or paper, whichever reads better on
  that cover, over a light veil of the same. At night (the system's dark theme) the cover lies dimmed
  and the words are paper, so a white cover never glares.
- From the top, 16 in: the speaker's glyph and name, as the dock names it; the title, bold, two lines
  at most; the artist, the author or the podcast. The four buttons share the widget's width at its
  foot, a quarter each (about 44 dp at 2 × 2 on a usual phone, 48 and more at three cells) and 52
  high. Play and pause wears Amsel's play disc; the others are bare glyphs; a Play … button is its
  item's own cover, 28 dp, with the cover corners. With Quieter or Louder among them, the level
  stands after the speaker's name in the quieter tone, as a view of its own: a long name pushes it
  out before it is cut itself.
- Nothing played yet: Surface 1, the speaker's name over "Not playing", as on the dock. Signed out:
  "Amsel" over "Sign in to Amsel", no buttons, and a tap opens it.
- The corners are the launcher's (`system_app_widget_background_radius`, on the view with
  `@android:id/background` and `clipToOutline`, Android 12 and up); below, the widget is square, as
  widgets were then. The words are in the phone's font: a launcher inflates a widget in a restricted
  context, where `TextView` skips an app's font resources, so Commissioner cannot be used;
  `textFontWeight` carries Amsel's weights. A launcher inflates only the classes widgets may use, so
  the veil is an `ImageView`, not a `View`.
- A tap anywhere but a button opens Now Playing (`amsel://now-playing`; Home when nothing plays).

## Buttons

| Button | Does | Shows |
| --- | --- | --- |
| Play and pause | what the dock's play does | play or pause, on the disc |
| Next | the next song; 30 s forward in a book or an episode | skip next |
| Previous | the song before; 15 s back in a book or an episode | skip previous |
| Quieter, Louder | the server's volume step; Louder stops at the volume limit | speaker low, high |
| Speakers | opens Play on (`amsel://speakers`) | the speaker |
| Continue listening | the book or episode heard last (`Catalog.inProgress(1)`) | a book |
| Play … | one album, playlist, artist, station, book or podcast, picked once | its cover |

As it comes: Previous, Play and pause, Next, Speakers. Each button acts on the speaker in use, as
Amsel's own buttons do.

## Setting the buttons

Placing the widget opens its page (`WidgetSetupActivity`, the widget's `configure`, in Amsel's
theme): the widget as it will look, drawn from the same views at the size the launcher gives it,
with what plays now, and under it the four buttons as rows, left to right. A tap on a row, or on its
button in the picture, opens a sheet of the choices, the current one ticked. Play … opens Search
under a slim bar to pick from, its recent searches first; whatever is tapped or held is the pick.
The defaults are set already, so Done alone places the widget. Later the launcher's own way to change a widget (a long press;
`widgetFeatures="reconfigurable"`) opens the same page. Each widget keeps its own buttons, stored by
its id; removing the widget forgets them.

## Taps, also while Amsel is closed

- Every button but Speakers sends one broadcast to Amsel's own receiver (`WidgetTaps`, an explicit,
  immutable `PendingIntent`, which carries whether play or pause showed). A widget tap lets the
  receiving app come to the front for 10 s: the launcher sends it with background starts allowed
  (`RemoteViews`), the broadcast passes that to the receiving process
  (`BroadcastQueueImpl.dispatchReceivers`), and `ActiveServices` accepts it for a foreground
  service. It is the window a media key gives: when sound starts, Media3 brings the playback service
  to the front with its notification, so This phone plays too.
- Play presses the media button, as a headset does: the service starts in front at once and the
  speaker carries on with what it holds, the stand-in ("Amsel", loading) showing until it reports.
- Pause, the skips and the volume go to the one Media3 session through a controller
  (`SessionHands`), which starts the service when it is not running and lets go once the command is
  on its way; it waits up to 5 s for the speaker to report, as Amsel was closed. So the widget keeps
  Amsel's rules: a book's skips, the volume limit, failures said plainly. This phone's Quieter and
  Louder move the phone's own media volume, with the system's slider showing.
- Continue listening asks the server for the book or episode heard last; it and Play … reach the
  session's speaker as a car's play does (`setMediaItem`, then `play`), and their title shows while
  the speaker starts them.
- No `PendingIntent.getForegroundService`: a service started so must show a notification within
  seconds, which a pause or a skip past the end never does. Media3 guards only play
  (`MediaButtonReceiver` drops every other key from Android 8). The docs' warning against
  `startForegroundService` in `AppWidgetProvider.onReceive` concerns the system's update broadcasts,
  which carry no tap.
- Play and pause change at once (Amsel's optimistic state), and the speaker confirms. A command that
  fails puts its sentence where the artist stands until the next change.

## Staying current

Amsel draws the widget anew whenever the speaker in use, what plays there, play and pause or the
level change, while it is connected: in front, or while music goes through it, as today. When the
connection closes, the widget rests as last heard, with the play glyph. No polling and no periodic
update (`updatePeriodMillis` 0): what the web UI or another phone starts shows once Amsel connects
again, which any button does. The last state and its blurred cover are kept on the phone, so a
restart draws the widget without the server.

## Not in this first widget

A widget for one room, with a speaker of its own instead of the one in use; a progress line; layouts
for other sizes.

## Code

`:app`, package `widget`: `AmselWidget` (the provider), `WidgetTaps` and `SessionHands` (taps),
`WidgetViews` and `WidgetArt` (the views, the blurred cover and the buttons' covers), `Widgets` (drawing
and taps, in `AppGraph`), `WidgetStore` (each widget's buttons and the last look, in `files/widget`),
the setup page (`WidgetSetupActivity`, `WidgetSetupScreen`, `WidgetSetupViewModel`, `WidgetPicker`);
`res/layout/widget.xml`, `res/xml/widget_info.xml`, `res/values*/widget.xml`, `strings_widget.xml`.
Tests: `WidgetLookTest`, `WidgetStoreTest`, `WidgetArtTest`, `WidgetViewsTest` (TalkBack's words),
and `WidgetShots` (the widgets on a home screen and the page, light, dark, 200 % and German).
