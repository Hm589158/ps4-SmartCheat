# Testing

## Layer 1 — pure unit tests (`android/core`)
`ValuesTest`: parsing ranges, signed/unsigned input, little-endian encoding, float rounding, all matcher modes,
alignment helpers. `CheatLibraryTest`: persistence across instances, JSON round trip, invalid files,
corrupt-file preservation, per-game isolation, module re-resolution after ASLR shift, GoldHEN export schema.

## Layer 2 — real client against the mock server (`android/mockserver`)
The mock is an independent implementation of the server side over real TCP, with fault injection
(stalls, drops, truncated maps, garbage status, ignored writes, missing FW extension, read delay).

* `Ps4DebugClientTest` – connect/version/firmware, missing extension resync, process list (incl. empty),
  process info, map parsing, unknown pid recovery, chunked reads, unmapped reads, writes, rejected writes,
  timeouts, dropped connections, truncated/malformed packets, non-ps4debug servers, refused connections,
  public-address blocking, disconnect/reconnect.
* `ScannerTest` – the 5000 → 4200 → 3900 currency scenario (expected sets computed by brute force over
  mock memory), all six types, region boundaries + chunk overlap, region filter, result cap, 48 MiB region
  streaming, cancellation (first/next scan), disconnect during scan, process termination, empty map,
  unmapped candidates, unknown-initial-value, snapshot limit, repeated scans, per-process sessions, pagination.
* `MemoryEditorTest` – write + fresh read-back + restore, read-only mode, system-process guard, non-writable
  guard, unmapped/straddling addresses, invalid input, silently ignored writes, rejected writes, drop during write.
* `ServerScanTest` – NG detection, classic/auth-failure fallback, keystream, currency scenario on Turbo with zero
  `CMD_PROC_READ`, **tier equivalence** (Turbo = server = Android results for Int8–Int64 × changed/unchanged/increased),
  resident-buffer overflow fallback, float/unaligned routing, capped local list with full PS4-side set, lost-session
  fallback after reconnect, Turbo cancel via side channel, cost-based server refine, traffic reduction.
* `AdaptiveRefineTest` – break-even gap maths and RTT/bandwidth measurement on a throttled mock link.
* `ToolsTest` – hex rows with change/unmapped flags, viewer never requests unmapped memory, CSV export format/escaping.
* `ScanBenchmark` (`./gradlew :core:benchmark`, not part of `test`) – see docs/PERFORMANCE.md.
* `FreezeManagerTest` – freeze/unfreeze, stop on process exit, disconnect, editing disabled.
* `WatchListTest` – per-game persistence, duplicate detection, corrupt-file backup, module-relative resolution after
  relocation, absolute addresses trusted only in their session, batched read plan (never across regions), live
  values from the mock in one request, entry follows a mock game restart.
* `RangeModesTest` – bigger/smaller/between for signed ints and floats (NaN never matches), fallback from server
  tiers, results equal to brute force.
* `PointerScanTest` – parallel sort, chain notation/JSON, chain found from static data to a heap value in a mock
  game, every reported chain resolves to the target, chain survives a restart with a relocated heap while a decoy
  is filtered out, broken chains never read unmapped memory, map/scan/result limits. `PointerWatchTest` – pointer
  entries in the address list follow a moving object and turn "broken" on a null pointer without failing reads.
* `EditHistoryTest` – undo/redo of verified writes, redo cleared by a new write, peek shows game-changed memory,
  read-only blocks undo, raw byte patches follow policy (code region, system process, size, region end, read-only)
  and can be undone, history dropped on process exit.
* `SavedSessionTest` – session saved and resumed in a new controller refines exactly like the original; other
  instances (pid/title) refused; snapshot sessions not saved; truncated/garbage files rejected.
* `GoldHenImportTest` – mods parsed, unsupported ones (section, button, bad bytes) flagged; exporter output read back
  identically; check reports off/on/mismatch/unmapped from mock memory; enabling goes through the editor.
* `LinkDiagnosticsTest` – ping statistics and latency measured on a delayed mock link.
* `WatchImportExportTest` – address list export/import merges by game without duplicates; invalid files change nothing.
* `SpilledPointerIndexTest` – pointer map spilled to sorted run files yields the same chains; files are deleted.
* `PatternSearchTest` – hex/wildcard/text parsing, matches across chunk borders, none across regions, alignment,
  hit cap; results equal to brute force over mock memory.

* `X86Test` – decoding of the demo game's routine (RIP-relative targets, read/write operands), branches and invalid
  bytes, effective addresses from registers, the instruction that ends at a trap RIP, sync of a sweep before an
  address, signatures with wildcarded displacements.
* `CodePatchTest` – assembling on the mock (incl. Keystone-style errors and reconnect), reference search, missing
  command on older payloads, whole-instruction planning with NOP padding and refusals, ON/OFF that really changes the
  demo game's behaviour, library round trip, GoldHEN groups and running-title check, switching from the library.
