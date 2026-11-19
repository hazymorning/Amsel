# Widget: what plays, and the buttons each person sets (owner's wish, 2026-11-19)

One widget, three ways onto the home screen by the size it starts at: **Button** (1 × 1, as large
as an app's icon), **Buttons** (2 × 1) and **Now playing** (2 × 2). Each can be resized to any size
and takes the form that size holds. No new permission and no new library: plain `RemoteViews`;
Glance would add itself and WorkManager and draws `RemoteViews` all the same.

## Forms

The launcher reports a widget's sizes (`OPTION_APPWIDGET_SIZES`, its portrait and landscape before
Android 12); Amsel picks the form, the number of buttons and how much of what plays for each, and
draws again when a widget is resized. Where everything stands is the layouts' own work, so it holds
at whatever size the launcher finally draws.

| Form | Size | Holds |
| --- | --- | --- |
| Tile | under two cells both ways | the whole tile is one button: its cell in the frame; a Play … button is its item's cover; with no button, the cover of what plays |
| Row | one row, under four cells wide | the buttons as cells filling the frame; with none, the speaker over the title |
| Card | two rows and more | the speaker, the title and the line under it, over a row of cells |
| Bar | one row, four cells wide | the cover, the speaker over the title, the cells at the end |
| Player | two rows and more, four cells wide | the cover beside what plays, over a row of cells |

## Frame and cells

The first two layouts placed bare glyphs by their measured ink at the sizes the launcher reported.
On a real home screen the gaps came out uneven: glyphs of different widths left unequal gaps between
them, and a row gave up room at its ends to keep a third button. Google's own widget layouts
(Jetpack Glance canonical layouts, `ToolBarLayout`: 12 dp padding, equal-width cells, 24 dp icons,
48 dp targets) show the way that cannot drift:

- **One frame:** 12 dp on all four sides of every form, set in the layouts (`widget_padding`).
- **Cells:** each button is a cell with the corner of the widget less the frame (28 − 12 = 16). Cells
  share their row's width equally, 4 dp apart; play and pause is the filled one. A cell's shape
  stands 2 dp inside its touch target on either side, so the targets meet and reach 2 dp into the
  frame: three targets of 48 dp fit two cells of a usual phone (172 dp), each cell 46.7 dp wide.
- **Words:** their first letter's ink (its side bearing, the font's metrics read on the phone) and
  the speaker's glyph stand on the frame; in a row or a bar their ink, not their box, stands in the
  middle. They give way in order as the height or the text size asks: the line under the title, the
  title's second line, the speaker's line; the level after the speaker's name whole or not at all.
- **Covers:** a bar's and a player's are as high as their place and as wide as high.
- Buttons that a size cannot hold give way whole, from the end; at a large text size a bar gives a
  button way before its words.
- `WidgetInsetsTest` draws every form at the size it was laid out for and at sizes 8 to 16 dp smaller
  and larger, and measures the frame and the gaps in pixels; `WidgetFitTest` checks that no target
  is narrower than 48 dp at any width.

## Looks

- **Cover:** the cover of what plays, blurred small and scaled up, under a veil of paper or of
  black, whichever lets the words read; at night the cover lies dimmed. Nothing playing: Surface 1.
- **Plain:** the system's colours (Material You) from Android 12, as Clock and Weather; Amsel's
  paper before.
- **Clear:** no ground; the words and glyphs in ink or paper as Android advises for the wallpaper
  (`WallpaperColors.HINT_SUPPORTS_DARK_TEXT`), drawn again when the wallpaper changes.

Cells are the tone's shade: ink or paper over the cover, the system's secondary container in Plain.
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
under it the look (Cover, Plain, Clear), the speaker, and the buttons: each to change, take away
or move by its handle, a held press or TalkBack's actions, as on Amsel's arrange pages; one more
while there are fewer than four. A button the size cannot hold says so in its row. Play … picks
with Search, its recent searches first. Done saves; leaving leaves the widget as it was.

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
`WidgetFit` (forms and counts), `WidgetViews`, `WidgetDrawing` and `WidgetWordsViews` (the views), `WidgetText` (type and glyph ink), `WidgetTone` (looks), `WidgetArt` (blur, cover
corners), `WidgetTaps`, `SessionHands` and `SpeakerHands` (taps), `WidgetStore`, and the page
(`WidgetSetupActivity`, `WidgetSetupScreen`, `WidgetSetupButtons`, `WidgetSetupSheets`,
`WidgetSetupViewModel`, `WidgetPicker`); `res/layout/widget_*.xml`, `res/xml/widget_*info.xml`. Tests: `WidgetFitTest`,
`WidgetInsetsTest`, `WidgetViewsTest`, `WidgetsTest`, `WidgetStoreTest`, `WidgetArtTest`,
`WidgetLookTest`, and `WidgetShots` (every form, the looks, the states, the page; light, dark, 200 %
and German).
