# Changelog

All hardware-related items remain **unverified on a real PS4** unless stated otherwise.

## 1.0.2

### Code caves: Source Code → Target Code (new — verified against the simulated server only)
- The "Original instructions run before / after my code / not" choice is gone. The injection screen now shows the
  hooked instructions as **Source Code** (read-only) and copies them into an editable **Target Code** followed by
  `jmp return`. Write your code before, after or between the original instructions, or change/delete them to replace
  them — no options needed.
- `jmp return` can be written **any number of times, anywhere** — also conditionally (`jl return`, `je return`) — to
  go back to the game right after the hook. Labels (`skip:`) work across the original instructions; `db 90 90` adds
  raw bytes.
- Lines identical to an original instruction are moved and re-encoded for the cave on the phone (RIP-relative
  operands and branches keep their targets), never re-assembled from the listing text. Original lines, `db` and
  `jmp return` need no assembler, so they also work without ps4debug-NG.
- **Choose what to replace:** tap a later instruction at the hook to overwrite more than the jump needs.
- A live summary under the editor shows how many original instructions are kept and how often the code returns;
  the preview labels every cave row (`new` / `orig` / `ret` / `db`) and shows the labels.
- Refused before anything is written: Target Code that does not end with `jmp return` (or another jmp/ret), labels
  named `return` or after a register, labels used as memory operands, conditional `return` from a cave more than
  2 GiB away.

