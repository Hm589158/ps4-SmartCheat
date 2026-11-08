# What writes / accesses — watchpoints

"What writes?" and "What accesses?" use ps4debug-NG's debugger: the app attaches to the game, puts a hardware
watchpoint (x86 debug register DR0) on the address and lists every instruction that touches it. Game memory is not
changed. Every hit pauses the game for one network round trip; the app resumes it immediately and detaches when you
press Stop, leave the screen, after 1000 hits or after 2 minutes. Closing the connection also detaches (the PS4 does
that itself).

## Requirement: ps4debug-NG with the configurable interrupt port

To report hits, **the PS4 opens a connection back to the phone**. Released ps4debug-NG builds (up to 1.3.4) always
use port **755**, and Android only lets apps listen on ports below 1024 with root.

[Pharaoh2k/ps4debug-NG pull request #1](https://github.com/Pharaoh2k/ps4debug-NG/pull/1) adds an optional
`debug_port` field to `CMD_DEBUG_ATTACH`. The app uses it automatically:

1. it listens on a high port (7550, or any free port if that one is taken) — no root, nothing to configure;
2. it sends the 12-byte attach request `{pid, client_ip, debug_port}`;
3. ps4debug-NG connects back to that port.

**Status:** the pull request is **not merged** into the official ps4debug-NG yet (checked against upstream
`8be828f`, v1.3.4). Load a payload built from that branch.

## With an older payload

A payload without the change ignores the port field and connects to 755. Where the app is allowed to open port 755
as well (rooted device, desktop), it listens there too and everything works. On a normal Android phone the attach
fails with "Debugger could not attach". The payload attaches to the game *before* connecting back, so after such a
failure the game can stay attached until it is restarted — **restart the game** before trying again. The payload
does not report whether it has the change (its version string is the same), so the app cannot check this in
advance.

## Reading the results

* Each row is one instruction with how often it hit, the thread name and — when the instruction's operands could be
  computed from the registers — the address it accessed. Tap it for all general-purpose registers.
* Data watchpoints trigger *after* the instruction ran; the app decodes the instruction that ends at the reported
  RIP. "Open in disassembler" jumps there, where you can build an ON/OFF patch.
* Sizes are 1, 2, 4 or 8 bytes and must be aligned; the app shrinks the size when the address is not aligned.
* Only one debugger session can exist on the PS4. Close other tools (PS4CheaterNeo …) first.

## Status

Implemented from the ps4debug-NG 1.3.4 source plus pull request #1 (commit `5a19d63`) and verified against the
simulated server in CI. Not yet reported on real hardware — please file a hardware test report.