* `AccessMonitorTest` – find what writes/accesses with the mock debugger (instruction, count, registers, effective
  address), stop and detach, busy debugger, dropped connection ends the session, port in use, watchpoint
  alignment/slot checks, SIGCONT, interrupt packet layout; extended attach (PR #1): the announced port is used with
  nothing configured on the mock, body bytes equal the PR's example, out-of-range port rejected before attaching,
  fallback to a free port; capability level 1.2 gate: levels compared numerically, a level-1.1 payload gets no
  attach request at all (nothing left attached), a level-1.1 payload is served with the 8-byte request when the
  legacy port can be opened, a failed connect-back at level 1.2 is explained (leftover attachment emulated).
* `WatchRobustnessTest` – the game is never left paused: Capture once records the first writer and releases;
  a refused CONTINUE is retried; a CONTINUE that keeps failing ends the session and the game runs; a stalled
  CONTINUE times out and the session is released from a new connection; a dropped connection the PS4 did not notice
  is recovered; cancelling while the game is paused still resumes and detaches; the flood guard stops before a stall;
  an unreachable PS4 is reported as unconfirmed (idempotent release); results saved, reloaded and resolved after a
  restart.
* `SessionValidatorTest` – after a restart: module entries valid, absolute entries unverified, broken pointers
  invalid; moved code found again by signature; patched state recognised; pointer candidates ranked over three
  restarts by consensus and by a confirmed address; activity probe; error advice.

Run: `cd android && ./gradlew :core:test` (works without an Android SDK).

## Android — end-to-end UI journey (automated, mock backend)
`AppJourneyTest` (Robolectric, `./gradlew :app:testDebugUnitTest`, runs in CI) launches the real `MainActivity`
and drives it like a user against `MockPs4DebugServer` (ps4debug-NG emulation) over TCP: connect → process →
Turbo first scan → refine → read-only editor → enable editing → confirmed, verified write → restore → save
cheat → memory viewer → cheat library → settings. It asserts on-screen text and the mock's memory, and saves a
screenshot per step (`app/build/outputs/roborazzi/`). CI publishes the screenshots and JUnit XML to the
`ci-screenshots` branch (see its README.md). Further tests: refused connection is reported and recoverable;
public addresses are blocked; address list survives a game restart (module-relative) without duplicates; byte
pattern search; "Value between" scan; undo/redo and memory-viewer byte patches inside the journey; address entry
saved as a cheat; connection diagnostics latency test and guide; tablet layout (1280 dp landscape) with a navigation
rail; pointer scan from an address-list entry finds the static chain, and the
added pointer entry follows the object when the mock game moves it; an Assembly ON/OFF patch (disassemble, assemble
`nop` on the mock, ON/OFF with confirmation, the demo routine stops/starts spending coins, save, switch from the
library, session check); "What writes?" lists the store instruction with its hit count, stops and detaches; game
activity check and capability level in Diagnostics; a level-1.1 payload disables Start and sends nothing; Capture
once records the first writer, the game keeps running, the saved result opens in the disassembler. This shows the app works end to end with a ps4debug-NG-compatible server; it is
not a PS4 test.

## Android
`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` (needs an Android SDK; runs in CI).
UI tests on a device/emulator are not automated yet.

## Manual testing without a PS4
`./gradlew :mockserver:run --args="7440"` on a computer in the same LAN, then connect the app to that
computer's IP, port 7440. The demo process `eboot.bin` (pid 77) holds coins = 5000 at `0x500120`;
type `spend 800` in the server console to simulate the game. **This is a simulator.**

## Layer 3 — physical PS4 (pending; not performed by the development agent)
Requirements: PS4 on FW 11.00 with GoldHEN, ps4debug payload loaded, Android phone on the same LAN, an offline
game or homebrew you own.

1. Connect with the console IP, port 744. Expect: status Connected, debugger version shown; firmware "11.00" or "unknown".
2. Processes → refresh. Expect the game as `eboot.bin`. Select it. Expect title ID and a non-empty memory map.
3. Note an on-screen number (e.g. money). Scanner: Int32, Exact, first scan.
4. Change the value in game, next scan with the new value; repeat until few results.
5. Open a result, enable editing in Settings, write a new value, confirm. Expect "Write verified" and the game shows it.
6. Restore. Expect the original value back.
7. Save as cheat; restart the game; Cheats → Validate. Expect module-relative cheats to be re-resolved, heap
   cheats flagged as possibly stale.
8. With ps4debug-NG: check the Connection screen lists "Turbo (PS4) → Server-side (PS4) → Android"; run an Int32
   exact scan and confirm the result line shows "Turbo Scan (PS4)", elapsed time and network bytes; repeat with
   Settings → Scan backend = Android only and compare times. Record both in docs/PERFORMANCE.md.
9. Pull the network cable/Wi-Fi during a scan. Expect "Connection lost", previous results kept.
10. Record results in `docs/COMPATIBILITY.md` (firmware, GoldHEN version, ps4debug version, game, outcome).
