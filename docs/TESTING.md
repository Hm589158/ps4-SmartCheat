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

Run: `cd android && ./gradlew :core:test` (works without an Android SDK).

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
