# Roadmap

| Version | Scope | Status |
|---|---|---|
| 0.1–0.4 (MVP) | Connection, processes, memory map, scanner (6 types, exact/inc/dec/changed/unchanged/±by, unknown initial), editor with verified writes/restore, cheat library, JSON import/export, GoldHEN export, CI | Implemented; mock-tested; hardware pending |
| 0.5 Fast scanning | Turbo Scan, ps4debug-NG server-side scan, automatic fallback, adaptive refine, benchmark | Implemented; mock-tested; hardware pending |
| 0.5+ Smart scanning | Pointer scanning and chain validation, signature (AOB) scanning, candidate ranking, cross-session validation | Pointer scan with restart filter (0.8, spilled map 0.9), AOB/text search (0.8), module-relative addresses; candidate ranking open |
| 0.6 Live | Value monitoring, freeze while connected | Done (client-side freeze, live result refresh, live memory viewer) |
| 0.7 Reliability | Reconnect, keep-alive, game detection, lifecycle, structured log | Done; core flow reported working on hardware by the owner |
| 0.8–0.10 Dev tools | Address list, range modes, pointer scan, byte patches, undo/redo, session resume, GoldHEN import | Done; mock-tested |
| 1.0 App | Diagnostics, guide, tablet layout, signed-release path | Done in the app; a signed release needs the owner's key (docs/RELEASE_SIGNING.md) |
| Later: PS4 agent | Console-side freeze via GoldHEN plugin SDK | Not started (see `ps4-agent/`); only if ps4debug-NG proves insufficient |
