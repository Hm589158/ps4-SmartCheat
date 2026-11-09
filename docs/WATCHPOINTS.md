# What writes / accesses — watchpoints

"What writes?" and "What accesses?" use ps4debug-NG's debugger: the app attaches to the game, puts a hardware
watchpoint (x86 debug register DR0) on the address and records the instruction that touches it. Game memory is not
changed. Every hit pauses the game for one network round trip; the app resumes it immediately (see below).

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

## Modes

| Mode | What happens | Pauses |
|---|---|---|
| **Capture once** (default) | stops at the first hit, records the instruction and all registers, then removes the watchpoint and detaches | exactly one |
| **Monitor** | lists every instruction with its hit count; ends at Stop, 1000 hits, 2 minutes, or when the flood guard trips | one per hit |

**Flood guard:** more than 40 hits within one second (a value read every frame, several threads…) means the game
spends most of its time paused; monitoring ends and the debugger is released before the game stalls. Use Capture once
or "Writes" for such values.

## How the app keeps the game from freezing

A watchpoint hit is a ptrace stop: **the whole game is paused on the PS4 until the app's `CMD_DEBUG_CONTINUE` is
processed**. The app therefore:

1. resumes first, before decoding anything, and **checks the server's answer** — `CMD_ERROR` (a failed
   `PT_CONTINUE`) is retried once, then the session is ended (DETACH resumes the game);
2. uses a 3-second timeout per debugger command (instead of the 10-second read timeout), so a stuck command ends
   quickly;
3. never sends debugger commands over a re-established connection (that connection has no session);
4. ends every session — Capture once, Stop, limits, flood guard, errors, leaving the screen, cancellation — through
   one cleanup that **verifies each step**: CONTINUE for a stop that was read but not confirmed resumed, clearing the
   watchpoint, draining and resuming stops that arrived meanwhile, then DETACH (the server clears all debug registers
   and `PT_DETACH`es, which also resumes a stopped game);
5. if the session's connection was lost, sends DETACH again **from a new connection** — the server's debug state is
   global, so this releases a session (and a paused game) the PS4 still holds; `CMD_SUCCESS` = released now,
   `CMD_ERROR` = no session left;
6. shows the result: "Game resumed · watchpoint removed · debugger detached", or a warning when it could not be
   confirmed. All steps are written to the diagnostics log (Settings → Export).

### What cannot be guaranteed

* **The PS4 is unreachable** (Wi-Fi off, console asleep, payload crashed) while the game is paused at a hit: no
  command can reach it. ps4debug-NG ends the session itself when it notices the closed connection (then the game
  resumes), but without a TCP reset it may not notice for a long time. The app reports "release could not be
  confirmed"; reconnect and use Settings → Connection diagnostics → Resume process, or reload ps4debug-NG.
* **The app process is killed** by Android at the exact moment of a hit: the PS4 ends the session when the socket
  closes (normally immediately) — the app cannot do anything itself.
* **A connect-back that failed during attach** (level 1.2, different networks): ps4debug-NG attaches before
  connecting back and then leaves the game attached; restart the game.
* **Another tool's session**: recovery DETACH acts on the PS4's single global debug session. If another debugger
  attached in the seconds between our session ending and the recovery, it would be detached as well.

## Saved results

Finished sessions (newest 30, all games) are saved with each instruction's address, executable-relative offset
(`eboot.bin+0x…`), bytes, text, count, thread and registers. They are listed under **Saved results** on the
watchpoint screen and open in the disassembler later — no debugger session needed, also after a game restart
(the module offset is resolved again).

## Registers in the disassembler

"Open in disassembler" (a live hit) or "Open" (a saved one) passes the hit's registers to the Code screen: the
"Registers at the hit" card lists the registers the selected instruction uses with their values, the operand
address computed from them and the value in memory there now; each row shows its registers' values and the hit is
marked ◆. The values are those right after the hit instruction ran; other instructions may have seen different ones.

## Call stack

At the first hit of each instruction the app also records **who called it**:

1. While the game is still paused, it reads up to 1 KiB of the thread's stack from `rsp`. This is one `CMD_PROC_READ`
   before CONTINUE, bounded by the stack mapping. If the read fails, no stack is recorded and the resume goes ahead
   as usual.
2. On the phone, every 8-byte value on that stack that points into executable memory is checked. It counts as a
   return address only if the code right before it decodes as a `call` instruction (`E8 rel32` or the `FF /2`
   forms).
3. Each frame gets a confidence:

| Shown as | Meaning |
|---|---|
| call verified | a direct call whose target is the start of the function the frame below is in |
| frame pointer chain | the slot is on the saved `rbp` chain (`[rbp]` → caller's rbp, `[rbp+8]` → return address) |
| indirect call | `call rax`, `call [rip+…]`: the target cannot be checked |
| possibly stale | a direct call to some other function, usually left on the stack by an earlier call; hidden by default |

PS4 games have no unwind information the app can use, so the stack can contain stale entries or miss frames, such as
tail calls that leave no return address behind. This is the same heuristic as Cheat Engine's stack view. Tap a frame
to open the caller in the disassembler; **Copy** puts the stack on the clipboard. Saved sessions keep each frame's
module offset.

An execute breakpoint ("capture the call stack when this instruction runs") is possible with ps4debug-NG's watchpoint
command (`breaktype` 0 = `DBREG_DR7_EXEC`). It is **not** offered yet: resuming from an instruction breakpoint needs
the x86 resume flag, and without hardware tests that could make the game stop on the same instruction forever.

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
