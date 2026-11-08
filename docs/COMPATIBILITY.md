# Compatibility status

**Required debugger: [ps4debug-NG](https://github.com/Pharaoh2k/ps4debug-NG)** (by OSR / Pharaoh2k, GPL-3.0).
The classic ps4debug payload only works in an untested compatibility mode (Android-side scanning) and is not supported.

Legend: **hardware** = reported on a real console by the project owner · **mock** = verified end to end against the
independent mock ps4debug-NG server in CI only.

| Area | Status |
|---|---|
| Connect on port 744, CMD_VERSION / FW_VERSION / BRANDING | **hardware** (FW 11.00, ps4debug-NG 1.3.3) |
| ps4debug-NG CMD_PROC_AUTH (automatic) | **hardware**; keystream also checked against the upstream C code (test vectors) |
| Process list, process info, memory map | **hardware** |
| Turbo Scan (resident START / COUNT / GET / END) | **hardware**: first scan and next scans narrowed candidates. CANCEL: mock |
| Memory write with read-back verification | **hardware** (value changed in a real game) |
| Server-side (iterative) scan, Android chunked scan | mock (used automatically when Turbo is unavailable) |
| Reconnect, keep-alive, game exit/restart detection | mock |
| Address list, pointer scan, byte-pattern/text search, byte patches, undo/redo, session resume | mock |
| GoldHEN cheat export / import | mock; schema from the official cheat repository, offset meaning from PS4CheaterNeo; not yet checked inside GoldHEN |
| Turbo page-table aliasing | opt-in flag; never exercised |
| Assembler (`0xBDAA0024`), reference search (`CMD_PROC_FIND_XREFS_TO`) | mock; layouts from the ps4debug-NG 1.3.4 source |
| Code patches ON/OFF (writes to executable memory) and their GoldHEN export | mock; GoldHEN offset meaning as for value cheats (not yet checked inside GoldHEN) |
| Debugger attach, hardware watchpoints, interrupt channel (What writes / accesses) | mock; needs ps4debug-NG capability level 1.2 (configurable interrupt port, [PR #1](https://github.com/Pharaoh2k/ps4debug-NG/pull/1), not merged upstream yet); level checked before attaching |
| Session check, pointer candidates, game activity check, SIGCONT resume | mock |
| ps4debug-NG 1.3.4 (current upstream) and other firmware listed by ps4debug-NG | expected compatible (same protocol); not yet reported |
| Classic / GoldHEN ps4debug | not supported (compatibility mode untested; its server scans are never used) |
| Android | min SDK 26 (Android 8.0), target SDK 36; used on the owner's phone for the hardware report above |

## Hardware test log

Results below were **reported by the project owner** after testing on their own console. The development agent has
no hardware access and did not observe them directly.

| Date | PS4 FW | GoldHEN | Debugger | Game | Result (user-reported) |
|---|---|---|---|---|---|
| 2026-11-04 | 11.00 | yes | ps4debug-NG v1.3.3 | a real (offline) game | TCP connect on port 744 ✔ · CMD_PROC_AUTH ✔ · process list ✔ · Turbo Scan selected and returned results ✔ · successive Next Scans narrowed candidates ✔ · ammo value changed in game ✔ |

Please add yours with the [hardware test report](https://github.com/Hm589158/ps4-SmartCheat/issues/new?template=hardware_report.yml)
issue form.
