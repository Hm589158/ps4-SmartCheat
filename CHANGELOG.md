# Changelog

All hardware-related items remain **unverified on a real PS4** unless stated otherwise.

## 0.9.0
- Address list → cheat library: "Save as cheat…" on an entry stores it with a value (module-relative when inside a
  module image, ready for GoldHEN export). Pointer-chain entries cannot become cheats (GoldHEN files hold fixed
  offsets only). Editor and address list share one save path.
- Pointer scan for big games: the pointer map is written to sorted run files in the app cache (memory-mapped,
  outside the Java heap) — up to 32 M pointers with ≈32 MiB of RAM while building, and up to 2 GiB of memory read.
  Run files are deleted after each scan, on failure/cancel, and at start-up. The screen shows how much memory the map
  will read before starting. Spilled and in-memory maps give identical chains (tested).
- Address list export/import (JSON, current game or all): imports merge by game, skip entries already present and
  reject foreign or newer files without changing anything.

## 0.8.0
- Address list per game (new "Addresses" tab): addresses are saved by title ID with a name and type and show
  live values (nearby entries are read in one request; unmapped entries are never read). Entries inside a module
  image are stored module-relative (e.g. `eboot.bin+0x100120`) and follow the game across restarts; absolute heap
  addresses are marked "from an earlier session — verify" outside the session they were added in, and cannot be
  frozen until checked in the editor. Add from a scan result (+), from the editor (+ List) or by address.
  Duplicates are not added. Nothing in the list is written to the console unless the user edits or freezes it.
- New scan modes "Bigger than", "Smaller than" and "Value between" (inclusive, either bound order) for first
  and next scans. They are compared on Android (no PS4-side compare code is used for them), so the scan falls
  back from Turbo/server tiers automatically and says so.
- Byte pattern (AOB) and text search (Scanner → "Byte pattern / text search…"): hex bytes with `??` and nibble
  wildcards, or UTF-8/ASCII and UTF-16LE text with optional ASCII case folding. Read-only; runs on Android with
  CMD_PROC_READ chunks; matches across chunk borders are found, matches never span two regions; hits open the
  memory viewer.
- Pointer scan (address list → entry → "Find pointers…"): builds a pointer map on Android from all readable,
  non-executable regions (read-only; limited by the snapshot size limit and "Max region size"), then searches
  backwards from the target to static module data, up to depth 1–5 and offsets up to 0x4000. After restarting the
  game, "Keep chains that lead there" filters the chains against the value's new address. Chains can be added to
  the address list; such entries are followed on every read, are marked "pointer chain broken" when a pointer
  leads to unmapped memory, and never trigger reads of unmapped memory. Pointer chains are app-only: they are not
  part of the GoldHEN export.
- Settings moved to the top bar to make room for the Addresses tab.

## 0.7.0
- CI: Robolectric's Android framework jar is resolved by Gradle and used offline (a Maven Central HTTP 429 at
  test time had failed the release gate).
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
