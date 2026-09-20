# PocketSSH

An SSH client for Android. Keep a list of servers, open a real terminal against one of them,
browse its filesystem over SFTP, and lock the whole thing behind a PIN when you put the phone
down.

Single-module Kotlin app, Jetpack Compose throughout, Material 3. Minimum Android 8.0 (API 26).

## Terminal

The terminal is a from-scratch ANSI/VT100 emulator rather than a text view that prints whatever
the server sends. It handles SGR attributes, cursor movement, the erase sequences and the
alternate screen, which is what full-screen programs need — `vim`, `htop`, `less` and `mc` all
render properly. Scrollback holds 2000 lines.

There are two input modes. Line mode gives you a text field and sends a command when you hit
send, which suits the on-screen keyboard. Raw mode forwards every keystroke immediately, which
is what you want inside an editor. Esc, Tab, Ctrl+C and Ctrl+D have their own keys.

A subset of the kitty graphics protocol is supported, so images sent inline show up in the
buffer. Only direct and base64 transmission — file-based transmission names paths on the remote
host, which a client-side emulator has no way to read, and kitty itself declines it over SSH for
the same reason.

The emulator has no Android dependencies at all, so it is covered by plain JVM unit tests.

## Files

SFTP browsing runs over the same connection logic as the shell: list a directory, create
folders, delete entries, and upload or download through the system file picker.

## Servers and secrets

A server profile is a name, host, port, username, and either a password or a private key with an
optional passphrase. Profiles live in encrypted SharedPreferences — the whole JSON blob is sealed
with AES-GCM under a key that never leaves the Android Keystore. You can decline to save the
secret, in which case only the connection details are stored.

Host keys use trust on first use. The first time you reach a server, PocketSSH shows the key's
SHA-256 fingerprint and asks whether to trust it. Accepted fingerprints are remembered and
checked on every later connection; a mismatch fails the connection. Settings lists everything
you have trusted so you can forget individual entries.

Backups are a single file you can put anywhere. The profile list is encrypted with a passphrase
you choose, derived with PBKDF2-HMAC-SHA256 at 210,000 iterations and sealed with AES-256-GCM.
The passphrase is not stored and cannot be recovered. Imported profiles are given fresh ids, so
importing never overwrites a server you already have.

## App lock

Setting a PIN makes PocketSSH lock every time it goes to the background. Biometric unlock is
offered when the device has it enrolled. The PIN itself is stored as a salted SHA-256 hash inside
the same Keystore-encrypted preferences.

The lock screen is customisable under Settings → App lock → Lock screen style, with a live
preview. You can change the accent colour, pick one of five backdrops (aurora, waves, grid, rain
or plain), switch the key shape and fill style, resize the keys, set your own greeting, and turn
the clock, hint text, haptics and unlock-on-last-digit off or on.

Each key can also carry its own label and icon, including the confirm key. This is cosmetic
only: a key still types the digit it was always going to type, so restyling the pad cannot change
what your PIN is or lock you out.

## Updates and rollback

PocketSSH checks its own GitHub releases, shows what changed, downloads the APK and hands it to
the system installer. Android still asks for confirmation and for permission to install from this
source — silent installation belongs to system installers alone.

Rolling back needs a second package, and the reason is structural. Android will not install an
older version over a newer one, so a downgrade has to uninstall first — and an app cannot install
anything after uninstalling itself. `PocketSSH Recovery` exists for that gap: a headless service
with no activity and no launcher entry, which PocketSSH calls on its way out. It copies the saved
APK, asks for the uninstall, and installs the older build once that finishes.

Android still confirms the uninstall and the install. Only a system installer can skip those, and
this is not one. What the helper removes is having to find the APK in a file manager afterwards.

The service is exported behind a `signature` permission, so only a build signed with the same key
can start it. Without the helper installed, PocketSSH falls back to naming the file and the
folder and leaving the second step to you — because once it is uninstalled there is no button of
its own left to press.

The automatic backups are what make that survivable. They are written before every update and
after every change, into that same folder — outside app storage, because the whole point is
surviving an uninstall. They are encrypted like any other backup, except the passphrase is
derived from `ANDROID_ID` rather than chosen. On Android 8.0 and up that value is scoped to the
app signing key and "does not change on package uninstall or reinstall, as long as the signing
key is the same", so a reinstalled PocketSSH can open its own backup with no passphrase to
remember.

The trade-offs, since they are not obvious:

- An automatic backup is readable only on the device that wrote it, by a build signed with the
  same key. A factory reset or a new signing keystore makes older ones unreadable.
- Moving to a different phone needs the manual passphrase backup instead.
- Uninstalling also drops the permission on the backup folder, so after a reinstall you point at
  the folder once and everything is restored in that same step.

## Languages

English, Russian, Ukrainian, Spanish and German, switchable in Settings independently of the
system language.

## Building

Needs JDK 17. Everything else comes down with Gradle. Two modules: `:app` and `:recovery`, the
downgrade helper.

```
./gradlew assembleDebug        # APK at app/build/outputs/apk/debug/
./gradlew :recovery:assembleRelease   # the helper, at recovery/build/outputs/apk/release/
./gradlew installDebug         # build and push to a connected device
./gradlew testDebugUnitTest    # JVM tests
./gradlew connectedDebugAndroidTest   # instrumented tests, needs a device or emulator
```

Release builds are signed with a keystore referenced from `local.properties`, which is gitignored
along with the keystore itself:

```
releaseStoreFile=pocketssh-release.jks
releaseStorePassword=...
releaseKeyAlias=pocketssh
releaseKeyPassword=...
```

Without those four lines the project still builds — the release type falls back to the debug key
and warns you about it. Such a build is fine for local testing and must not be published.

CI runs unit tests, lint and a debug assemble on every push and pull request, plus instrumented
tests on an API 30 emulator.

## Layout

```
app/src/main/java/com/pocketssh/app/
  data/        profiles, Keystore-backed storage, backup encryption, lock screen settings
  ssh/         connect/auth/host-key verification, shell session, SFTP
  terminal/    the ANSI emulator and its types — no Android imports
  ui/          Compose screens
  ui/lock/     lock screen backdrops and keypad
```

## Dependencies

[sshj](https://github.com/hierynomus/sshj) for the SSH transport, with BouncyCastle as its
crypto provider. AndroidX Compose, Lifecycle, Fragment and Biometric. Nothing else.

## Status

A personal project, not a product. It works, and it is used, but there is no release channel and
no support. Expect rough edges.

## Licence

CC0 1.0 Universal — public domain. Do what you like with it. See [LICENSE](LICENSE).
