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
| Pad | one row under four cells wide, or what plays left out | the buttons alone, in as few rows as hold them, in the frame's middle |
| Line | one row, no buttons | the speaker over the title |
| Card | two rows and more | the speaker, the title, the line under it and the time, above or below rows of buttons |
| Poster | two cells wide, tall enough for the cover across and the rows | the cover across the frame, the speaker and the title under it, rows of buttons at the foot or the top |
| Bar | one row, four cells wide | the cover, the speaker over the title, the buttons at the end or the start |
| Player | two rows and more, four cells wide | the cover beside what plays, in its middle, above or below rows of buttons |

What plays (the cover and the words) is a part of the widget as its buttons are: before them, as it
comes, after them, or left out. The two parts stand at the widget's two ends, the room between them;
a part with nothing in it leaves the other alone. Left out, the buttons fill the widget (Pad): in as
few rows as hold them, each as full as the next, their cells as wide as the row allows and about as
high, at least 56; a last row with fewer keeps the others' cell width, in its middle. Four buttons
in a square stand two by two. Buttons one row cannot hold take more rows, each as full as the next
and 4 dp apart, as long as what plays keeps its speaker, a title line and the line under it: two
cells by three hold five, four by three eight. A tall two-cell widget shows its cover across only
where its rows leave room for it.

The layouts hold the frame and the places; the cells and the words are added at draw time
(`RemoteViews.addView`): rows before what plays and rows after it, of which one place is used, and
the words in the layout of their size. So the order is the person's without a layout for each order,
and no layout carries eight fixed buttons. The picker's preview and a widget's first moment show
fixed layouts of the buttons as they come (`widget_preview_*`).

## Frame and cells

The first two layouts placed bare glyphs by their measured ink at the sizes the launcher reported.
On a real home screen the gaps came out uneven: glyphs of different widths left unequal gaps between
them, and a row gave up room at its ends to keep a third button. Google's own widget layouts
(Jetpack Glance canonical layouts, `ToolBarLayout`: 12 dp padding, equal-width cells, 24 dp icons,
48 dp targets) show the way that cannot drift:

- **One frame:** 16 dp on all four sides of every form, Android's own room inside a widget
  (`system_app_widget_internal_padding`); 12 dp put the words too near the corner.
- **Cells:** each button is a cell with the corner of the widget less the frame (28 − 16 = 12). Cells
  share their row's width equally, 4 dp apart. A cell's shape stands 2 dp inside its touch target on
  either side, so the targets meet and reach 2 dp into the frame: three targets of 48 dp fit two
  cells of a usual phone (172 dp), each cell 44 dp wide. In one row they are 56 dp high in its
  middle, as under words, not a capsule the height of the widget.
- **Bare buttons** draw nothing at a row's ends, so their targets reach sideways to 4 dp from the
  widget's edge (`WidgetSizes.REACH`): a square 156 dp wide holds three of them where two cells
  stand, and every glyph still keeps the frame.
- **Targets stay 48 dp high:** in a row lower than a cell and its frame (a strip 72 dp high), the
  targets reach into the frame above and below as far, and the cells drawn in them keep the frame.
- **Shading:** the person chooses which cells show (Button backgrounds): play and pause alone, as it
  comes (Google's expressive toolbar fills its main button alone); every one; or none. A button
  without its cell shows the cell's shade while pressed. Its place stays the same either way, so the
  glyphs stand as evenly as the cells (`WidgetInsetsTest`).
- **Words:** their first letter's ink (its side bearing, the font's metrics read on the phone) and
  the speaker's glyph stand on the frame; after the buttons, their last baseline stands on it at
  the foot; in a row or a bar their ink, not their box, stands in the middle, beside a player's
  cover in the cover's middle. They give way in order as the height or the text size asks: the time,
  the title's second line, the line under the title; then the speaker and the title grow an eighth
  smaller, 4 dp apart (`widget_words_small`), before the speaker's line goes, so a strip lower than
  usual keeps both: from 62 dp, as the two small lines stand in its middle by their ink and their box
  may take its lead above and below the frame. Where every line has
  room, as on a card two by two and a half or a player three rows high, they are a fifth larger
  (`widget_words_large`).
- **Time:** while a song plays, where it stands of its length; while a book or an episode plays,
  what is left at its speed. A `Chronometer` ticks it on the launcher's side, so Amsel draws nothing
  for it; at rest it stands as plain words, as a stopped `Chronometer` works its time out again
  whenever the launcher lays the widget out anew and runs on (AOSP `Chronometer`).
- **Covers:** a bar's and a player's are as high as their place and as wide as high; a poster's as
  wide as the frame.
- **The speaker's line** is set regular under the bold title, and the speaker's volume stands after
  its name, wherever the speaker reports one (not This phone): the volume's glyph and its number, as
  Now Playing has them. Compared with the line's end and the widget's top corner, beside the name it
  reads as the speaker's, alike in every form. It stays whole; the speaker's glyph gives way before
  the name is cut, then the name is.
