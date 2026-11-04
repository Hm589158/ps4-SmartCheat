# Architecture

```
android/
  core/        Pure Kotlin/JVM library: protocol client, scanner, editor, cheat library, freeze
  mockserver/  Independent mock ps4debug server (tests + standalone simulator)
  app/         Android app: Jetpack Compose + Material 3, MVVM, StateFlow
```

`core` has no Android dependency, so every algorithm and the real networking client are tested on a
plain JVM (locally and in CI). `app` is a thin MVVM layer over `core`.

## core packages

| Package | Responsibility |
|---|---|
| `backend` | `DebugBackend` interface (`connect`, `disconnect`, `getProcesses`, `getProcessInfo`, `getMemoryRegions`, `readMemory`, `writeMemory`, `state`) |
| `protocol` | `Ps4DebugClient` (standard ps4debug), constants, local-network policy |
| `scan` | `ValueCodec`, `Matcher`, `ScanEngine` (stateless algorithms), `ScanController` (per-process sessions, cancellation, pagination), snapshots |
| `edit` | `MemoryEditor` (policy checks, original journal, verified write, restore), `ProcessSafety` |
| `cheat` | Models, `CheatJson` (format + validation), `CheatRepository` (atomic JSON file), `ProfileValidator`, `GoldHenExporter` |
| `live` | `FreezeManager` (client-side periodic writes while connected) |

## Key decisions

* **Backend abstraction first, ps4debug adapter only.** ps4debug-NG is wire-compatible for the commands used,
  so no separate NG adapter exists; NG-only commands are not used.
* **Requests are serialised** with a mutex; one TCP connection. After a server-side error the session is
  reopened lazily (the server closes it). Timeouts and I/O errors are fatal and reported (`ConnectionState.Failed`).
* **Scanner memory bounds:** results are stored as `LongArray` + packed value bytes (`8 + size` bytes each) with a
  hard cap (default 1,000,000). Reads are chunked (default 1 MiB) and never cross a region end. Unknown-value
  snapshots are capped (512 MiB) and spill to a cache file above 64 MiB.
* **Atomic steps:** a cancelled/failed scan step keeps the previous results.
* **Persistence:** cheats are one JSON file written atomically (temp + rename) in the same format used for export.
  This deviates from the "Room" preference on purpose: the data is a small document tree, the file is the
  import/export format, and it is testable on the JVM. Settings use SharedPreferences.
* **Safety:** read-only by default (editing switch resets on every app start), system-process write guard,
  non-writable region guard, confirmation dialog for every write, read-back verification, no automatic
  re-application after reconnect, no telemetry, local-network-only hosts.
* **DI:** a manual `AppContainer` (no DI framework).
