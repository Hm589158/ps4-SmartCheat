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
2. **Hook.** The instructions the jump overwrites are highlighted (whole instructions covering 5 bytes, or 14 when the
   cave is more than 2 GiB away). Tap a later instruction to replace more of them; tap the first one to go back to the
   minimum. The highlighted instructions are the **Source Code**.
3. **Source Code** (read-only) shows those original instructions with their addresses and bytes.
4. **Target Code** (editable) is what runs in the cave instead. It starts as a copy of the Source Code followed by
   `jmp return`, so an unchanged injection behaves exactly like the game. Edit it freely:
   * write your code **before** or **after** the original instructions, or **between** them;
   * change or delete original lines to **replace** them (the preview lists what no longer runs);
   * use `jmp return` (back to the game, right after the hooked instructions) **as often as you need and wherever you
     need it** — also conditionally, e.g. `cmp eax, 100` / `jl return`;
   * define labels (`skip:`) and branch to them; write raw bytes with `db 90 90`.
5. Choose the cave:
   * **Unused padding in the game executable.** The app searches for it automatically. The address is the same every
     run, so the injection can be saved, switched from Cheats, and exported to GoldHEN.
   * **Allocated memory near the hook.** ps4debug-NG maps 16 KiB of `rwx` memory for this game session only. This
     injection cannot be saved.
6. **Build preview.** This shows the hook's bytes OFF and ON, the cave listing with each part labelled (`new` your
   code, `orig` moved original instruction, `ret` jump back, `db` raw bytes), the labels, and the warnings. Nothing
   has been written at this point.
7. **Inject (ON)** / **Remove (OFF)** each ask for confirmation. Every write is verified by reading it back.
8. **Save to Cheats…** (executable caves only) stores the injection as one group; the Target Code is kept with it.

Memory editing and "Allow writes to non-writable regions" must both be enabled in Settings, as for code patches.

### Target Code example

Hooking `sub eax, 1` in a routine that spends coins (`mov eax, [coins]` / `sub eax, 1` / `mov [coins], eax`):

```
cmp eax, 100
jl return                 // fewer than 100 coins: skip the original code, nothing is spent
cmp eax, 1000
jge spend
mov eax, 1000             // top up to 1000 first
spend:
sub eax, 1                // original instruction (kept, moved into the cave)
mov [0x500120], eax       // original instruction (kept, moved into the cave)
jmp return                // back to the game
```

## How the Target Code is turned into bytes

```
hook:   jmp cave            ; 5 bytes (jmp rel32), or 14 (jmp [rip] + address) if the cave is > 2 GiB away
        nop …               ; rest of the overwritten whole instructions (never executed)
return: …                   ; game continues here

cave:   <Target Code>       ; laid out line by line from the cave address
```

Each line of the Target Code is one of:

| Line | Encoded by | Notes |
|---|---|---|
| identical to an instruction at the hook (spacing and case ignored) | the phone (iced BlockEncoder) | the original instruction is **moved**, not re-assembled: the listing shows RIP-relative operands as absolute addresses, which the assembler would encode differently |
| `jmp return` | the phone | `jmp rel32`, or `jmp [rip]` + 8-byte address when the cave is more than 2 GiB from the hook |
| `name:` | — | a label; its address is resolved on the phone |
| `db 90 90` / `db 0x90, 0x90` | the phone | raw bytes |
| anything else | ps4debug-NG's Keystone, at its final address | `return` and labels used as branch/call targets are replaced by their address first (`jl return` → `jl 0x…`) |

Consecutive assembler lines are assembled together. Because a label's address depends on the size of the code before
it (the assembler may choose a short or a near branch), the layout is repeated until every label stays where it was
in the previous pass (at most 10 passes). The Target Code must end with `jmp return` (or another `jmp`/`ret`),
otherwise the CPU would run into the bytes after the cave; labels and `return` cannot be used inside memory operands;
with a cave more than 2 GiB away only a plain `jmp return` can go back (use `jne go` / `jmp return` / `go:` for a
condition). Without ps4debug-NG nothing can be assembled, but Target Code made only of original instructions, `db`
lines and `jmp return` still works.

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

## Original bytes (OFF) and the GoldHEN safety check

An injection's OFF bytes are the hook's bytes read from memory when the injection is built. If another patch is ON at
that address — typically an earlier injection that was edited and built again, or a patch enabled by GoldHEN — those
bytes are that patch's jump, not the game's code. Saved as OFF, the jump would be written back later with nothing
behind it (the old cave is empty in a fresh game), and the new cave would copy the jump as an "original" instruction.

* **Building is refused** while a known patch is ON at the hook or the cave: library entries of the game's profiles
  and this session's injections (`AppliedPatches`). The same applies to code patches in the disassembler. Switch the
  other patch OFF (or restart the game) first.
* **Before a GoldHEN export and before a library switch**, every mod passes `GoldHenSafety` (no game connection
  needed). A mod is left out of the file (and its switch is blocked) when:
  - OFF of a jump or code patch is a jump into a code cave, or starts with a `jmp rel32` saved at a hook (an earlier
    injection), or is a 64-bit absolute jump;
  - ON or OFF of code ends in the middle of an instruction or holds invalid instructions;
  - the hook's ON is not `jmp` to this mod's cave followed only by whole `nop`s;
  - the cave code, followed path by path from its start, runs past its end into padding, jumps into the instructions
    the hook replaced, never leaves the cave, stops the game (`int3`) or uses a 64-bit absolute jump (its target is this
    session's address);
  - the cave's OFF is not unused padding (`00`/`CC`), its parts are not contiguous, or the mod has a cave without a
    jump (or a jump without its cave);
  - two mods write the same bytes with different original bytes, or two injections share code.
  Two value mods at the same address with the same original bytes are exported with a warning (enable only one).
* The written file is read back and compared with what was meant (offsets as hex without `0x`, `on`/`off` as whole
  upper-case hex bytes of equal length, fields as in the GoldHEN cheat repository).

What GoldHEN itself does when its cheat menu opens is not documented (its loader is closed source) and has not been
observed with these files on hardware; the checks make sure that writing any `on` or `off` of an exported mod leaves
the game's code consistent.

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
