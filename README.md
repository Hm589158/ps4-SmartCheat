# PS4 SmartCheat

An Android memory scanner and cheat tool for your own jailbroken PlayStation 4 (GoldHEN + **ps4debug-NG**),
for offline games and homebrew you control. ps4debug-NG is the primary backend (automatic CMD_PROC_AUTH unlocks
server-side and Turbo scanning); classic ps4debug works in compatibility mode with Android-side scanning.

> **Status:** the owner reported the core flow working on a real PS4 (FW 11.00, GoldHEN, ps4debug-NG 1.3.3:
> connect, authentication, process list, Turbo Scan, refines, a write in a game — see
> [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)). Features added since 0.8 (address list, pointer scan, pattern
> search, byte patches, undo/redo, session resume, GoldHEN import, diagnostics) are tested end to end against the
> mock ps4debug-NG server only. Online/multiplayer cheating, anti-cheat evasion, DRM bypass and kernel editing are
> out of scope.

## Features
* Connect to ps4debug-NG (default port 744; classic ps4debug also works), see branding, firmware and the scan backends unlocked by authentication
* List processes, inspect title ID and the memory map of the selected game
* Three scan backends chosen automatically: **Turbo Scan** and **server-side scan** on the PS4 (ps4debug-NG),
  falling back to an optimized **chunked scan on Android** — with live elapsed time, MiB/s, network bytes and the
  backend used ([measurements](docs/PERFORMANCE.md))
* Scan Int8/16/32/64, Float, Double: exact, bigger/smaller than, value between, unknown initial, increased,
  decreased, changed, unchanged, increased/decreased by; progressive refinement, cancellation, paginated results
  with live values
* Byte pattern (AOB, `??`/nibble wildcards) and text (ASCII/UTF-8, UTF-16LE) search
* Address list per game: named addresses with live values, module-relative entries that follow the game across
  restarts, freeze and edit from the list
* Pointer scan for values whose address changes every run: pointer map built on the phone, backward search to
  static game data, filter after a restart, pointer-chain entries in the address list
* Memory editor: read-only by default, confirmation for every write, fresh read-back verification, restore of
  original bytes, confirmed undo/redo of the session's writes, client-side freeze while connected
* Scan sessions are saved after each step and can be resumed after an app restart (same game instance only)
* Memory viewer (hex + ASCII, live refresh with changed-byte highlighting, confirmed byte patches), live result
  values, CSV export of results
* Cheat library: per-game profiles (title ID + version + process), module-relative addresses when possible,
  validation before re-applying, JSON import/export, GoldHEN cheat-file export (verified fields only) and import
  (mods are enabled only when memory matches the file's "off" bytes)
* Connection diagnostics (latency test, traffic, reconnects, recent errors), in-app guide, tablet layout

## Install the APK
1. Open the repository's **Releases** page and download the newest `PS4-SmartCheat-*.apk`.
   Files named `…-TEST-debug.apk` are debug-signed test builds (pre-release) that install as
   `com.ps4smartcheat.app.debug`. Every CI run also stores a debug APK as a workflow artifact for 7 days
   (Actions → CI → run → Artifacts).
2. On the phone allow "Install unknown apps" for your browser/file manager and open the APK.
3. Check `SHA256SUMS.txt` from the release if you want to verify the download.

## Use
1. On the PS4: GoldHEN, then load the ps4debug-NG payload; start the game.
2. In the app: Connect → enter the PS4 IP → Processes → select `eboot.bin` → Scanner.
3. First scan the value you see, change it in game, next scan, repeat. Tap a result to open the editor.
4. Settings → enable memory editing (resets on every app start) → Write → confirm.

ps4debug has no authentication. Only use it on a trusted network; the app refuses non-local addresses by default.

## Build
```
cd android
./gradlew :core:test            # JVM tests, no Android SDK needed
./gradlew :app:assembleDebug     # needs an Android SDK (ANDROID_HOME or local.properties)
./gradlew :mockserver:run --args="7440"   # simulator for testing the app without a PS4
```
JDK 17+. Gradle 8.14.3 (wrapper), AGP 8.11.1, Kotlin 2.2.21, Compose BOM 2025.08.00.

## Releases and signing
`.github/workflows/release.yml` runs on `v*` tags or manually. With the secrets
`SMARTCHEAT_KEYSTORE_BASE64`, `SMARTCHEAT_KEYSTORE_PASSWORD`, `SMARTCHEAT_KEY_ALIAS`, `SMARTCHEAT_KEY_PASSWORD`
it publishes a signed release APK; without them it publishes a debug-signed **TEST** pre-release.
It refuses to overwrite an existing release. No signing material is stored in the repository.
How to create the key and the secrets: [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md).

Create a keystore once: `keytool -genkeypair -v -keystore release.jks -alias smartcheat -keyalg RSA -keysize 4096 -validity 10000`,
then `base64 -w0 release.jks` into the secret.

## Docs
[Changelog](CHANGELOG.md) · [Architecture](docs/ARCHITECTURE.md) · [Protocol](docs/PROTOCOL.md) · [Testing](docs/TESTING.md) ·
[Performance](docs/PERFORMANCE.md) · [Compatibility](docs/COMPATIBILITY.md) · [Roadmap](docs/ROADMAP.md)
