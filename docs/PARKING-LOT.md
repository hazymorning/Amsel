# Parking lot

Ideas that failed the feature test in `docs/PLAN.md` ("Six rules", rule 3), kept here so they
stop coming back. Each line: the idea, the date, and which part of the test it failed.
Post-beta candidates from the plan (lyrics) live here until they pass the test.

| Idea | Date | Failed because |
| --- | --- | --- |
| Lyrics | 2026-11-01 | Post-beta; must pass the feature test first |
| WebRTC remote access | 2026-11-01 | Out of scope for v1; VPN or HTTPS reverse proxy instead |
| Playback speed for books and episodes | 2026-11-02 | Not used weekly by most; a speed set in the web UI is followed |
| A series view for books | 2026-11-02 | Not used weekly; the server's autoplay already goes on to the next book of a series |
| Podcast transcripts | 2026-11-02 | Not used weekly; 2.11 only |
| Download from Home's and the Library's tiles | 2026-11-08 | Their items come from the index, which does not know where an item is from; a book's page and a podcast's episodes offer it |
| Signing in to Audiobookshelf with OpenID | 2026-11-08 | Not needed for the household; username and password, as Lissen started |
| Downloading a podcast's new episodes by themselves | 2026-11-08 | Not asked for; one tap per episode works untold |
| Streaming a book or an episode straight from Audiobookshelf on This phone, without a download (seeks at once, as in Lissen and Absorb) | 2026-11-10 | A second playback path beside Music Assistant's, which then knows nothing of what the phone plays (Up next, Play on, its own progress); needs trying on a phone first. A download already plays from the phone this way. To build: `LocalPlayback` with an HTTP source on the app's OkHttp client and Audiobookshelf's token, a manifest planned without downloading (`DownloadJob.plan`), progress as for downloads |
| Pinning an endless mix to Home, a mix of a genre, a page that shows a mix's songs before it plays | 2026-11-12 | Not asked for; one tap plays, and the web UI has the page |
| The DJ for a guest, and for a book, an episode or a station | 2026-11-12 | A guest may not read the hosts' names; the plugin talks between songs and nothing else was tried |
| See all, paging or a choice of source for similar songs and artists | 2026-11-12 | The server sends 25 at most; no second screen for them |
| A tap on the blackbird at the foot of Settings that makes it cock its head | 2026-11-12 | Not asked for; a picture needs no answer, and it would be a stop for TalkBack that says nothing |
| Home Assistant actions on a widget's buttons (a webhook or a service call) | 2026-11-19 | Needs setting up in Home Assistant for each button, and its app already has action and media widgets that stand beside Amsel's |
