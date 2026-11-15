# Amsel design (v7)

A quiet room in the evening: soft light, few things, each one chosen and placed with care. The
interface is ink on paper; the covers bring the colour; one muted moss marks what is alive. Every
choice can be explained in one sentence about calm, clarity or comfort.

The code is the source of truth for the values: `ui/theme` holds the tokens (`Color.kt`, `Type.kt`,
`Dimens.kt`, `SmoothShape.kt`, `Motion.kt`, `PressIndication.kt`, `CoverColors.kt`) and
`ui/components` the components. Screens use tokens and components only. Measurements are on a
411 x 891 dp phone.

## Principles

1. **Ink on paper.** Monochrome; colour only from artwork, plus moss for live states.
2. **Size, weight and space make hierarchy**: titles bold, captions semibold, secondary lines regular.
3. **Groups are visible.** Space inside a group is clearly smaller than space between groups.
4. **Derive, don't choose.** Margins, radii and widths follow from a few rules.
5. **One focal point per screen.** If two things compete, one gets quieter.
6. **Removal test.** If nobody would miss an element, it goes.
7. **Self-explanatory.** Controls look like what they do; gestures only repeat a visible control.
8. **Never fight for attention.** Nothing blinks or wears a badge, but for one moss dot on the
   band's speaker while music plays on it; a bar rises over the dock only to answer what the person
   just did.

## Rules (from the design review of Now Playing and Home)

Every screen follows these; the numbers live in `Dimens.kt`, `Type.kt`, `Color.kt` and `Lift.kt`.
Distances are measured ink to ink (cap tops, baselines, glyph ink), so they hold in any font.

1. **Margin 24.** Covers, headings, the bar's glyphs, pills and the dock stand on it.
2. **Words under a cover start a little in**: half the cover's corner radius, 4 under a tile, 8
   under Now Playing's artwork (`underCover`, `Radius.under`).
   Tile captions 8 in; Now Playing's words, line and controls 16 in (40 from the screen's edge).
