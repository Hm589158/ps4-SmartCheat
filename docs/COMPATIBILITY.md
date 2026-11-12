# Compatibility status

**Required debugger: [ps4debug-NG](https://github.com/Pharaoh2k/ps4debug-NG)** (by OSR / Pharaoh2k, GPL-3.0).
The classic ps4debug payload only works in an untested compatibility mode (Android-side scanning) and is not supported.

## Firmware

**Every firmware ps4debug-NG supports.** The app has no firmware-specific code: it only talks to ps4debug-NG, which
does all firmware-dependent work on the console. ps4debug-NG lists 39 versions (README "Supported firmwares", commit
`8be828f`) and refuses to install on others:

| Major | Versions |
|---|---|
| 5.xx | 5.05, 5.07 |
| 6.xx | 6.71, 6.72 |
| 7.xx | 7.00, 7.02, 7.50, 7.51, 7.55 |
| 8.xx | 8.00, 8.03, 8.50, 8.52 |
| 9.xx | 9.00, 9.03, 9.04, 9.50, 9.51, 9.60 |
| 10.xx | 10.00, 10.01, 10.50, 10.70, 10.71 |
| 11.xx | 11.00, 11.02, 11.50, 11.52 |
| 12.xx | 12.00, 12.02, 12.50, 12.52 |
| 13.xx | 13.00, 13.02, 13.04, 13.50, 13.52 |
| 14.xx | 14.00, 14.02 |

The Connection screen shows the console's firmware (`CMD_FW_VERSION`) and whether it is in this list
(`core.protocol.Ps4Firmware`). Hardware reports so far are from FW 11.00 (below); reports from other firmware are welcome.

## Feature status

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
| Code caves / Assembly injection (jump + cave in the executable's padding, saved and exported as one GoldHEN mod) | mock: the simulated game executes the injected code with a small x86 interpreter. Not yet run on a PS4 |
| Allocated caves (`CMD_PROC_ALLOC_HINTED` / `CMD_PROC_FREE`) | mock; layouts from the ps4debug-NG 1.3.4 source; whether games may execute the new mapping is unknown on hardware |
| Debugger attach, hardware watchpoints, interrupt channel (What writes / accesses) | mock; needs ps4debug-NG capability level 1.2 (configurable interrupt port, [PR #1](https://github.com/Pharaoh2k/ps4debug-NG/pull/1), not merged upstream yet); level checked before attaching |
| Session check, pointer candidates, game activity check, SIGCONT resume | mock |
| ps4debug-NG 1.3.4 (current upstream) and the other firmware versions above | supported (same protocol, no firmware-specific code in the app); not yet reported on hardware |
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
