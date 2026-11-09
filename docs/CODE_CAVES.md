# Code caves / Assembly injection

A code patch overwrites instructions in place, so the new code can be at most as long as the code it replaces.
An **injection** removes that limit. A jump at the hooked instruction leads to a *code cave*: free memory that holds
your code, the instructions the jump overwrote, and a jump back into the game. It works like Cheat Engine's
"code injection" template.

> **Status:** implemented from the ps4debug-NG 1.3.4 source and verified against the simulated server only. In the
> simulation, the demo game runs its routine with a small x86 interpreter, so the tests see the injected code execute.
> **It has not been run on a PS4.** Use it only in offline single-player games.

## Using it

1. **Code** → disassemble → tap the instruction to hook → **Inject code…**
2. Type your code (Intel syntax, assembled on the PS4 by ps4debug-NG's Keystone at its final address), or hex bytes.
   The template lists the instructions the jump will overwrite as comments.
3. Choose when the original instructions run: **after my code** (default), **before my code**, or **not** (your code
   replaces them).
4. Choose the cave:
   * **Unused padding in the game executable.** The app searches for it automatically. The address is the same every
     run, so the injection can be saved, switched from Cheats, and exported to GoldHEN.
   * **Allocated memory near the hook.** ps4debug-NG maps 16 KiB of `rwx` memory for this game session only. This
     injection cannot be saved.
5. **Build preview.** This shows the hook's bytes OFF and ON, the cave listing with each part labelled (new code,
   moved original, jump back), and the warnings. Nothing has been written at this point.
6. **Inject (ON)** / **Remove (OFF)** each ask for confirmation. Every write is verified by reading it back.
7. **Save to Cheats…** (executable caves only) stores the injection as one group.

Memory editing and "Allow writes to non-writable regions" must both be enabled in Settings, as for code patches.

## How it is built

```
hook:   jmp cave            ; 5 bytes (jmp rel32), or 14 (jmp [rip] + address) if the cave is > 2 GiB away
        nop …               ; rest of the overwritten whole instructions (never executed)
        …                   ; game continues here = return address

cave:   <your code>         ; assembled for this address
        <moved original>    ; the overwritten instructions, re-encoded for the cave (iced BlockEncoder)
        jmp return address
```

* **Moving instructions.** iced re-encodes RIP-relative operands so they reach the same data from the new place, and
  turns short branches into near ones. An instruction that cannot reach its target from the cave makes the planner
  refuse the injection; it is never silently broken. One example is a RIP-relative access to data above 4 GiB from
  an allocated cave more than 2 GiB away.
* **Jumps into the overwritten bytes.** The code following the hook is decoded. If a branch there targets the middle
  of the overwritten instructions, the hook is refused: that jump would land inside the new `jmp`. Code elsewhere in
  the game cannot be checked, so a warning is shown when more than one instruction is overwritten.
* **Finding caves** is read-only and limited to 2 MiB of reads. It covers the end of each code mapping of the module
  (page padding after the last function), then ±256 KiB around the hook. Candidates are ranked:
  1. padding up to the end of the code;
  2. `int3` (0xCC) runs between functions;
  3. zero runs of at least 128 bytes inside the code, flagged as less certain.

  A cave starts 8 bytes into a run, 16-byte aligned, because a run's first bytes can belong to the instruction before
  it (for example the zero bytes of `mov eax, 0x100`). Caves used by saved or active injections are skipped.
  Immediately before writing, the planner re-checks that the cave still holds only padding.

## Write order and failures

| Action | Order | If a write fails |
|---|---|---|
| ON | cave in parts of ≤ 64 bytes, each verified → jump | everything written so far is restored (verified); the jump was never written |
| OFF | jump → cave padding (executable caves) | stops; once the jump is gone the cave can no longer run, so leftover cave code is harmless ("cave written, jump not in place") |

The library uses the same order: a saved injection is the cave parts followed by the jump, all in one group. Cheats
switches them ON in that order and OFF in reverse. A mod left half-applied by an interrupted toggle can be finished
(Enable) or undone (Disable). Parts already in the target state are skipped. Parts in any other state block both.

## Known risks

* **The jump is not written atomically.** ps4debug writes memory with a kernel copy. A game thread executing those
  exact bytes at that instant can crash. Hooking code that runs rarely (or pausing the game first) reduces the risk.
* **Freeing allocated memory** while a thread is still inside the cave crashes the game. The app only frees memory
  whose injections are OFF (checked first), but cannot see threads.
* **Allocation replies cannot be trusted.** ps4debug-NG reports success even when the kernel call failed: it returns
  the hint address itself. The app therefore checks the memory map before and after, and refuses any address that
  was already in use or is not `rwx` (see [PROTOCOL.md](PROTOCOL.md#memory-allocation-for-code-caves--verified-in-source)).
  Whether a game may execute such a mapping on real firmware is unknown.
* **Injections are never re-applied automatically**, including after a reconnect or a game restart. After a game
  update, check saved injections in Cheats: the bytes then no longer match and nothing is written.
