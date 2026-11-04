# Compatibility status

| Item | Status |
|---|---|
| ps4debug wire protocol (commands used) | Implemented from upstream source; verified against mock server only |
| GoldHEN ps4debug v1.1.x on PS4 FW 11.00 | **Pending hardware verification** |
| ps4debug-NG | Same commands; expected compatible from source review; **not tested on hardware** |
| Firmware display | Via `CMD_FW_VERSION` extension when present; otherwise "unknown" |
| GoldHEN cheat JSON export | Schema from the official repository; offset semantics from PS4CheaterNeo; **not verified in GoldHEN** |
| Android | minSdk 26 (Android 8.0), targetSdk 36; built in CI; **no device test recorded yet** |

## Hardware test log
No physical test has been performed yet. Add rows here when one is:

| Date | PS4 FW | GoldHEN | Debugger | Game (title/version) | Result |
|---|---|---|---|---|---|
