# Compatibility status

Primary backend: **ps4debug-NG**. Classic/GoldHEN ps4debug: compatibility mode (Android-side scanning only).

| Item | Status |
|---|---|
| ps4debug-NG CMD_PROC_AUTH (automatic) | Keystream verified against the upstream C code (test vectors); **works on hardware (user report, FW 11.00, NG v1.3.3)** |
| ps4debug wire protocol (commands used) | Implemented from upstream source; verified against mock server only |
| GoldHEN ps4debug v1.1.x on PS4 FW 11.00 | **Pending hardware verification** |
| ps4debug-NG | Same commands; expected compatible from source review; **not tested on hardware** |
| ps4debug-NG server-side scan (iterative START/COUNT) | Implemented from `proc.c`; verified against mock emulation only; **hardware pending** |
| ps4debug-NG Turbo Scan (resident START/COUNT/GET/END/CANCEL) | Implemented from `scan_turbo.c`; first scan + refines **work on hardware (user report)**; CANCEL not yet reported |
| Turbo page-table aliasing | Opt-in flag only; **never exercised** (mock ignores it) |
| GoldHEN ps4debug server scans | Not used (opcode semantics unverifiable; classic CMD_PROC_SCAN has a known source bug) |
| Firmware display | Via `CMD_FW_VERSION` extension when present; otherwise "unknown" |
| GoldHEN cheat JSON export | Schema from the official repository; offset semantics from PS4CheaterNeo; **not verified in GoldHEN** |
| Android | minSdk 26 (Android 8.0), targetSdk 36; built in CI; **no device test recorded yet** |

## Hardware test log
Results below were **reported by the project owner** after testing on their own console (the development agent
has no hardware access and did not observe them directly).

| Date | PS4 FW | GoldHEN | Debugger | Game | Result (user-reported) |
|---|---|---|---|---|---|
| 2026-11-04 | 11.00 | yes | ps4debug-NG v1.3.3 | a real (offline) game | TCP connect on port 744 ✔ · CMD_PROC_AUTH ✔ · process list ✔ · Turbo Scan selected and returned results ✔ · successive Next Scans narrowed candidates ✔ · ammo value changed in game ✔ |

Not yet reported on hardware: GoldHEN cheat export, freeze, memory viewer, unknown-initial-value scans, reconnect
behaviour, game restart detection, server-side (non-Turbo) scan path.
