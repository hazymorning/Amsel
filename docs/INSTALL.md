# Installing Amsel

Amsel is a player for your Music Assistant server: it plays your library on this phone or on
any speaker in the house. It needs Music Assistant 2.10 or newer.

## On your phone

1. Download `amsel-<version>.apk` from the newest release on
   [Releases](https://github.com/hazymorning/Amsel/releases).
2. Open it. Android asks once to allow installs from your browser or files app: allow it, then
   tap **Install**.
3. Open Amsel and tap **Find my server**. On the same Wi-Fi as Music Assistant it finds the
   server by itself; otherwise type its address (for example `192.168.1.20` or
   `music.example.com`). Outside your home, use an `https://` address.
4. Sign in with your Music Assistant account (or Home Assistant, when your server offers it).

Everyone in the house can sign in with their own Music Assistant user; a shared account works too.

## Updates with Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) installs and updates apps straight from
GitHub releases. In Obtainium, tap **Add App** and paste `https://github.com/hazymorning/amsel`.

## Android Auto

Android Auto only lists apps from the Play Store unless you allow others once:

1. Open Android Auto's settings on the phone (Settings → Connected devices → Android Auto).
2. Scroll to the bottom and tap **Version** ten times, then confirm to turn on developer settings.
3. Open the ⋮ menu → **Developer settings** and turn on **Unknown sources**.
4. Reconnect to the car. Amsel appears with your other media apps.

## Widgets and shortcuts

Amsel has a widget of its own: hold an empty spot on the home screen, choose Widgets, then Amsel. It
shows which speaker is in use and what plays there, with four buttons you choose as you place it;
hold the widget to change them later. Its buttons work while Amsel is closed.

Music widgets such as KWGT can choose Amsel as their player; their buttons work like Amsel's own,
also while Amsel is closed. Hold Amsel's icon for Search, Now playing and Speakers. A widget's
"open link" action reaches the same places: `amsel://search`, `amsel://now-playing` and
`amsel://speakers`.

## Updates in Amsel

Settings → Updates looks for a newer version on GitHub, and can do so each time Amsel starts
(off at first; never in the background). Install update downloads it in Amsel and opens
Android's installer on it; the first time, Android asks to let Amsel install apps. Every Amsel
release is signed with the same key, so Android refuses an update that was not.
