# Changelog

All hardware-related items remain **unverified on a real PS4** unless stated otherwise.

## 0.6.0 (unreleased)
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
