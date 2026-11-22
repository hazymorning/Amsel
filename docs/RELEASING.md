# Releasing

1. Run `scripts/check.sh` before the pull request: CI's checks as CI runs them, the live tests
   against both Music Assistant servers in Docker too. CI then passes the first time; a failed run
   mails the owner.
2. Write `release/NOTES.md`: a heading with the release's name, short (two or three words), from a
   bird's life, its own and with some character ("Nesting luck"), then 100 to 150 words in plain
   language under the headings `## New`, `## Better` and `## Fixed` (only those with something in
   them), one line each: `- **Name:** what it does for the people using the app.`
3. Set `release/VERSION` to the new version, through the pull request, so it reaches `main`.
   Versions count from `v1.0`: each release the next, `v1.1`, `v1.2`, and a new first number only
   when the owner says. Until 1.0 they followed the calendar (`v2026.11.57`); the update check puts
   those before every version since. Every version is published as a regular release and marked
   the latest.
4. The Release workflow builds the signed APK (the pull request's CI tested that code) and publishes it
   here, https://github.com/hazymorning/Amsel, as "Nesting luck • 1.1", with the notes as
   written and the APK as its one file. A name an earlier release already has stops it, and fails
   the pull request's CI before that (`scripts/release/check-name.sh`). Actions → Release → Run
   workflow publishes the version in `release/VERSION` if it is not released yet.
5. Once the release is out, its `caches` job runs CI's checks on main once more, only to fill the
   Gradle cache that pull requests start from (they can read main's caches and no others): a pull
   request then downloads next to nothing and builds and tests again only what it changed. A
   failure there fails nothing.

Amsel's source is public here, under the GNU General Public License, version 3 (`LICENSE`); the
app's update check and Obtainium read the releases.

Cleanup (https://github.com/hazymorning/Amsel/actions/workflows/cleanup.yml, Run workflow) keeps
the newest release and deletes the older ones with their tags (`keep` 0 clears them all, for a
release published anew under its name), every tag no release keeps,
every branch but `main` and `signing` (a branch with an open pull request stays), the Actions
caches, and every workflow run but the newest of each workflow.

## The signing key

The owner sets one repository secret
(https://github.com/hazymorning/Amsel/settings/secrets/actions/new): `AMSEL_SIGNING_PASSWORD`, a
long password from a password manager.

The key itself never leaves GitHub and never passes through anyone's hands: the first release
run here generates it on the runner (PKCS12, RSA 4096, alias `amsel`; keytool accepts only ASCII,
so the keystore's own password is the SHA-256 hex of the secret), encrypts it with that password
(AES-256-CBC, PBKDF2 with 600,000 iterations) and pushes the encrypted file to the `signing`
branch. Every later release decrypts the same key from there, signs, and deletes the decrypted
copy; an existing `signing` branch refuses a second key, and once a release is out a run that
cannot read the key stops rather than make a new one. Releases come from `main` only, by hand too. The password is the one thing to keep
safe; never delete the `signing` branch. Losing either means users must uninstall to get later
updates, as 2026.11.31 asked once: the key of the private repository's releases could not move,
its password being lost.
