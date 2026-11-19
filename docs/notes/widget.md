# Widget: what plays, and the buttons each person sets (owner's wish, 2026-11-19)

One widget, three ways onto the home screen by the size it starts at: **Button** (1 × 1, as large
as an app's icon), **Buttons** (2 × 1) and **Now playing** (2 × 2). Each can be resized to any size
and takes the form that size holds. No new permission and no new library: plain `RemoteViews`;
Glance would add itself and WorkManager and draws `RemoteViews` all the same.

## Forms

The launcher reports a widget's sizes (`OPTION_APPWIDGET_SIZES`, its portrait and landscape before
Android 12); Amsel lays out each one exactly (`WidgetFit`) and hands them over together, so the
launcher shows the right one at once, and draws again when a widget is resized.

| Form | Size | Holds |
| --- | --- | --- |
| Tile | under two cells both ways | the whole cell is one button: its glyph on a round ground of 44 dp in the middle; a Play … button is its item's cover; with no button, the cover of what plays |
| Row | one row, under four cells wide | the buttons in the middle of its height; with none, the speaker over the title |
| Card | two rows and more | the speaker, the title and the line under it over the buttons |
| Bar | one row, four cells wide | the cover, the speaker over the title, the buttons at the end |
| Player | two rows and more, four cells wide | the cover at the start, the words over the buttons beside it |

## Room, measured to the ink

Every distance is to the ink, not to a view's box: a glyph's drawing (`IconInk`), the disc, a cover,
a word's first letter (its side bearing) and capitals (its font's metrics, read on the phone).

- A card and a player keep 16 dp on all four sides: the speaker's glyph at the top, the first word
  and the first button at the start, the last button at the end, the tallest button at the foot.
- A row keeps at its ends the room above and below its tallest button, less only where that room
  would cost a button, never under 12. A bar keeps around its cover what its height gives, 8 to 16,
  and the same at its end.
- Buttons are 48 dp targets, each ink in the middle of its own, spread from the first ink to the
  last; a single one stands in a row's middle. Those a size cannot hold give way whole, from the
  end. Where a target would reach past the widget, its ink moves inside it instead.
- The words give way in order as the height or the text size asks: the line under the title, the
  title's second line, the speaker's line. The level after the speaker's name shows whole or not
  at all.
- `WidgetInsetsTest` draws every form and measures its ink from each edge; `WidgetFitTest` lays out
  every width and height and checks the same.

## Looks

- **Cover:** the cover of what plays, blurred small and scaled up, under a veil of paper or of
  black, whichever lets the words read; at night the cover lies dimmed. Nothing playing: Surface 1.
- **Plain:** the system's colours (Material You) from Android 12, as Clock and Weather; Amsel's
  paper before.
- **Clear:** no ground; the words and glyphs in ink or paper as Android advises for the wallpaper
  (`WallpaperColors.HINT_SUPPORTS_DARK_TEXT`), drawn again when the wallpaper changes.

Covers shown sharp keep Amsel's cover corners (rule 7) and at night the night's range, as in the
app. The corners of the widget are the launcher's; the words are in the phone's sans-serif with
Amsel's weights, the one font a widget can count on.

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

None to four, in the person's order. As they come: Button, play and pause; Buttons and Now playing,
the transport. With Quieter or Louder among them, the level stands after the speaker's name.

## The speaker

As it comes, a widget shows and acts on the speaker Amsel plays on. Its page can keep it to one
speaker ("Kitchen") whichever Amsel is on; This phone is not offered, as it plays only where Amsel
plays.

## Its page

A widget is placed without a page (`configuration_optional`); a long press on it, the launcher's
way to change a widget (`reconfigurable`), opens its page, as does placing it before Android 12.
The page shows the widget at its size on the home screen, over the wallpaper's own colour, and
under it the look (Cover, Plain, Clear), the speaker, and the buttons, each to change or take
away, one more while there are fewer than four. A button the size cannot hold says so in its row.
Play … picks with Search, its recent searches first. Done saves; leaving leaves the widget as it was.

## Taps, also while Amsel is closed

- Every button but Speakers sends one broadcast to Amsel's own receiver (`WidgetTaps`, an explicit,
  immutable `PendingIntent`, which carries whether play or pause showed). A widget tap lets the
  receiving app come to the front for 10 s (`BroadcastQueueImpl.dispatchReceivers`, then
  `ActiveServices`): the window a media key gives.
- On the speaker Amsel plays on, play presses the media button, as a headset does: the service
  starts in front at once and the speaker carries on with what it holds. The other buttons go to the
  one Media3 session through a controller (`SessionHands`) that waits up to 5 s for the speaker to
  report. This phone's Quieter and Louder move the phone's own media volume.
- On a speaker of the widget's own, every button goes through a player for that speaker alone
  (`SpeakerHands`, the same `RemotePlayer` as the session's), let go after the command.
- No `PendingIntent.getForegroundService`: a service started so must show a notification within
  seconds, which a pause or a skip past the end never does.
- Play and pause change at once and the speaker confirms; a command that fails puts its sentence
  where the artist stands until the next change.

## Staying current

Amsel draws the widgets anew whenever what they show changes, while it is connected: in front, or
while music goes through it. When the connection closes they rest as last heard. No polling and no
periodic update (`updatePeriodMillis` 0). Each widget's setup and what they showed last on each
speaker are kept on the phone (`files/widget`); their covers come from Amsel's own cover cache.

## Survey (November 2026)

Spotify, YouTube Music, Apple Music, Plexamp, Symfonium, Poweramp, Sonos, Pocket Casts,
Audiobookshelf, Absorb, Lissen, Samsung Music, Auxio, Retro Music, Namida, Home Assistant. The best
are one resizable widget that adds content as it grows (Auxio, Pocket Casts, Plexamp). Settings,
where there are any, are the speaker, the background and the buttons; corners, padding and fonts are
Poweramp's and Namida's extras. Seen to fail: controls clipped at slim sizes, empty space at large
ones, stale widgets, too much image memory. Home Assistant actions stay out
(`docs/PARKING-LOT.md`).

## Code

`:app`, package `widget`: the providers (`AmselWidget`, `AmselButtonWidget`, `AmselButtonsWidget`),
`Widgets` (following, drawing and taps, in `AppGraph`), `WidgetRender` (sizes and pictures),
`WidgetFit` and `WidgetPlace` (the geometry), `WidgetViews`, `WidgetDrawing` and `WidgetWordsViews`
(the views), `WidgetText` (type and glyph ink), `WidgetTone` (looks), `WidgetArt` (blur, cover
corners), `WidgetTaps`, `SessionHands` and `SpeakerHands` (taps), `WidgetStore`, and the page
(`WidgetSetupActivity`, `WidgetSetupScreen`, `WidgetSetupSheets`, `WidgetSetupViewModel`,
`WidgetPicker`); `res/layout/widget_*.xml`, `res/xml/widget_*info.xml`. Tests: `WidgetFitTest`,
`WidgetInsetsTest`, `WidgetViewsTest`, `WidgetsTest`, `WidgetStoreTest`, `WidgetArtTest`,
`WidgetLookTest`, and `WidgetShots` (every form, the looks, the states, the page; light, dark, 200 %
and German).