3. **Cover corners by size**: 8 under 200 dp (rows, the dock, tiles), 16 above (Now Playing, a
   page's cover). Continuous corners; never a circle.
4. **Type**: six sizes and no others, each a clear step from the next: 30, 26, 20.5, 16, 14 and 12.
   Title 26/32 bold (Now Playing's track), Heading 20.5/28 bold (sections), Caption 14/20 semibold
   (tile titles, chips, group labels), Meta 12/16 regular, +0.25 tracking (every second line); the
   dock reads a size larger, its speaker Body at 560 over a line of Body's face at 14/20. A second
   line's baseline stands its line height + 4 under the first's (20 for Meta, 24 on the dock).
   Weight and colour make every other difference: Now Playing's artist is Body at 400, its "from"
   line Caption at 400. List titles and sentences are Body at 440, so headings carry the weight; a
   button's words stand at 520.
5. **Rhythm**: heading baseline to its row 22.5; cover to caption capitals 14; a small label's
   baseline (a settings group, the library's order) to what it labels 18; one section's last
   baseline to the next heading's capitals 48. Now Playing: cover 84 from the screen's top (28 under the bar), its foot
   to the title's capitals 46.5, title to artist baselines 26, artist baseline to the line 29.5, line
   to the play button 32; the speaker's pill ends 32 above the screen's foot, the room left lies above it.
6. **Pills and chips**: 36 high, glyph and word 16 from the ends and 8 apart, ink at 5.5%, words
   16/22 semibold. **A glyph beside words is as large as the
   words are set** (`wordGlyph`): its box is their size, so its ink stands a little taller than
   their capitals, and it grows with them; a caret after words is 0.82 of that. This holds
   wherever the two share a line: pills, chips, quiet actions, the search field, Now Playing's
   footer. The speaker's pill is a step up, larger by its padding and never stretched: 44 high
   and as wide as its words, name 16/22 bold from 20 in, the glyph 8 before it, a caret 18 from
   its end. A speaker's button says where, not whether: the play button beside it says what
   plays, so the button carries no mark of its own. And
   one glyph: the button that opens the speaker choice carries the speaker, whatever it plays
   on, a group or This phone included; the link glyph is Group's alone.
7. **Controls ink**: the chrome's glyphs and the pills' words are ink at 87% by day, ink at night;
   transport glyphs are ink; a toggle that is off is secondary.
8. **Shadows, by day only**: a large cover `0 1 2` and `0 24 48 -24` in warm umber at 5% and 38%; a
   play button `0 6 16 -8` in ink at 40%. At night a cover stands on its own: no glow, no shadow.
9. **Bars are paper; the dock is one surface**: bars take the page's own tone; the dock is Surface 1,
   solid and edge to edge, never blurred or see-through, never the cover's colour, with no card on
   it and no line along it. The status strip and a slim bar fill in, with a hairline under them,
   only while content runs under them. On the dock what plays stands on the margin: its cover (48)
   24 under the dock's edge and 24 in from the screen's, the skip's ink on the margin across; a
   cover there stands a tone above the dock (Surface 2 empty, and its floor at night). Under it the
   three places on a band a tone darker by day and lighter at night, each its glyph alone (24), 20
   over and under the glyphs; the player's band keeps 24 over and under its cover.

## Foundations

**Colour** (`Color.kt`). No pure black or white; neutrals lean slightly to stone.

| Token | Night | Day | Slot | For |
| --- | --- | --- | --- | --- |
| Background | `#131412` | `#F2F1EC` | `surface` | screens |
| Surface 1 | `#1B1C19` | `#E9E8E1` | `surfaceContainerLow` | cards, fields, sheets, skeletons, a cover without a picture |
| Surface 2 | `#252621` | `#DFDED6` | `surfaceContainerHigh` | the selected chip, tonal buttons, the message bar |
| Hairline | ink 8% | ink 8% | — | cover edges |
| Ink | `#E6E5DE` | `#191A17` | `onSurface`, `primary` | text, the filled play circle |
| Secondary | `#9A998F` | `#66665E` | `onSurfaceVariant` | artists, counts, times, inactive tabs |
| Faint | `#67675F` | `#87877E` | `outline` | inactive glyphs, separators; never reading text |
| Moss | `#A3B486` | `#4F6A3B` | `tertiary` | the playing ring, a running sleep timer |

Moss covers far less than 1% of any screen. The crowns of the picture at the foot of Settings are the
README's tints of moss; its sun by day is the paper raised by the step Surface 1 lies under it
(`#FBFAF7`), its moon the ink. Covers carry a hairline just inside their edge. At night a cover is
drawn between Surface 1 and the ink: its black stands a step above the page, as an empty sleeve
does, and its white is no brighter than the words, so a dark cover keeps its edge and a white one
never glares. Layers part by tone, never by shadow, and never by a line drawn round a box.

**Type** (`Type.kt`). Commissioner (OFL), Flair 25 for titles and 0 for text, static weights built by
`scripts/design/build-fonts.py`. Selection shows in colour, never weight. Times use tabular figures.

| Role | sp / weight | For |
| --- | --- | --- |
| Display | 30 / 650 | screen titles, a page's title |
| Title | 26 / 700 | the track on Now Playing |
| Heading | 20.5 / 700 | section headings, sheet titles |
| Body | 16 / 440 | titles in lists, sentences (a button's words 520, the mini player's speaker 560) |
| Caption | 14 / 600 | cover captions, chips, group labels |
| Line | 14 / 440 | the mini player's line under the speaker |
| Meta | 12 / 400 | artists, counts, secondary lines |
| Times | 12 / 500 | elapsed and left, tabular |
| Tab label | 12 / 560 | the tab bar |

**Space** (`Dimens.kt`). One side margin, 24 dp: every left edge (text, covers, chips, the dock) sits
on it and every right edge on its mirror. Distances come from 4, 8, 12, 16, 24, 32, 48 and 64,
measured to the letters (cap top and baseline, `Modifier.textInk`), not to text boxes. Big titles
are pulled left by their first letter's side bearing (`inkStart`); the play triangle sits right of
centre. Text never cuts a word (`Words`): it ends after the last whole word with an ellipsis, and
separators inside names (| • – ·) are drawn in faint.

**Shape.** Continuous corners (`SmoothShape`, 60% smoothing): 6 for thumbnails (≤ 64 dp), 12 for
tiles, cards and fields, 16 for the big artwork, page covers and sheets; full round for chips and
pills. Nested shapes are concentric (a row's pressed band: 6 + its 8 inset = 14). Every cover takes
these corners, an artist's picture too: artwork is never a circle. Circles only for the play buttons,
the ring, the volume dot, the refresh disc, the scrubber's letter, a glyph's press and the picture at
the foot of Settings.

**Icons.** Phosphor, every line 1.5 dp at every size: glyphs are 16, 24 or 32 dp, drawn in the
Bold, Regular or Light cut, whose lines are 1.5 dp there, and `InkGlyph` picks the cut by the size
(a glyph beside words grows with them and takes the nearest). Filled only for transport and the
active tab; three of Amsel's own to the same spec in `scripts/icons/custom/`: the speaker
(`ic_speaker`, a soft capsule, never a box), Up next (`ic_queue`) and Library. The caret after words,
a step smaller than they are, and the glyph inside a download's ring (12) are drawn from Phosphor's
line drawing with lines that are 1.5 dp at their size. `scripts/icons/fetch-icons.py` writes them,
their cuts and the ink table.

**Press.** No ripples: a soft ink highlight (6%) in the control's own shape and a press-in to 0.98,
over 120 ms (`PressIndication`).

## Effects (three, nothing else)

1. **Paper** (`Paper.kt`, `Dock.kt`): the dock and the status strip are the page's own paper,
   solid. The strip (and a slim bar on it) fills in with a hairline under it only while content
   runs under it, so at rest a page's wash shows through and nothing marks the top of the screen.
2. **The cover's hue** (`CoverColors.kt`): the strongest family of hues, weighed by area and
   colourfulness, muted strongly; Now Playing's page leans a few percent toward it. A black-and-white
   cover leaves the page plain. Nothing glows.
3. **The playing ring** (`PlayingRing.kt`): a thin moss ring (8 dp, 1.5 dp line, as the blackbird's
   eye) before the subtitle of whatever plays, in place of a track's number. It holds still.

A shelf of covers is a plain row: lamp, fold, turn and step back were tried and each was a step
back from it.

## Components

| Component | Anatomy |
| --- | --- |
| Dock (`Dock.kt`, `DockCard.kt`, `NavBar.kt`) | One surface of Surface 1, full width, with no card and no line: what plays, and under it the places. Cover 48 on the margin, 16 to the words: the speaker's name (Body, 560), so the dock says at once which speaker is in hand, a group's "+2" after it in the secondary tone and never cut, and under it title and artist on one line (Line, secondary) that, while it is longer than its room, rests at its start, glides to its end, rests and glides back, again and again, its hidden end faded; then play (28 dp glyph) and the skip: next for music, forward 30 s for a book or an episode, as the notification has them, its ink on the margin. Play and the skip stand a touch target apart. While nothing plays the row stays: an empty cover and the speaker's name over "Not playing"; a tap anywhere on it opens Play on. Under the player's band of Surface 1, with 24 over and under its row, the bar is a band of Surface 2 of four of equal width, each its glyph alone: Home, the speaker, Search and Library, inactive secondary, active ink and filled; TalkBack reads their names. The speaker is no place: its glyph stays secondary and opens Play on, and while music plays on the speaker in use a moss dot of 6 sits on its glyph's top end, cut free of the glyph by a 1.5 ring of the band's tone. The gesture handle lies in the room under the glyphs; with Android's buttons the glyphs stand 12 above them. The paper runs on under the system's bar, with no scrim. A tap on the card or a pull up anywhere on the dock opens Now Playing; a sideways swipe on the card is previous and next. No progress line: where the song is shows in Now Playing. |
| Speaker pill (`SpeakerPill.kt`) | Where the music plays, at the start of Now Playing's footer: a 44 dp pill of ink at 5.5%, as wide as its words, the speaker's glyph as large as the name, the name and a caret. Never stretched. Short of room it gives up its caret, then letters of the name, and last the name. A tap opens Play on. |
| Row (`ListRow.kt`) | 72 with a 56 cover (radius 6), 16 to the text; 56 or 64 for text rows. Title Body, second line Meta secondary. Track numbers stand on the margin (the first figure's ink on it), titles at 64. No menu: tap opens or plays, long press opens the actions, which also start an album, a playlist or an artist (Play, Shuffle) anywhere but on its own page, where the buttons are. Press: the band, 8 past the margins for a cover's row, 16 from words and glyphs for a row of words. |
| Tile (`MediaTile.kt`) | Width (W − 24 − 32) × 3/7, 16 apart, so every row shows two and a third; small covers (W − 24 − 36) × 3/10, 12 apart, three and a third; big covers (W − 48 − 16) / 2, two between the margins. A flung shelf comes to rest with a whole cover on the margin (`ShelfRow`); the next runs off the screen's edge as it is. Square cover, caption capitals 14 under: title Caption, second line Meta right under it. Every caption is two lines high (`TileCaption`): a title too long for one line takes the second line's place and the second line gives way, so two titles that begin alike read apart and the tiles of a shelf or a grid stand alike. A tile without a second line keeps its title to one. An artist's tile is like any other. |
| Second line | A row or a tile among two-line ones always has one: the server's (an artist, an owner) or, where it gives none, what the item is ("Radio", "Playlist", "Artist"). A list in which no item has one keeps to one line (the library's artists). What plays always has one: the artist, else the album, else what it is. |
| Home card (`HomeCards.kt`) | The grid's item: a 56 dp cover and its title (Caption, two lines at most) on a card of Surface 1 with the cover's corners (8), two to a row, 8 apart both ways. |
| Artist without a picture (`Artwork.kt`) | Four covers of its albums two by two, edge to edge in the one cover shape: its own first, then those it shares as a later artist, each newest first and compilations last, each picture once. With fewer than four, the first alone; with none, its initials. The index keeps them (`albumCovers`): worked out again after albums or artists sync, when an album comes, changes or goes, and when the artist loses its picture; an update to the artist keeps them. It wears them wherever it shows, read from the index with no call per row: the Library, Home and See all, Search and its top result, Similar artists, Recent searches; the car shows the first alone. An artist Search finds that the index does not hold wears the covers of the answer's own albums and songs by it. On its page, an artist the index has none for (a guest, an author, a music service's artist) wears those of the albums and popular songs the page lists, from what it showed last time until the server answers. |
| Cover without a picture (`TypeCover.kt`) | Set in type on Surface 1: the name in the Display face from the top corner (12% of the side, 10% in) on covers of 96 dp and more, otherwise, and for an artist always, the initials of the first two words, centred (36% of the side), in the secondary ink. Its letters keep their size at any font scale. |
| Continue listening (`ListeningTile.kt`) | A big cover with no card and no progress drawn on it, nothing on its corner. Title and the time left under it ("24 h 23 min left"), as any tile's caption; a tap offers to resume it. |
| Offer (`OfferBar.kt`, `Offers.kt`) | On Home, what plays at a tap (a station, an episode, Continue listening) wears nothing on its cover; a tap brings up a bar over the dock that offers it: on the margins in Surface 2 like a message, the cover (48) 12 in from its start, the title, under it the speaker's glyph and name, which a tap changes through Play on, and one big button in ink at its end, "Play" or "Resume". A second tap on the same cover plays at once. Something that rests on the speaker in use is asked about too; only what plays right now opens Now Playing at once. It rises half its height into place and goes after 8 s, with a swipe aside, or once taken; a message stands over it. Elsewhere a station or an episode plays at a tap, as a row does. |
| Hints (`Hints.kt`) | The same bar, right after something happened, with one step that helps: a song just liked goes into the last playlist used (or one chosen); after adding to a playlist, Open; a speaker that cannot be reached plays on This phone instead; one all the way down is turned up to 30 %; headphones connected, or the app opened with no Wi-Fi beneath any VPN, while a speaker plays brings the music to the phone; opened within half an hour of joining Wi-Fi while the phone plays, it goes back to the speaker last used; a queue that ran to its end goes on as an endless mix; a book or an episode started between 21 and 5 offers the sleep timer, once a night. About a speaker or a setting, the bar shows its glyph in place of a cover. Only while the app is in front, one at a time; a kind passed up three times in a row does not come again. Unliking a song says so with Undo, as a message. |
| Section heading (`SectionHeader.kt`) | Heading on the margin; "All" (Meta, secondary) on the right margin on its baseline; the whole row taps; no chevron. Heading to content 12; section to section 40. |
| Chips (`Chips.kt`) | 32 tall, 14 side padding, 8 apart, Meta. Unselected: the label alone in secondary, its letters on the margin. Selected: Surface 2 with ink. The row fades over 24 at its edge instead of cutting, and brings the chosen chip to its middle whenever the choice changes, by a tap or by a swipe between the pages; the library's lists share one row's place. |
| Tip (`TipCard.kt`, `app/Tips.kt`) | What the app keeps out of sight on purpose, said once: a card of Surface 1 between the margins above Home's first section, 32 over its capitals. A lightbulb as large as the words, one sentence (Body) 16 in from the card's edges, and a close glyph (16) in a 48 dp target at its end. One at a time, in a fixed order: Edit Home (a long press on a heading), Settings (a pull down), the long press on an album, a playlist or a song. A tip stays until it is swiped away to either side, closed, or the person does what it says, and is never shown again; the next comes another day. None in Edit Home, and none with TalkBack, which names every long press as it reads and finds Settings open. The mark is a tip's own: the glyph of what it tells of would read as the button for it. |
| Slider (`screens/player/PlayerSlider.kt`, the player's own) | 4 dp line, elapsed ink, the rest ink 15%. Seek handle: the ring; volume: a plain ink dot. A quick tap moves there and a sideways drag past the touch slop follows the finger; a swipe up or down and a long press leave it alone. While the speaker is still on its way to a place sought, the elapsed part is fainter (ink 45%). |
| Play (`Buttons.kt`) | Ink circle 72 (Now Playing), ink 56 (pages), tonal 40 (cards). Labelled buttons are 48 dp pills, round up to 64 dp tall: ink, or Surface 2. One that cannot be used yet is Surface 2 with a faint label. |
| Bars (`PageTopBar.kt`) | `MaterialScreen`: content under the status strip; a slim bar (56) with Back on the margin, the title centred in Body 500, actions on the right margin. `TabHeader`: Display title, capitals 16 under the status bar, an action centred on its capitals. |

## Screens

**Home.** No header: the first section's capitals 24 under the status bar, or a tip above it while
there is one to give (Components). Sections of
headings over small covers, tiles, big covers, a list, or a grid of cards (two columns, each a
cover and its title on a tone). Continue listening comes first. A long press on a section's title opens Edit
Home, which names itself in a slim bar with Done. There a section is its title, its sentence and ⋯
(Remove): a tap on the title renames it, a held press on the title or the sentence picks the section
up, and in Pinned a held press on a pin picks the pin up, the others making way; TalkBack moves both
through their actions. Pinned shows every pin there, so each can be put in its place.

**Library.** "Library" (Display), the chips 16 under it, the order in
words 16 under them ("Recently played ↓"; a tap moves to the next order; none when there is nothing
to sort), the list 8 under that. Albums, books and podcasts are a grid of covers; playlists and
artists are rows.
Once the title has scrolled away, a slim bar names the kind that shows. A sideways swipe goes to
the chip beside the chosen one, round from the last to the first, the items following the finger
while the header stays, and each kind keeps its own place in its list. Every kind's list is at hand
while the Library is open, so a swipe let go or a chip tapped hands over at once: the next list
comes in from its side as faint as the last one left, and fades up; there is never an empty frame. A playlist names its owner only when that is someone else.
What plays (or its album, book or podcast) carries the ring.

**Now Playing.** Collapse and the menu on the words' keyline (8 in from the margins), "from Blue
Hour" (Meta, secondary) between them. Artwork at the content width (16). Then three groups: title (Title, two lines)
and artist 32 under the artwork; the seek line 24 under them, times 8 under it (elapsed, and what
is left as "−2:23": always for a book or an episode, for music while the line is moved). One
layout, its words by what plays: a book's or an episode's chapter is the title and the book stands
under it; a station's song on air is the title and the station under it, after the song's artist,
and a station's line and times keep their room unseen, for a live stream has no place. Then the
transport 32 under the times: the 72 dp ink play, previous and next as 32
dp glyphs 24 from its edge, shuffle and the heart outside them (faint; on, shuffle is ink with a dot under it and the heart fills in ink). Shuffle and repeat order a queue of songs: for a station, a mix or what another source plays they are dimmed and take no touch. Then the
footer: the speaker's pill at its start; at its end, in a row 6 apart, the volume with its level
("silent" at nothing; in its sheet every step of the level is a detent the finger feels)
and Up next (Chapters for every book and episode, a bookmark before it). The pill and the glyphs
that open something stand on a quiet ground 44 high; the volume, a reading, stands bare beside
them. The grounds' outer edges are on the page's margins, under the artwork's edges,
and 24 above the screen's foot, exactly as far as from its sides (the gesture handle lies in that
room; over Android's buttons, their height and 12); each glyph in the middle of its ground. The speaker's name comes first:
where the row is short, the volume gives up its level, then the pill its caret, then letters.
A sleep timer that runs shows as a moss moon in the top bar, beside the menu it was set from. While the
server's endless mix plays, the line between collapse and the menu names it, "Endless mix · Blue
Hour", where it named the album. A sideways swipe on the artwork
is previous and next. Leftover height splits equally above the artwork
and before the volume; the artwork gives way on short phones. A book's or an episode's chapters
show only in the Chapters sheet, never on the page: a touch under the controls went to another
chapter.

**Search.** The Display title, a 48 dp field (radius 12, Surface 1); results dissolve under it.

**Pages** (album, playlist, artist, book, podcast). A title page: the cover at three quarters of the
content width, centred, an artist's as any other; the Display title, the artist and the details; one 56
dp ink play circle with shuffle and the menu 24 from its edge (a book: the play alone, its menu in
the bar; a podcast: no play). The list's first ink 40 under the circle, or under the last line.
An album ends with "More by" its artist and an artist with "Similar artists", where the server
finds some: a heading and one shelf of covers, the artists' with one line. The songs similar to a
song are a list under a slim bar, "Similar to “Lanterns”".

**Up next.** The slim bar on paper; the list opens on the song that plays, under the bar,
with the songs already played waiting above it. Only what comes next has a handle. What the
server's DJ says between two songs is a row like theirs, "DJ" where the artist stands.

**Sheets, Settings, setup.** Sheets on Surface 1 with 16 dp corners and a faint handle, over a
dim of 16% by day and 45% at night; the title on the margin, a second step without a back glyph;
a choice is marked by its tick alone, no band, and every choice keeps the tick's room, so a name
breaks alike chosen or not. Play on carries Group at the end of its heading ("Group +2" while a
group exists, for that group's leader) and, while speakers play along, Ungroup beside it as a glyph
on a round ground, both as high as Now Playing's speaker pill (44); where the heading and the two do not fit on one line, they stand under it. A speaker that plays carries the ring before what it plays ("Playing"
where that has no name); one that rests on something says so ("Paused · Lanterns"). The DJ's sheet,
from Now Playing's menu, is Off and the server's hosts. Settings ends with Amsel's line, "Black feathers. Golden song." (Caption, ink), the licence line ("© 2026 hazymorning", Meta, secondary) a second line's step under it and, 48 under its baseline, the README's picture
(`SettingsBird.kt`): the icon's blackbird behind three crowns before a 72 dp sun; at night a quiet moon a step above Surface 2,
centred and cut by the dock's edge it stands on, so the sun rises over that edge as the page reaches
its end. Each time six tenths of it come over the dock's edge, the sun grows from nothing into place (650 ms) and then the bird rises from behind the crowns to its perch (900 ms), both settling; once it is under the dock again it waits to rise anew, and with reduced motion it simply stands there. A picture only: not spoken, no touch, its size the same at any font scale; where the
settings fit a screen it stands at the screen's foot, and it never adds a scroll. Welcome is one
Display sentence and Find my server; elsewhere the blackbird lives on the icon and the splash.

## Motion and feel

Durations: 120 ms press, 250 ms state, 350 ms navigation, 450 ms Now Playing, 700 ms colour; two
easings, neither past its target: one that settles for what moves, a gentler one for opacity and
colour. A finger hands its speed to a critically damped spring.

- **Opening Now Playing**: the layer rises in 300 ms (240 to close) as one piece, its cover inside;
  no cover flies on its own. The layer follows the finger both ways; Back previews closing.
- Two kinds of motion and no others: content that changes cross-fades, and only what a finger
  moves moves in space, damped and never past where it stops. Now Playing opens as one drawer.
- **Track change**: the cover crossfades in 300 ms, the page's hue in 700, the words cross-fade.
- A page that opens or closes cross-fades; another tab is simply there; only the back gesture moves
  a page, with the finger. Lists appear at once; skeletons are still.
- Nothing moves that the person did not move, but for one line and one picture: the picture at
  Settings' foot rises as it comes into view (above), and what plays, on the dock, glides
  to its end and back while it is longer than its room, resting 2.5 s at each end, its hidden end
  faded. It rests while Now Playing lies over the dock. The ring is still.
- A sideways swipe (the dock's row, Now Playing's artwork, the library's kinds) repeats a visible
  control: what it carries lags behind the finger and fades, past 64 dp it acts, and what comes
  fades in where it stands; the library's kinds go round. A message, an offer and a tip can be
  swiped away to either side.
- A pull up from the dock opens Now Playing after an eighth of the way or a slow flick; closing it
  takes a third, or a throw.
- Haptic ticks on play and pause, a chip, the start of a scrub and a swipe that acts.
- **Reduced motion** (animations off): crossfades only; Now Playing fades rather than rises; a long
  title stays at its start.

## Words

| Say | Never say |
| --- | --- |
| speaker | player, device, output |
| This phone | local player, Sendspin |
| Play on | transfer, cast |
| Up next | queue |
| music service | provider |

## Acceptance

A screen is done when its light, dark and 200% screenshots hold every value above, TalkBack labels
and 48 dp targets are in place, and nothing from the brief's "What to avoid" appears; and when
each of these has been looked at, not assumed, on every screen a change touches, also in German,
at 360 dp wide (`./gradlew :app:recordRoborazziDebug -Plook=de-w360dp` records every shot that way
into `app/build/look`) and with the longest name in the house:

1. **What is chosen is in view.** A chip, a tab or a row that says where one is stays on screen
   however one got there: a tap, a swipe, a link.
2. **One glyph, one meaning.** A button keeps its glyph in every state; a glyph that is an action
   in one place never stands for something else in another.
3. **Edges agree.** A filled shape's edge stands under the edge of the filled shape above it (Now
   Playing's grounds under its artwork), a glyph's ink on the words' keyline; what stands in a
   corner is exactly as far from one edge as from the other; what faces each other across a row
   weighs alike.
4. **A glyph beside words is as large as the words.**
5. **A name is cut last.** Where a row is short, what matters least gives way first.
6. **The system's bars are counted once.** Room under a control is measured to the screen's
   foot, the gesture handle inside it.
