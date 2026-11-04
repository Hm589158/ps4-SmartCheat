# Changelog

All hardware-related items remain **unverified on a real PS4** unless stated otherwise.

## 0.6.0 (unreleased)
- Memory viewer: 256-byte hex/ASCII window around any address, paging, live refresh with highlighting of
  bytes that changed between reads (memory diff), unmapped bytes shown as `??` and never requested.
- Live values: optional periodic refresh of the visible scan-result page (interval in Settings).
- Export scan results as CSV (address, type, value, bytes, region).
- Setting to skip memory regions larger than N MiB during scans.
- Editor: "View memory" opens the viewer at the current address; viewer lines open the editor.

## 0.5.0-test.1 (pre-release, debug-signed)
- ps4debug-NG as primary backend with automatic CMD_PROC_AUTH (keystream verified against upstream C code).
- Turbo Scan (server-resident) → ps4debug-NG server-side scan → Android chunked scan, automatic fallback.
- Elapsed time, MiB/s, network bytes and backend shown during and after scans; benchmark in CI.
- Adaptive Android refine batching (measured RTT × bandwidth).

## 0.4.0
- MVP: connection, processes, memory map, scanner (6 types, exact/unknown initial + refine modes),
  verified memory editor with restore, client-side freeze, cheat library, JSON import/export, GoldHEN export.