- Buttons that a size cannot hold give way whole: play and pause last, the others from the start, so
  the end stays, where play and next stand as a widget comes; at a large text size a bar gives a
  button way before its words. A button with nothing to act on in what plays gives way too: the heart,
  shuffle and repeat for a book, an episode or a station.
- `WidgetInsetsTest` draws every form at the size it was laid out for and at sizes 8 to 16 dp smaller
  and larger, and measures the frame and the gaps in pixels; `WidgetFitTest` checks that no target
  is narrower than 48 dp at any width.

## Looks

- **Cover:** the cover of what plays, blurred small and scaled up, under a veil of paper or of
  black, whichever lets the words read; at night the cover lies dimmed. Nothing playing: Surface 1.
- **Darkened:** the cover itself, sharp, cropped to the widget, under a black veil that lets paper
  read on any cover (5.8 to 1 on a white one, the quiet words 3.9), darker at night.
- **Plain:** the system's colours (Material You) from Android 12, as Clock and Weather; Amsel's
  paper before.
- **Clear:** no ground; the words and glyphs in ink or paper as Android advises for the wallpaper
  (`WallpaperColors.HINT_SUPPORTS_DARK_TEXT`), drawn again when the wallpaper changes.

Cells are the tone's shade: ink or paper over the cover, the system's secondary container in Plain.
Under the cover looks, from Android 12, play and pause take the cover's own colour where it has one
(the hue most of its coloured pixels share, deep on a light ground, bright on a dark one), its glyph
in ink or paper, whichever reads (`WidgetArt.accent`).
Covers shown sharp keep Amsel's cover corners (rule 7) and at night the night's range, as in the
app; a Play … button's cover fills its cell, cut from the picture's middle where the cell is not
square. The corners of the widget are the launcher's; the words are in the phone's sans-serif with
Amsel's weights, the one font a widget can count on.

## Buttons

| Button | Does | Shows |
| --- | --- | --- |
| Play and pause | what the dock's play does | play or pause, on the disc |
| Next | the next song; 30 s forward in a book or an episode | skip next |
| Previous | the song before; 15 s back in a book or an episode | skip previous |
| Favourite | the song's heart, as Now Playing's | the heart, filled when it is one |
| Shuffle, Repeat | as Now Playing's; repeat goes off, all, one | quieter while off; repeat one's own glyph |
| Quieter, Louder | the server's own step, as its volume up and down take it: its Volume step setting, else 1 near silence and near full, 2, and 3 in the middle; Louder stops at the volume limit and says so | speaker low, high |
| Speakers | opens Play on (`amsel://speakers`) | the speaker |
| Group | opens Play on at its group step (`amsel://group`): the speaker in use's, or where it cannot lead a group (This phone) the first speaker that can, one that plays first | a link |
| Up next | opens Up next over Now Playing (`amsel://up-next`); in a book or an episode, its chapters, as Now Playing's last button | the list; the chapters' list |
| Continue listening | the book or episode heard last (`Catalog.inProgress(1)`) | a book |
| Play … | one album, playlist, artist, station, book or podcast, picked once | its cover, as large as its cell, a cover's corners; darker while pressed |

None to eight, in the person's order; the widget's page says how many fit at its size and which. As they
come, as the owner sets theirs: Button, play and pause; Buttons and Now playing, shuffle, the heart,
the speakers, play and next, of which two cells hold the speakers, play and next, and the owner's
narrower two play and next. A widget saved with the earlier transport, untouched, comes so too.

## The speaker

As it comes, a widget shows and acts on the speaker Amsel plays on. Its page can keep it to one
speaker ("Kitchen") whichever Amsel is on; This phone is not offered, as it plays only where Amsel
plays.

## Its page

A widget is placed without a page (`configuration_optional`); a long press on it, the launcher's way
to change a widget (`reconfigurable`), opens its page, as does placing it before Android 12. The
page shows the widget at its size on the home screen, over the wallpaper's own colour, and under it
three rows, each opening its choices with a line on what each does: the speaker, the background
(blurred cover, darkened cover, plain, clear) and which buttons have a background (play alone, all,
none, which fits more). Then the widget's parts in their order: what plays, with its switch, at
either end, and the buttons, each to change or take away; every part moves by its handle, a held
press or TalkBack's actions, as on Amsel's arrange pages, and what plays lands at the nearer end.
Where the size cannot hold every button a sentence says that play and the last so many show and to
drag the most needed down, and each hidden one says when it shows. One more while there are fewer
than eight; the last part stays. Play … picks from the library by kind, played lately first, as the
Library tab has it, or with Search. Done saves; leaving leaves the widget as it was.

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
- Quieter, Louder, shuffle, repeat and the heart act on the speaker the widget shows, from what it
  shows (`StateTaps`): a tap that starts Amsel comes before Amsel knows its speaker, and the volume
  went to the phone's own. Each shows at once, on the widgets and in Amsel (`OptimisticTransport`,
  `Favorites`); the server takes the step by its own setting, as its volume up and down do, and the
  widget shows at once where it lands by the setting last read (`config/core/get_value`, asked in
  the background, so a tap never waits for it); two taps in a row step on from the first before the
  speaker reports it.
