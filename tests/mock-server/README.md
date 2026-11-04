# Mock ps4debug server

Implementation: `android/mockserver/src/main/kotlin/com/ps4smartcheat/mockserver/` (Gradle module `:mockserver`,
kept inside the Gradle build so tests can depend on it).

* In tests: `MockPs4DebugServer()` binds an ephemeral loopback port; `DemoGame.install(server)` adds processes.
* Standalone: `cd android && ./gradlew :mockserver:run --args="7440 0.0.0.0"`.

It is a simulator. Nothing it returns comes from a PS4.
