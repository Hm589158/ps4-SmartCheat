# Roadmap

| Version | Scope | Status |
|---|---|---|
| 0.1–0.4 (MVP) | Connection, processes, memory map, scanner (6 types, exact/inc/dec/changed/unchanged/±by, unknown initial), editor with verified writes/restore, cheat library, JSON import/export, GoldHEN export, CI | Implemented; mock-tested; hardware pending |
| 0.5 Fast scanning | Turbo Scan, ps4debug-NG server-side scan, automatic fallback, adaptive refine, benchmark | Implemented; mock-tested; hardware pending |
| 0.5+ Smart scanning | Pointer scanning and chain validation, signature (AOB) scanning, candidate ranking, cross-session validation | Module-relative addresses + stale detection done; rest open |
| 0.6 Live | Value monitoring, freeze while connected | Client-side freeze + result refresh done; periodic auto-refresh open |
| 0.7 Dev tools | Hex viewer/editor, region diff, scan-session export, bookmarks | Open |
| 1.0 PS4 agent | Console-side scanning/freeze via GoldHEN plugin SDK | Not started (see `ps4-agent/`) |
