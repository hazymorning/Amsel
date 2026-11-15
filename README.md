# Amsel release signing key

`amsel-release.jks.enc` is the release keystore (PKCS12, alias `amsel`), encrypted with
AES-256-CBC and PBKDF2 (600,000 iterations). The repository secret AMSEL_SIGNING_PASSWORD
decrypts it; its store and key password is the SHA-256 hex of that secret. Created by the
first release run; every release signs with it. Never delete this branch: losing the key
means users must uninstall to get later updates. See docs/RELEASING.md on the main line.
