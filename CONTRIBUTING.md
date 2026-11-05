# Contributing

Thanks for helping! The most valuable contribution right now is a **hardware test report**
([open one](https://github.com/Hm589158/ps4-SmartCheat/issues/new?template=hardware_report.yml)): which features
worked on your console, firmware and debugger version, scan times and the diagnostics latency.

## Development

```bash
cd android
./gradlew :core:test                      # required before every commit (no Android SDK needed)
./gradlew :app:testDebugUnitTest          # end-to-end UI journeys (Android SDK)
./gradlew :mockserver:run --args="7440"   # simulated PS4 for manual testing
```

* All protocol, scanning and editing logic lives in `android/core` (pure Kotlin, no Android imports) and is tested
  against the independent mock server in `android/mockserver` over real TCP.
* New protocol commands or packet layouts must be verified in upstream source and documented in
  [docs/PROTOCOL.md](docs/PROTOCOL.md) with the source file. Never guess a layout.
* Keep the safety invariants: read-only by default, every write confirmed and verified by a fresh read, no automatic
  re-application of writes, local-network-only hosts, no kernel memory editing, no telemetry.
* Hardware results are only stated when actually observed; everything else is labelled mock-tested.
* Add a line to [CHANGELOG.md](CHANGELOG.md) for user-visible changes.

## Out of scope

Online/multiplayer cheating, anti-cheat evasion, DRM bypass, account or network-service attacks, save-file editing
and kernel memory editing. Pull requests in these areas are closed.
