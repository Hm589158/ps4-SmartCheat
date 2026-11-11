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
| `scan` | `ValueCodec`, `Matcher`, `ScanEngine` (Android chunked scan + link measurement), `ServerScanner` (ps4debug-NG iterative + Turbo), `ScanController` (backend selection/fallback, per-process sessions, cancellation, pagination, stats), snapshots |
| `edit` | `MemoryEditor` (policy checks, original journal, verified write, restore), `ProcessSafety` |
| `cheat` | Models, `CheatGroups`, `GoldHenSafety`, `CheatJson` (format + validation), `CheatRepository` (atomic JSON file), `ProfileValidator`, `GoldHenExporter` |
| `live` | `FreezeManager` (client-side periodic writes while connected) |
| `tools` | `MemoryViewer`/`HexDump` (mapped-only display reads, change diff), `ScanResultsCsv`, `BytePattern`/`PatternSearch` (AOB and text search) |
| `watch` | Per-game address list: `WatchRepository` (atomic JSON keyed by title ID), `WatchResolver` (module-relative / same session / earlier session / unmapped), `WatchReader` (batched reads within one region) |
| `pointer` | `PointerScanner` (pointer map as two sorted primitive arrays, bounded backward search to static module data, restart filter), `PointerResolver` (follows a chain reading mapped memory only), `PointerChain`, `PointerStability`/`PointerCandidateStore` (candidates ranked by restarts survived) |
| `process` | `GameDetector`, `ProcessWatcher` (exit/restart detection), `listProcessDetails` (names first, details with games first, time-limited) |
| `asm` | `X86` (iced-x86 decode/format, instruction before a trap RIP, signatures with wildcarded constants), `CodeTools` (ps4debug-NG assembler and reference search), `CodePatchPlanner`/`CodePatch` (whole-instruction ON/OFF patches) |
| `debug` | `DebugSession` (attach with interrupt listener, watchpoints, continue, detach, SIGCONT), `InterruptListener`/`InterruptChannel` (port-755 connection from the PS4), `AccessMonitor` (what writes / accesses) |
| `validate` | `SessionValidator` (after restart: address list, pointers, value cheats, code patches with signature search) |
| `diag` | `LinkDiagnostics` (latency), `ErrorAdvisor` (cause + next steps), `ProcessActivity` (running vs stopped) |
| `tabs` | `TabManager` (open tool tabs: order, active tab, use history for Back, single- vs multi-instance tools), `Tool`, `ToolArgs`, `TabSpec` |

## App window: tabs

The app is organised like a browser. `AppContainer.tabs` (a `core.tabs.TabManager`) is the single place that opens,
focuses, reorders, duplicates and closes tool tabs; the tab list is saved in SharedPreferences (`TabStore`) and
restored at the next start.

* Every tool screen is opened as a tab with `ToolArgs` (address, value type, size, write/access). Links between tools
  ("view memory", "disassemble", "what writes?") open a tab next to the current one; the same tool at the same address
  is focused instead of opened twice.
* Tools that show app-wide state (Connection, Processes, Scanner, Addresses, Cheats, Pattern search, Settings,
  Diagnostics, Guide) are single-instance. Editor, Memory viewer, Code, Code cave, Watch, Debugger and Pointer scan
  can be open several times, each tab with its own work.
* Each tab has its own `ViewModelStore` (`TabViewModelStores`, kept for the process lifetime and cleared when the tab
  closes, so e.g. a watch session is released) and its own `SaveableStateHolder` entry, so switching tabs never loses
  a tool's state. Only the active tab is composed.
* All tabs share the container: one PS4 connection, one selected process, one scan session, one cheat library and the
  same safety checks. Nothing about reading, writing or confirmation changed with the tabs.
* Back returns to the tab used before; the new-tab page lists every tool, the overview (tab counter) shows all tabs
  with reorder and close.

## Key decisions

* **Backend abstraction first, ps4debug adapter only.** ps4debug-NG is wire-compatible for the base commands, so
  no separate NG adapter exists. NG-only commands (scans, assembler, reference search, debugger) run through
  `ServerScanTransport.exclusive` and are only offered when the payload identifies as ps4debug-NG.
* **Instruction text on the phone.** ps4debug-NG's disassembler returns metadata only, so x86-64 is decoded with
  iced-x86 (MIT, pure Java); assembling stays on the PS4 (Keystone) so encodings match the target address.
* **Three scan backends** (Turbo → server-side → Android) selected per step; see docs/PERFORMANCE.md.
  `ServerScanTransport.exclusive` gives strategies framed access to the single connection; Turbo CANCEL uses a
  short side connection.
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
