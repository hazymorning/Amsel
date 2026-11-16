# Home: sentence sections (owner's choice, 2026-11-01)

Home is the reason the owner started Amsel. It shows **as many sections as the person adds**. Each
section is one sentence the user sets with two taps: **"Show [source] as [layout]"**. Every user gets their own
definition of a useful start screen, without settings screens or a page of toggles.

## Sources (what)

| Source | Options (the sentence's first chip) | Server |
| --- | --- | --- |
| Pinned | what the user pinned from an item's actions: albums, playlists, artists, stations, books and podcasts | stored on the phone (ordered list of uris) |
| Playlists | Smart playlists (the Smart Playlists plugin's, or Music Assistant's built-in set when there are none) · Your playlists · Favourite playlists · Recently played playlists | `music/playlists/library_items` + provider/owner filter |
| Albums | New in your library · Recently played · Favourite albums · Random albums | `music/albums/library_items` with sort/favourite |
| Artists | Artists you play most · Favourite artists · Recently played artists | `music/artists/library_items` (sort `play_count`, `last_played`, favourite) |
| Radio | Favourite stations · All stations · Recently played stations | `music/radios/library_items` |
| Search | Search: Search's own field at rest between the margins, with no heading; a tap opens Search, emptied, the keyboard up. In edit mode it is only a section to arrange. Never empty, never loading | none: the app's own Search |
| Recently played | Everything you played lately (albums, playlists, artists, stations) · Recently played here: what last played on the speaker in use, titled "Recently played · Kitchen", the whole history while nothing has played there · Continue listening (books and episodes in progress) | `music/recently_played_items` (with `queue_id` for here); `music/in_progress_items`, each item looked up with `music/item_by_uri` for where the person stands |
| Audiobooks | New audiobooks | library index |
| Podcasts | New episodes (the newest of each podcast; 2.11) · Your podcasts | `music/recommendations/items` row `latest_episodes`; library index |

Options for audiobooks and podcasts stay out of What to show until the library holds some.

## Layouts (how): five, one visual language

| Layout | Looks like | Items |
| --- | --- | --- |
| Grid | cards on the two-column grid: a small cover and its title on a tone (shortcuts) | up to 8, whole rows from 4 (every pin) |
| Small covers | a sideways shelf of small tiles, three whole to a screen and the edge of the next | up to 12 |
| Row | a sideways shelf of tiles: cover, title and secondary line, two whole to a screen and the edge of the next | up to 12 |
| Big | the same shelf with two covers between the margins | up to 8 |
| List | cover rows: thumbnail, title, secondary line | up to 5 |

Covers are square with the radius their size gives them (DESIGN.md, "Shape"). Any layout offers
See all when its option has more than it shows.

## Titles

The title is the sentence's source in plain words ("Smart playlists", "Radio", "Artists you play
most"), never "Section 1". The layout never appears in the title.

## Editing

- **Long-press any section title** to enter edit mode (a tip says so once; with nothing on Home,
  its empty state offers Edit Home).
- In edit mode each section shows its title (a tap renames it), its sentence with two chips,
  **Show [source ▾] as [layout ▾]** (on two lines, so a longer choice never reflows it; each chip says
  its choice as the sentence needs it, "Show your pins as a grid", "Zeigt Angeheftetes als große
  Cover", where headings and the sheets keep their names), and ⋯ with Hide title (Show title, once
  hidden: Home then keeps only the room a heading takes, and edit mode shows the title faint) and
  Remove. Every section shows its title until hidden, but Search, a field, which shows it once
  asked to. A held press on the title or the sentence picks the section up to move it; TalkBack moves it
  through its actions. A chip opens a sheet of choices, as Library's sort does. **Add section** stands
  at the end, however many there are. **Done** (in the slim bar) and Back leave edit mode. Every
  change shows immediately in place.
- **Pin to Home / Unpin from Home** in the actions of an album, playlist, artist, station, book or podcast. Pinning when no Pinned section exists
  adds one at the top (Grid).
- No settings screen, no separate editor page: Home edits itself.

## Defaults (a new user)

1. Continue listening · Big (hidden while empty): upright cards in their cover's hue, the title
   (or the title and its author), what is left and how far; a tap resumes, a long press opens its
   actions. A Home stored before it
   existed gets it once at the top when the library first holds audiobooks or podcasts.
2. Pinned · Grid (hidden while empty; the first pin shows it)
3. Recently played · Row ("Your albums" for a brand-new library, as today)
4. Smart playlists · Row
5. New in your library · Row

## Behaviour

- Sections refresh by themselves after a play, a sync or a reconnect; a pull down at Home's top opens
  Settings, not a refresh.
- Each section paints from its last snapshot on disk, then refreshes (no skeletons after the first run).
- A section whose source returns nothing is hidden outside edit mode, and shows one quiet line in
  edit mode ("Nothing here yet").
- When every section is hidden, Home says "Add music in Music Assistant" only if the library is
  empty; otherwise "Nothing on Home yet" with Edit Home, so the person can always get back.
- Stored per server in DataStore as a small JSON list: `{id, option, layout}` plus the
  pinned uris.