### Look
- Cards have numbered steps, subtitles and a calmer, consistent colour scheme (neutral blue-grey containers instead
  of Material's purple-tinted defaults, in dark and light theme). Code is shown in tinted, side-scrolling code panels.

## 1.0.1

### Editing on by default
- Memory editing, writes to non-writable regions (code patches, code caves) and writes to system processes are on by
  default and kept after restarting the app. Every write is still shown for confirmation and verified by reading it
  back; writes to system processes keep their extra warning. Turn editing off in Settings for a read-only session.

### Settings reorganised
- Order by use: Connection (now with game auto-select) → Memory editing → Scanning (Engine / Regions, with limits,
  refresh and Turbo aliasing under "Advanced settings") → Diagnostics → App. The event log is folded behind
  "Show log (n)" instead of filling the screen.

### Release numbering
- Releases are numbered v1.0.1, v1.0.2, … (one step per update) instead of "beta · build N"; when a new release is
  published the older ones are removed, so the Releases page always shows the current app.


### Register values in hex and decimal
- Registers are shown as hexadecimal (padded to the register's width) **and** decimal everywhere: the "Registers at
  the hit" card (`eax = 0x00001387 (4999)`), "All registers" (one per line, 64-bit), the values next to each
  instruction in the disassembly, and the watch results. Values with the top bit set also show the signed value
  (`0xFFFFFFFF (4294967295 / -1)`).

### Call stack at watchpoint hits (new — verified against the simulated server only)
- "What writes / accesses" now records the **call stack** of the first hit of each instruction: who called the
  function that wrote the value, and who called that. ps4debug has no unwinder, so up to 1 KiB of the thread's stack
  is read **while the game is still paused** (one extra read before CONTINUE, once per instruction, never delaying
  the resume when it fails) and walked on the phone.
- A stack value counts as a return address only if a `call` instruction ends right before it (decoded from the code).
  Direct calls into the function of the frame below are marked **call verified**, slots on the saved `rbp` chain
  **frame pointer chain**, indirect calls (`call rax`, `call [rip+…]`) **indirect call**; direct calls elsewhere are
  usually stale values and hidden behind "Show possibly stale entries".
- Shown in the results (live and saved), and in the disassembler's "Registers at the hit" card: tap a frame to open
  the caller, **Copy** puts the stack on the clipboard. Saved stacks keep module offsets, so they open at the right
  place after a game restart.
- Simulated server: the demo game runs on a real stack in its memory and reaches the spend routine through two
  callers (a direct call with a frame pointer and an indirect `call rax`).

### GoldHEN export: file names and versions (fix)
- **File name by target process**, as the official GoldHEN_Cheat_Repository README specifies:
  `{titleid}_{version}.json` for eboot.bin and `{titleid}_{version}_{process}.json` for any other process — e.g.
  `CUSA05294_01.25_default.elf.json` (the repository itself has such files, e.g. `CUSA00018_01.21_default.elf.json`).
  Previously the process was always left out, so GoldHEN would not pick up cheats for games that run e.g.
  `default.elf`. The game name typed in the dialog is never part of the file name.
- **Versions are normalised**: `1.25` → `01.25`, `v1,25` → `01.25`, `2` → `02.00`; correct ones stay as they are.
  An ambiguous `1.5` is never guessed: the dialog offers `01.05` / `01.50`. Title IDs are normalised the same way
  (`cusa-05294` → `CUSA05294`). Versions are also stored in this form when cheats are saved or a profile is edited.
- **Export dialog**: title ID and version are editable there and checked while typing; it shows the resulting file
  name, the folder to copy it to on the PS4 (`/user/data/GoldHEN/cheats/json/`), how many mods are exported and what
  is skipped and why. Export is enabled only when the file is valid; problems are shown in the dialog. Corrected
  values are saved in the profile.

### Copy assembly
- Code screen: **Copy listing** (address, `eboot.bin+offset`, bytes, instruction, aligned in columns) and **Copy code**
  (instructions only, one per line) put the shown disassembly on the clipboard. The injection preview has the same
  buttons for the hook and cave code.

### Code caves / Assembly injection (new — verified against the simulated server only)
- **Code → tap an instruction → Inject code…** opens the injection builder: a jump at the instruction leads to a
  code cave holding your code, the overwritten instructions and a jump back. Where the original instructions run is
  selectable: after your code (default), before it, or not at all.
- The overwritten instructions are whole instructions (5 bytes for `jmp rel32`, 14 for an absolute jump when the cave
  is more than 2 GiB away) and are **moved and re-encoded for the cave** with iced's BlockEncoder: RIP-relative
  operands keep pointing at the same data, short branches become near ones. What cannot be moved is refused. Code that
  jumps back into the overwritten bytes is detected and the hook refused.
- **Caves in the game executable** (saveable): unused padding found by a bounded read-only search — page padding after
  the code first, then `int3` padding between functions, then long zero runs (marked as less certain); caves used by
  saved injections are skipped. **Allocated caves** (this session only): 16 KiB near the hook through ps4debug-NG's
  `CMD_PROC_ALLOC_HINTED`, after confirmation; since the server reports success even when the kernel call failed, the
  map is checked before and after, and an address that was in use or is not `rwx` is refused. Freed only on request,
  after the jump was removed.
- Writes go through the memory editor (read-only by default, confirmation, verified by a fresh read) in a safe order:
  the cave first (in ≤ 64-byte parts), the jump last; removal restores the jump first. A failed enable undoes what it
  wrote. Preview shows the hook bytes OFF/ON, the cave listing (new / moved / jump back) and warnings.
- **Save to Cheats**: one group (cave parts + jump), toggled from Cheats in that order (OFF in reverse) and exported as
  one GoldHEN mod. Library switches now also finish or undo a mod left half-applied by an interrupted toggle.
- Injections are listed for the session (check, OFF, free memory) and never re-applied automatically.
- Simulated server: `CMD_PROC_ALLOC`/`FREE`/`ALLOC_HINTED` with ps4debug-NG's failure behaviour, and the demo game
  now runs its routine with a small x86 interpreter, so tests see patched and injected code really execute.

### Register values in the disassembler
- Opening a watchpoint hit (live or saved) in the disassembler shows its registers: a "Registers at the hit" card
  with the registers the selected instruction uses and their values (sub-registers such as `eax`, `ax`, `al`, `ah`,
  `r8d` are taken from their 64-bit register and masked), the operand address computed from them and the value in
  memory there now; every instruction row shows the values of the registers it uses, the hit is marked ◆; all
  registers on demand. Values are those right after the hit instruction ran (watchpoints stop after the access).
- The decoder lists each instruction's registers as written (explicit operands, address registers, implicit ones
  such as `rsp` for `push`).

### Saving Assembly patches (fix)
- **Root cause:** on a real PS4 the game's code and data are mapped under the name `executable` (as PS4CheaterNeo
  reads them), not `eboot.bin`. The app only recognised file-style names (`.bin`, `.sprx`…), so code in the game
  was "not inside a module image": saving a patch failed — and the error was shown at the top of the Code screen while
  the form had already closed, so nothing appeared in Cheats. The simulated server used `eboot.bin`, which hid it.
