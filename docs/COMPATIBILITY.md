# Compatibility status

Primary backend: **ps4debug-NG**. Classic/GoldHEN ps4debug: compatibility mode (Android-side scanning only).

| Item | Status |
|---|---|
| ps4debug-NG CMD_PROC_AUTH (automatic) | Keystream verified against the upstream C code (test vectors); handshake verified against mock; **hardware pending** |
| ps4debug wire protocol (commands used) | Implemented from upstream source; verified against mock server only |
| GoldHEN ps4debug v1.1.x on PS4 FW 11.00 | **Pending hardware verification** |
| ps4debug-NG | Same commands; expected compatible from source review; **not tested on hardware** |
| ps4debug-NG server-side scan (iterative START/COUNT) | Implemented from `proc.c`; verified against mock emulation only; **hardware pending** |
| ps4debug-NG Turbo Scan (resident START/COUNT/GET/END/CANCEL) | Implemented from `scan_turbo.c`; verified against mock emulation only; **hardware pending** |
| Turbo page-table aliasing | Opt-in flag only; **never exercised** (mock ignores it) |
| GoldHEN ps4debug server scans | Not used (opcode semantics unverifiable; classic CMD_PROC_SCAN has a known source bug) |
| Firmware display | Via `CMD_FW_VERSION` extension when present; otherwise "unknown" |
| GoldHEN cheat JSON export | Schema from the official repository; offset semantics from PS4CheaterNeo; **not verified in GoldHEN** |
| Android | minSdk 26 (Android 8.0), targetSdk 36; built in CI; **no device test recorded yet** |

## Hardware test log
No physical test has been performed yet. Add rows here when one is:

| Date | PS4 FW | GoldHEN | Debugger | Game (title/version) | Result |
|---|---|---|---|---|---|