- No `PendingIntent.getForegroundService`: a service started so must show a notification within
  seconds, which a pause or a skip past the end never does.
- Play and pause change at once and the speaker confirms; a command that fails puts its sentence
  where the artist stands until the next change.

## Seen by TalkBack and in the picker

The widget as a whole says what plays, by whom, where and how loud ("Lanterns, Marlene Vogt. Playing
on Kitchen. Volume, 40 %") before its buttons; shuffle, repeat and the heart say whether they are on. From Android 15
the widget picker shows each kind with what plays now (`WidgetPreviews`, about once an hour, as
Android allows).

## Swiping

A widget sees taps and vertical swipes only: the home screen takes a sideways swipe to page (the
developer guide's widget overview; Launcher3 `Workspace.determineScrollingStart` pages on any drag
within 30° of sideways). A `StackView` flicks up and down through cards, drawn as a stack with the
next card behind; a `ViewFlipper` changes only on a tap or a timer. A sideways swipe between a cover
and the buttons is not possible; parked with what is (`docs/PARKING-LOT.md`).

## Staying current

What plays on the speakers changes on the server, which cannot wake the phone (Music Assistant has
no push), so the widgets keep up three ways:

- **Live while Amsel follows the music:** in front, or while music goes through its player (the
  notification), its socket is open and the widgets are drawn anew whenever what they show changes.
  A socket that gave up in the phone's sleep (two minutes out of reach) opens again as the screen
  comes on or the phone joins a network, not when Amsel is opened next.
- **Looks while the socket is closed** (`WidgetLooks`, a JobScheduler job: no library, no
  permission): every 30 minutes while there are widgets, and once when Android asks for them anew
  (`onUpdate`); never timed to what plays. A look opens a socket of its own
  (`Session.glance`), reads the speakers and their queues, draws the widgets and closes it; nothing
  else a connect does (the library, Home, a new token) comes with it. Only with a network and not on
  a low battery; while the phone sleeps (Doze) Android holds looks back, and one that is due runs as
  soon as it wakes, so the widgets are right when the screen comes on. Jobs are not kept over a
  restart, which takes a permission: the widgets' update after it plans them again and looks at once.
- **At every tap,** which opens Amsel's socket for a minute.

Closed on purpose, the socket leaves the widgets as they were: the speaker plays on, and they say so
until a look says otherwise (`Session.upToDate`). Only where the server could not be reached do they
rest as last heard. `updatePeriodMillis` stays 0: a job waits for a network and a battery that is not
low. Each widget's setup and what they showed last on each speaker are kept on the phone
(`files/widget`); their covers come from Amsel's own cover cache.

How others keep up (November 2026): calendar and note widgets show what is on the phone, which
changes there or through their maker's push; Spotify keeps its notification and connection while a
speaker plays, and its servers wake the app when one starts elsewhere; Home Assistant's widgets look
every 30 minutes while the screen is on, and live only with a permanent notification or notification
access. Android itself: 30 minutes at the least for a widget's own updates, 15 for a periodic job,
cached apps get the screen coming on only once they run again (Android 14), and an app with a widget
stands in the active standby bucket, where jobs are hardly limited. Live widgets while nothing plays
through Amsel are parked (`docs/PARKING-LOT.md`).

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
`Widgets` (following, drawing and taps, in `AppGraph`), `WidgetLooks` and `WidgetPlan` (looks while the socket is closed), `WidgetRender` (sizes and pictures),
`WidgetFit` (forms and counts), `WidgetViews`, `WidgetDrawing`, `WidgetCellsViews` and
`WidgetWordsViews` (the views),
`WidgetText` (type and glyph ink), `WidgetTone` (looks), `WidgetArt` (blur, cover corners, the
cover's colour), `WidgetTaps`, `SessionHands` and `SpeakerHands` (taps), `WidgetActions`,
`StateTaps` and `WidgetCommands` (volume, shuffle, repeat, heart), `WidgetPreviews`, `WidgetStore`,
and the page (`WidgetSetupActivity`, `WidgetSetupScreen`, `WidgetSetupParts`, `WidgetSetupSheets`,
`WidgetSetupViewModel`, `WidgetPicker`); `res/layout/widget_*.xml`, `res/xml/widget_*info.xml`.
Tests: `WidgetLooksTest`, `WidgetFitTest`, `WidgetInsetsTest`, `WidgetViewsTest`, `WidgetsTest`, `WidgetStoreTest`,
`WidgetArtTest`, `WidgetLookTest`, `StateTapsTest`, and `WidgetShots` (every form, the cells, wide
and tall widgets, what plays after the buttons or left out, a low strip, the looks, the states, the
page and its sheets; light, dark, 200 % and German).