- `executable` is now the game's main module everywhere: patches and value cheats are saved executable-relative,
  GoldHEN export/import and the library ON/OFF switches accept it as `eboot.bin`, references saved under either name
  resolve on both, and pointer scans treat the executable's data as static.
- Save flow: the patch and a new profile are written in one atomic step, the library file is read back to confirm
  the patch is stored before "saved" is reported, entries that would make the library unreadable at the next start
  are refused, and a save right after app start waits for the stored library instead of overwriting it.
- The save form stays open until the save is confirmed: errors are shown inside the form with the input kept, Save
  is disabled with a spinner while saving (no double saves); on success the form closes and Cheats updates by itself.

### Watchpoints without freezes (new in this build — verified against the simulated server only)
- **Capture once** (now the default): records the first instruction that writes/accesses the address with all
  registers, then removes the watchpoint and detaches — the game pauses exactly once. "Monitor" keeps the previous
  continuous behaviour.
- Freeze causes fixed in `DebugSession` / `AccessMonitor`: CONTINUE is checked (`CMD_ERROR` is retried once, then the
  session is ended); a failed DETACH is no longer ignored; debugger commands are never sent over a re-established
  connection; 3-second timeout per debugger command; attaching that is cancelled ends the session on the PS4.
- One verified cleanup for every way out (`DebugSession.release()`): CONTINUE for an unconfirmed stop, clear the
  watchpoint, drain and resume stops that arrived meanwhile, DETACH; if the connection was lost, DETACH again from a
  new connection (the server's debug state is global). The result is shown ("Game resumed · watchpoint removed ·
  debugger detached") and logged.
- Flood guard: more than 40 hits per second ends monitoring before the game stalls.
- Results are saved (newest 30) and open later in the disassembler without a debugger, also after a restart.
- Cases that cannot be guaranteed are documented in docs/WATCHPOINTS.md.

### Connection and interface
- The connection stays open in the background: a foreground service ("Connected to the PS4" notification with a
  Disconnect action) keeps Android from killing the app or cutting its network, and a Wi-Fi lock keeps the radio
  awake. Reconnect attempts after a drop: 5 with backoff (about 15 s) instead of 3. When the app returns to the
  foreground after the link was lost, it reconnects automatically (not after a manual Disconnect).
- Watchpoint screen redesigned: target, mode and session cards, status pills, clear release result, saved results.
  Connection screen shows a status pill and the capability level.

### Capability level 1.2 (verified against the simulated server only)
- The app reads ps4debug-NG's capability level from `CMD_BRANDING` (PR #1 commit `e5ce2bf` reports `1.2`, released
  builds `1.1`; compared numerically) and sends the 12-byte `CMD_DEBUG_ATTACH` only at level ≥ 1.2. Older payloads
  get no attach request at all — the watchpoint screen explains why and Start is disabled — so a failed
  connect-back can no longer leave the game attached. Diagnostics shows the level ("watchpoints ready").
- Mock: payload level selectable (`capability("1.1")` behaves like a released build).

