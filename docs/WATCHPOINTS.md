# What writes / accesses — watchpoints

"What writes?" and "What accesses?" use ps4debug-NG's debugger: the app attaches to the game, puts a hardware
watchpoint (x86 debug register DR0) on the address and lists every instruction that touches it. Game memory is not
changed. Every hit pauses the game for one network round trip; the app resumes it immediately and detaches when you
press Stop, leave the screen, after 1000 hits or after 2 minutes. Closing the connection also detaches (the PS4 does
that itself).

## Requirement: ps4debug-NG with capability level 1.2

To report hits, **the PS4 opens a connection back to the phone**. Released ps4debug-NG builds (up to 1.3.4) always
use port **755**, and Android only lets apps listen on ports below 1024 with root.

[Pharaoh2k/ps4debug-NG pull request #1](https://github.com/Pharaoh2k/ps4debug-NG/pull/1) adds an optional
`debug_port` field to `CMD_DEBUG_ATTACH` and advertises it as **capability level 1.2** in the `CMD_BRANDING` reply
(the text after the NUL; released builds report `1.1`). The app reads that level when it connects and:

| Level | What the app does |
|---|---|
| **1.2 or newer** | listens on a high port (7550, or any free port), sends the 12-byte attach `{pid, client_ip, debug_port}`; no root, nothing to configure |
| below 1.2 / none | sends **nothing** to the PS4 and explains that a newer ps4debug-NG is needed (Start is disabled). Exception: where the system lets the app open port 755 too (privileged/desktop), it sends the legacy 8-byte attach and listens on 755 |

Checking the level first matters: ps4debug-NG attaches to the game *before* connecting back, so a connect-back
that fails can leave the game attached until it is restarted. With the check, an old payload never gets an attach
request it cannot complete. Settings → Connection diagnostics shows the level ("watchpoints ready").

**Status:** the pull request (commits `5a19d63`, `e5ce2bf`) is **not merged** into the official ps4debug-NG yet
(upstream is still `8be828f`, v1.3.4). Load a payload built from that branch.

If attaching still fails with level 1.2 (e.g. phone and PS4 on different networks), the payload may already be
attached: **restart the game** before trying again.

## Reading the results

* Each row is one instruction with how often it hit, the thread name and — when the instruction's operands could be
  computed from the registers — the address it accessed. Tap it for all general-purpose registers.
* Data watchpoints trigger *after* the instruction ran; the app decodes the instruction that ends at the reported
  RIP. "Open in disassembler" jumps there, where you can build an ON/OFF patch.
* Sizes are 1, 2, 4 or 8 bytes and must be aligned; the app shrinks the size when the address is not aligned.
* Only one debugger session can exist on the PS4. Close other tools (PS4CheaterNeo …) first.

## Status

Implemented from the ps4debug-NG 1.3.4 source plus pull request #1 (commits `5a19d63`, `e5ce2bf`) and verified
against the simulated server in CI. Not yet reported on real hardware — please file a hardware test report.
