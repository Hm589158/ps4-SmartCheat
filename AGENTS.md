# Notes for coding agents

* Gradle root is `android/`. `:app` is only included when an Android SDK is found (`ANDROID_HOME`,
  `ANDROID_SDK_ROOT` or `local.properties`); `-PskipAndroid=true` excludes it explicitly.
* Run `./gradlew :core:test` before every commit. All protocol/scanner/editor logic belongs in `:core`
  (no Android imports) so it stays JVM-testable against `:mockserver`.
* Never add protocol commands or packet layouts that are not verified in upstream source; document them in
  `docs/PROTOCOL.md` with the source file.
* Never report hardware results that were not observed. Mock results are labelled as such.
* Safety invariants: read-only by default; every write confirmed + verified by fresh read; no automatic
  re-application of cheats; local-network-only hosts by default; no kernel memory editing; no telemetry.
* `google()` repositories are content-filtered in `settings.gradle.kts` so JVM modules resolve without Google Maven.