### Watchpoints without root or relay (verified against the simulated server only)
- "What writes / accesses" uses the configurable interrupt port of ps4debug-NG
  ([Pharaoh2k/ps4debug-NG PR #1](https://github.com/Pharaoh2k/ps4debug-NG/pull/1), not merged upstream yet): the app
  listens on a high port (7550, or any free port) and sends the 12-byte `CMD_DEBUG_ATTACH {pid, client_ip,
  debug_port}`. Nothing to configure: the interrupt port and relay settings were removed.
- Older payloads ignore the port and connect to 755; the app also listens there when the system allows it. Otherwise
  the attach fails with an explanation (update the payload, restart the game — the payload may leave the game
  attached after a failed connect-back).
- Mock server emulates the extended attach (port 0 = 755, > 65535 rejected before attaching) and an unpatched
  payload, including the attachment it leaves behind.

### Code tools (verified against the simulated server only)
- **Disassembler** (Code, wrench icon; "Disassemble" in the memory viewer): x86-64 decoded on the phone (iced-x86,
  MIT) with addresses, `module+offset`, bytes, W/R/branch markers, branch following and "Code using it" (server-side
  reference search, `CMD_PROC_FIND_XREFS_TO`). ps4debug-NG's own disassembler sends no instruction text, so it is
  not used for display.
- **Assembly ON/OFF cheat builder**: new instructions are assembled **on the PS4** by ps4debug-NG's Keystone
  (`0xBDAA0024`) at the exact address; whole instructions are replaced and NOP-padded; the preview shows the original
  (OFF) and new (ON) bytes and warns about replaced control flow or several instructions. ON/OFF are only allowed
  from the opposite state, need confirmation and are verified by a fresh read. Hex bytes work without the assembler.
- Code patches are saved in the cheat library (assembly, ON/OFF bytes, optional group, AOB signature with wildcarded
  constants), switched in Cheats → "ON/OFF switches", and exported to GoldHEN: entries sharing a group become one mod;
  a group is skipped whole if any member cannot be exported; export is refused when the profile's title ID differs
  from the running game.
- **What writes / accesses this address** (address list, or from the disassembler): attaches ps4debug-NG's debugger,
  sets a hardware watchpoint (DR0), lists every instruction with hit count, thread and registers, resumes the game
  after every stop and detaches at the end (1000 hits / 2 minutes / Stop / leaving the screen)
  ([docs/WATCHPOINTS.md](https://github.com/Hm589158/ps4-SmartCheat/blob/main/docs/WATCHPOINTS.md)).
- **Check after restart** (Cheats): read-only check of the address list (module offsets, pointer chains), value cheats
  and code patches; when a patch's bytes changed, its signature is searched and the new offset is shown.
- **Pointer candidates**: save scan results per game and validate them after each restart against the confirmed
  address or the address most candidates agree on; ranked by how many restarts they survived.
- **Diagnostics**: game activity check (is the game running or stopped?), "Resume process" (SIGCONT), plain-text
  diagnostic report export, and explanations with next steps for connection, assembler and debugger errors.
- Mock server: assembler stand-in, reference search, debugger with watchpoints and interrupt channel, and a demo
  game routine with real x86-64 code that honours patches.

### Beta
- License: PS4 SmartCheat is now released under GPL-3.0 (README badge and credits updated; iced-x86 is MIT).
- **Beta.** Version 1.0.0-beta: feature-complete, every automated check green, hardware verification in progress.
  Builds are published as `v1.0.0-beta.<build>` ("PS4 SmartCheat v1.0.0 Beta · build N",
  `PS4-SmartCheat-v1.0.0-beta.<build>.apk`) and marked **Latest** (not pre-release), so the repository's Releases
  sidebar links to the current app build.
- ps4debug-NG is required for scanning and editing: the connection screen warns when a classic ps4debug is detected,
  the guide and About name the download, its author (OSR / Pharaoh2k) and license (GPL-3.0). Compatibility texts in
  the app now match the owner's hardware report instead of saying nothing was verified.
- README: "Required downloads" (app, ps4debug-NG with a direct link to the current payload file and its SHA-256),
  five-step setup (GoldHEN → ps4debug-NG → port 744), status table without contradictions, credits and GPL-3.0
  redistribution note. docs/COMPATIBILITY.md rewritten from the same facts.
- Connection diagnostics (Settings → Connection diagnostics): debugger, firmware, auth and Turbo engines, traffic,
  idle time, automatic reconnects, measured link, a 5-request latency test (CMD_VERSION only — no game memory is
  read) and the recent warnings/errors.
- In-app guide (ⓘ in the top bar): connect, find a value, edit safely, keep what you found, troubleshooting, and
  the offline-only / trusted-network notice.
- Client: the request lock is now taken and released on the IO dispatcher. Before, a request started from the UI
  thread released the lock only after resuming on that thread, so a busy UI thread could hold up every other request
  (found while investigating an intermittent E2E failure).
- Project presentation: new README with screenshots from the UI tests, banner and badges; issue
  forms (bug, hardware test report, feature), PR template, CONTRIBUTING and SECURITY policies.
- Releases: files are named `PS4-SmartCheat-v<version>-test.apk` (test builds) / `PS4-SmartCheat-v<version>.apk`
  (signed), titles read "PS4 SmartCheat v1.0.0 · Test build 46", and the notes (download table, install steps,
  what's new from this changelog, compatibility) are generated by `.github/scripts/release-notes.sh`. The manual
  "Tidy releases" workflow applies the same scheme to earlier releases without touching their APKs.
- Wide screens (≥ 720 dp: tablets, landscape phones) use a side navigation rail; content is limited to 900 dp.

## 0.10.0
- Memory viewer: patch raw bytes ("DE AD BE EF", up to 64) at the highlighted address — same policy, region checks,
  confirmation dialog (current vs new bytes) and fresh read-back verification as typed writes.
- Undo/redo of verified writes in this session (editor → History): each step is confirmed, shows the bytes in
  memory now and warns when the game changed them since; it is verified by a fresh read. History is per session,
  never persisted, never replayed automatically, and dropped when the game exits.
- Scan session save/resume: after every completed step the session (≤1 M results; snapshot-based sessions are not
  saved) is written to app storage. When the exact same game instance (pid, name, title ID, path) is selected
  after the app restarts, the Scanner offers "Resume"/"Discard". Resumed values are the ones from save time.
- GoldHEN cheat files can be opened in the cheat library ("Open GoldHEN file…", session only). Each checkbox mod
  is checked against the running game: title ID/process must match, and every patch must currently hold exactly
  its "off" bytes to be enabled (or "on" bytes to be disabled) — files for another game build are refused without
  writing. Enable/Disable shows every patch (address, current → new bytes) for confirmation and goes through the
  editor (policy, region checks, verification, undo history). Mods with "section", non-checkbox types, invalid or
  mismatched on/off bytes, or patches over 64 bytes are listed as not applicable.

## 0.9.0
- Address list → cheat library: "Save as cheat…" on an entry stores it with a value (module-relative when inside a
  module image, ready for GoldHEN export). Pointer-chain entries cannot become cheats (GoldHEN files hold fixed
  offsets only). Editor and address list share one save path.
- Pointer scan for big games: the pointer map is written to sorted run files in the app cache (memory-mapped,
  outside the Java heap) — up to 32 M pointers with ≈32 MiB of RAM while building, and up to 2 GiB of memory read.
  Run files are deleted after each scan, on failure/cancel, and at start-up. The screen shows how much memory the map
  will read before starting. Spilled and in-memory maps give identical chains (tested).
- Address list export/import (JSON, current game or all): imports merge by game, skip entries already present and
  reject foreign or newer files without changing anything.

## 0.8.0
- Address list per game (new "Addresses" tab): addresses are saved by title ID with a name and type and show
  live values (nearby entries are read in one request; unmapped entries are never read). Entries inside a module
  image are stored module-relative (e.g. `eboot.bin+0x100120`) and follow the game across restarts; absolute heap
  addresses are marked "from an earlier session — verify" outside the session they were added in, and cannot be
  frozen until checked in the editor. Add from a scan result (+), from the editor (+ List) or by address.
  Duplicates are not added. Nothing in the list is written to the console unless the user edits or freezes it.
- New scan modes "Bigger than", "Smaller than" and "Value between" (inclusive, either bound order) for first
  and next scans. They are compared on Android (no PS4-side compare code is used for them), so the scan falls
  back from Turbo/server tiers automatically and says so.
- Byte pattern (AOB) and text search (Scanner → "Byte pattern / text search…"): hex bytes with `??` and nibble
  wildcards, or UTF-8/ASCII and UTF-16LE text with optional ASCII case folding. Read-only; runs on Android with
  CMD_PROC_READ chunks; matches across chunk borders are found, matches never span two regions; hits open the
  memory viewer.
- Pointer scan (address list → entry → "Find pointers…"): builds a pointer map on Android from all readable,
  non-executable regions (read-only; limited by the snapshot size limit and "Max region size"), then searches
  backwards from the target to static module data, up to depth 1–5 and offsets up to 0x4000. After restarting the
  game, "Keep chains that lead there" filters the chains against the value's new address. Chains can be added to
  the address list; such entries are followed on every read, are marked "pointer chain broken" when a pointer
  leads to unmapped memory, and never trigger reads of unmapped memory. Pointer chains are app-only: they are not
  part of the GoldHEN export.
- Settings moved to the top bar to make room for the Addresses tab.

## 0.7.0
- CI: Robolectric's Android framework jar is resolved by Gradle and used offline (a Maven Central HTTP 429 at
  test time had failed the release gate).
- First hardware report (owner): FW 11.00 + GoldHEN + ps4debug-NG v1.3.3 — connect, auth, process list, Turbo Scan,
  refines and a write to a real game worked. Recorded in docs/COMPATIBILITY.md.
- Automatic reconnection after dropped/server-closed connections (exponential backoff, "Reconnecting" state).
  Reads are repeated once on the new session; writes and server scans are never repeated automatically.
- Keep-alive detects silently dropped links while idle.
- Game detection by title ID, executable path and name (eboot.bin, default.elf, …); NPXS system apps and Sce*
  daemons are classified as system. The running game is auto-selected after connecting (setting).
- Process search/filter (name, pid, title ID, path); classification shown per process.
- Game lifecycle: exit is detected (scan session closed, freeze stopped, stored originals discarded, scanning
  blocked); a restart of the same game under a new pid is picked up automatically with a fresh session.
- Structured diagnostics log (category + fields), exportable as JSON Lines from Settings.
- A closed session is shown as information ("Scan session closed: … exited"), not as a failure, and is cleared
  when another process is selected.

## 0.6.0
- End-to-end UI journey test (Robolectric + Roborazzi) drives the real app against the mock ps4debug-NG
  server and records screenshots of every step; CI publishes them to the `ci-screenshots` branch.
- UI polish from reviewing those screenshots: region shown under each scan result, no duplicated result count,
  memory viewer highlights the requested address and shows its value as Int8…Double, cheat profiles say
  "version not set" instead of "v?", Apply reads "Validate first" until the profile is validated.
- Save-as-cheat is an inline form in the editor (was a dialog that the keyboard could cover).
- Memory viewer: 256-byte hex/ASCII window around any address, paging, live refresh with highlighting of
  bytes that changed between reads (memory diff), unmapped bytes shown as `??` and never requested.
- Live values: optional periodic refresh of the visible scan-result page (interval in Settings).
- Export scan results as CSV (address, type, value, bytes, region).
- Setting to skip memory regions larger than N MiB during scans.
- Editor: "View memory" opens the viewer at the current address; viewer lines open the editor.
- Turbo: at most 100,000 survivors are copied to the phone per step (was up to 1,000,000); the full set stays on
  the PS4 for the next Turbo refine. For Int32 this bounds the fetch to ~1.6 MB instead of ~16 MB.

## 0.5.0-test.1 (pre-release, debug-signed)
- ps4debug-NG as primary backend with automatic CMD_PROC_AUTH (keystream verified against upstream C code).
- Turbo Scan (server-resident) → ps4debug-NG server-side scan → Android chunked scan, automatic fallback.
- Elapsed time, MiB/s, network bytes and backend shown during and after scans; benchmark in CI.
- Adaptive Android refine batching (measured RTT × bandwidth).

## 0.4.0
- MVP: connection, processes, memory map, scanner (6 types, exact/unknown initial + refine modes),
  verified memory editor with restore, client-side freeze, cheat library, JSON import/export, GoldHEN export.
