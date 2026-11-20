# Amsel

Native Android player for Music Assistant: find something in your library and play it, on the
phone or on any speaker. Simple, light, beautiful; usable by anyone at home without explanation.
Plan: `docs/PLAN.md`. Design: `docs/DESIGN.md`. Home: `docs/notes/home-sections.md`.

## Writing style (owner's rules, strict)

- No AI slop. Comments only where the code cannot say it: a non-obvious why, a constraint, a
  workaround. Never restate the code, never narrate, no banner or decorative comments. KDoc only
  where a public API would be misread without it.
- No AI or assistant branding anywhere: code, comments, commit messages, pull requests, release
  notes, docs. No `Co-Authored-By`, session links or "generated with" lines.
- If a name for the assistant or its tooling is unavoidable, use German bird words:
  **Kiebitz** (the assistant), **Nest** (its working branch or session), **Schwarm** (several
  agents at once), **Gezwitscher** (drafts and working notes).
- Commit messages: short imperative subject; a body only when it adds information.
- Docs stay short and factual. The README is the owner's: the header (`docs/design/readme`, drawn
  by `scripts/design/readme-header.py`), what Amsel is, four screens in GitHub's light or dark
  theme (laid out by `scripts/design/readme-screens.py`), the ideas behind it, and small at its foot
  the blackbird's line and the owner's note that Amsel is developed with the help of AI tools.
- Every release has a short name of its own with some character, from a bird's life, the heading of
  `release/NOTES.md`, a calendar version, and is titled "Nesting luck • 2026.11.0"; its notes have
  100 to 150 words under New, Better and Fixed (`docs/RELEASING.md`).

## How decisions are made

Decide without asking the owner, using these questions in order, and add one line to
`docs/DECISIONS.md` (what, and which question settled it):
1. What would a first-time user expect? 2. Fewest elements, steps and rules? 3. Calmer and better
looking? 4. Faster and lighter? Evidence (household tests, owner feedback) beats reasoning, and the
owner's explicit wishes override the plan.

## How to work here

- The owner's requests come first. Finish each one and make it visible on `main` before anything
  else, then say plainly what was done and what is still open.
- One working branch per task, from `main`, ending in one pull request squash-merged into `main`
  once its CI is green; then delete the branch. No other branch is left behind (`signing` holds the release key and
  stays).
- The repo stays clean: no files for an assistant or its tools (`CLAUDE.md`, `.claude/`, hooks,
  prompts, task or work-queue files), no stray branches, nothing left half done.
- Spend effort where a user would notice: a bug, a crash, lost data, a security hole, or what the
  owner asked for. No open-ended review rounds: at most one, on what the task touched. Wording,
  renames and tidying only alongside such a change.
- Before work that runs several agents or many hours, say what it will cost and wait for the
  owner's go. Stop when asked.
- A clear request is done, not asked about. Replies: the answer or link first, in the first line;
  then at most a few short lines; no recap of the work.

## Rules

1. The server does the work: no client-side recommendations, enrichment or scrobbling.
2. No parity chasing: admin stays in the Music Assistant web UI ("Open web UI").
3. A feature ships only if it is used weekly, needs no new permission and works untold;
   everything else goes to `docs/PARKING-LOT.md`.
4. Settings fit one screen.
5. Permissions: INTERNET, ACCESS_NETWORK_STATE, ACCESS_LOCAL_NETWORK, FOREGROUND_SERVICE,
   FOREGROUND_SERVICE_MEDIA_PLAYBACK, WAKE_LOCK; and, as the owner asked, FOREGROUND_SERVICE_DATA_SYNC
   and POST_NOTIFICATIONS for downloads, REQUEST_INSTALL_PACKAGES for updates. Nothing else.
6. Budgets fail the build: APK <= 15 MB, permission and dependency allowlists
   (`:app:checkBudgets`).
7. One corner rule for every cover, artists included (continuous corners by size, `Shapes.cover`):
   no artwork is ever a circle (`CoverShapeTest`).

Words in the UI: "speaker" not "player", "Play on" not "transfer", "Up next" not "queue",
"music service" not "provider". German and English strings stay in step.

## Code

| Module | Holds |
| --- | --- |
| `:api` | The Music Assistant WebSocket client, pure Kotlin |
| `:data` | Session, token vault, discovery, Room library index, speakers, Home |
| `:playback` | The Media3 session, speakers as players, This phone (Sendspin), Android Auto |
| `:app` | Compose screens and ViewModels, navigation, theme, `AppGraph` |
| `:sendspin` | Vendored unchanged (`sendspin/VENDORED.md`); never edit, re-vendor |

- One screen is one file plus one ViewModel; files under ~300 lines; no `!!`.
- Immutable UI state; lazy lists with stable keys; no work in composition.
- Every tap answers within 100 ms (optimistic state); errors are plain sentences with Retry.
- New dependency or permission only with a reason in the pull request.
- Check `docs/API-NOTES.md` before using a server command and record new ones there.

## Commands

```
./gradlew :app:assembleDebug
./gradlew test testDebugUnitTest :sendspin:testAndroidHostTest
./gradlew recordRoborazziDebug / verifyRoborazziDebug
./gradlew ktlintCheck
./gradlew :app:checkBudgets
scripts/test-server/start.sh 2.11.0b7    # local Music Assistant in Docker
scripts/check.sh                         # all CI runs, live servers too
```

Done means: unit tests and the kept screenshots pass, changed screens are looked at in light, dark
and 200% and pass `scripts/design/measure.py`, TalkBack labels and 48 dp targets are in place, no
new warnings. A fix runs only the tests for what it touched; every pull request first passes
`scripts/check.sh`, all CI runs.
