# Changelog

All hardware-related items remain **unverified on a real PS4** unless stated otherwise.

## 0.7.0
- First hardware report (owner): FW 11.00 + GoldHEN + ps4debug-NG v1.3.3 — connect, auth, process list, Turbo Scan,
  refines and a write to a real game worked. Recorded in docs/COMPATIBILITY.md.
- Automatic reconnection after dropped/server-closed connections (exponential backoff, "Reconnecting" state).
  Reads are repeated once on the new session; writes and server scans are never repeated automatically.
- Keep-alive detects silently dropped links while idle.
- Game detection by title ID, executable path and name (eboot.bin, default.elf, …); NPXS system apps and Sce*
  daemons are classified as system. The running game is auto-selected after connecting (setting).
- Process search/filter (name, pid, title ID, path); classification shown per process.
- Game lifecycle: exit is detected (scan session closed, freeze stopped, stored originals discarded, scanning
  blocked); a restart of the same game under a new pid is picked up automatically with a fresh session.
- Structured diagnostics log (category + fields), exportable as JSON Lines from Settings.
- A closed session is shown as information ("Scan session closed: … exited"), not as a failure, and is cleared
  when another process is selected.

## 0.6.0
- End-to-end UI journey test (Robolectric + Roborazzi) drives the real app against the mock ps4debug-NG
  server and records screenshots of every step; CI publishes them to the `ci-screenshots` branch.
- UI polish from reviewing those screenshots: region shown under each scan result, no duplicated result count,
  memory viewer highlights the requested address and shows its value as Int8…Double, cheat profiles say
  "version not set" instead of "v?", Apply reads "Validate first" until the profile is validated.
- Save-as-cheat is an inline form in the editor (was a dialog that the keyboard could cover).
- Memory viewer: 256-byte hex/ASCII window around any address, paging, live refresh with highlighting of
  bytes that changed between reads (memory diff), unmapped bytes shown as `??` and never requested.
- Live values: optional periodic refresh of the visible scan-result page (interval in Settings).
- Export scan results as CSV (address, type, value, bytes, region).
- Setting to skip memory regions larger than N MiB during scans.
- Editor: "View memory" opens the viewer at the current address; viewer lines open the editor.
- Turbo: at most 100,000 survivors are copied to the phone per step (was up to 1,000,000); the full set stays on
  the PS4 for the next Turbo refine. For Int32 this bounds the fetch to ~1.6 MB instead of ~16 MB.

## 0.5.0-test.1 (pre-release, debug-signed)
- ps4debug-NG as primary backend with automatic CMD_PROC_AUTH (keystream verified against upstream C code).
- Turbo Scan (server-resident) → ps4debug-NG server-side scan → Android chunked scan, automatic fallback.
- Elapsed time, MiB/s, network bytes and backend shown during and after scans; benchmark in CI.
- Adaptive Android refine batching (measured RTT × bandwidth).

## 0.4.0
- MVP: connection, processes, memory map, scanner (6 types, exact/unknown initial + refine modes),
  verified memory editor with restore, client-side freeze, cheat library, JSON import/export, GoldHEN export.
