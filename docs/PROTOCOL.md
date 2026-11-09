# ps4debug-NG / ps4debug protocol (as implemented)

PS4 SmartCheat targets **ps4debug-NG** as its primary backend. The base commands below are shared with classic
(GoldHEN) ps4debug, which is supported in compatibility mode without server-side scanning.

This file separates **verified facts** (read in upstream source code) from **assumptions**.
Only the commands listed under "Used by PS4 SmartCheat" are sent by the app.

## Sources inspected

| Source | What was used |
|---|---|
| `GoldHEN/ps4debug` | README only (repository ships `ps4debug.bin`, no source). Lists FW 11.00 as supported, v1.1.19. |
| `Pharaoh2k/ps4debug-NG` | `debugger/include/protocol.h`, `proc.h`, `debug.h`, `debugger/source/server.c`, `proc.c`, `assemble.c`, `debug.c`, `fw.c`, `console.c`, `PROTOCOL.md` (v1.3.4, commit `8be828f`) |
| `avan06/PS4CheaterNeo` | `common/ps4dbg/libdebug/PS4DBG*.cs` (client library derived from jogolden/ps4debug), `Process.cs`, `Main.cs`, `CheatJson.cs`, `SectionTool.cs` |
| `GoldHEN/GoldHEN_Cheat_Repository` | 1881 JSON cheat files (schema survey) |
| `GoldHEN/GoldHEN_Plugins_SDK` | headers listed; not needed for the MVP (no console-side component yet) |

## Transport — verified

* TCP, port **744**. All integers **little-endian**, structs packed.
* Request header, 12 bytes: `u32 magic = 0xFFAABBCC`, `u32 cmd`, `u32 datalen`, followed by `datalen` body bytes.
* A header with a wrong magic is silently ignored by the server (no reply).
* Status words: `CMD_SUCCESS 0x80000000`, `CMD_ERROR 0xF0000001`, `CMD_TOO_MUCH_DATA 0xF0000002`,
  `CMD_DATA_NULL 0xF0000003`, `CMD_ALREADY_DEBUG 0xF0000004`, `CMD_INVALID_INDEX 0xF0000005`.
* **When a handler returns an error the server usually closes the connection** (`server.c`: `if (r) goto error`).
  The client therefore drops the socket after an error status and reconnects transparently on the next request.
* UDP 1010 discovery (`0xFFFFAAAA` echo) exists but is **not used**. The TCP debug-interrupt channel (755 by default,
  a client-chosen port with PR #1) is used only by "What writes / accesses" (see *Debugger*).

## Used by PS4 SmartCheat — verified layouts

| Command | ID | Request body | Response |
|---|---|---|---|
| `CMD_VERSION` | `0xBD000001` | none | **no status word**; `u32 len` + ASCII string (NG: `"1.3"`) |
| `CMD_FW_VERSION` | `0xBD000500` | none | **no status word**; `u16` = major*100+minor (e.g. 1100) — extension, see below |
| `CMD_PROC_LIST` | `0xBDAA0001` | none | status; `u32 n`; n × `{char name[32]; i32 pid}` (36 B). No processes → `CMD_DATA_NULL` + close |
| `CMD_PROC_READ` | `0xBDAA0002` | `u32 pid, u64 addr, u32 len` (16 B) | status; then exactly `len` bytes |
| `CMD_PROC_WRITE` | `0xBDAA0003` | `u32 pid, u64 addr, u32 len` (16 B) | status (ready); client sends `len` bytes; final status |
| `CMD_PROC_MAPS` | `0xBDAA0004` | `u32 pid` | status; `u32 n`; n × `{char name[32]; u64 start; u64 end; u64 offset; u16 prot}` (**58 B**) |
| `CMD_PROC_INFO` | `0xBDAA000A` | `u32 pid` | status; `{u32 pid; char name[40]; char path[64]; char titleid[16]; char contentid[64]}` (188 B) |
| `CMD_CONSOLE_END` | `0xBDDD0002` | none | none; server closes the client |

Notes (verified in source):

* `CMD_PROC_READ` **never reports read failures**: the server zero-fills its 64 KiB buffer and ignores the
  `sys_proc_rw` result. Unmapped memory reads as zeros. The app only reads inside mapped, readable regions.
* `CMD_PROC_WRITE` in ps4debug-NG ≥ capability 1.1 verifies by read-back and returns `CMD_ERROR` on mismatch
  (connection stays open). The classic server's behaviour could not be inspected (GoldHEN ships only a binary),
  so the app **always** verifies writes with its own fresh `CMD_PROC_READ`.
* ps4debug-NG's `PROTOCOL.md` describes map entries as 64 bytes with `prot` as `u64`; the actual source
  (`proc.h`, `struct proc_vm_map_entry`) and the PS4CheaterNeo client use **58 bytes** with `u16 prot`. The source wins.
* `prot` uses FreeBSD `VM_PROT_*`: READ=1, WRITE=2, EXECUTE=4.
* Map entry **names** come from the PS4 kernel: the game's own code and data are named `executable`, libraries by
  file name (`libkernel.sprx`…), the rest anonymous. Source: PS4CheaterNeo (`SectionTool.cs` sorts `executable` first,
  `Main.cs` looks the code up as section `"executable"` with prot 5, `PointerFinder.cs` uses it as the base). The app
  treats `executable` as the process's main module (`eboot.bin` in profiles and GoldHEN files); module offsets are
  relative to its lowest mapping. The simulated server names it `eboot.bin`; both are accepted.
* `CMD_PROC_INFO` answers success even for unknown pids (fields from a zeroed struct).

## Firmware detection

* `CMD_FW_VERSION` is implemented in ps4debug-NG (`fw.c`: BCD from `sceKernelGetSystemSwVersion`, converted to
  decimal major*100+minor; `0xFFFF` on failure) and, per PS4CheaterNeo, in GoldHEN's ps4debug ("ext fw by ctn123").
* **Assumption:** a server without the extension sends nothing (ps4debug-NG's dispatcher returns 0 without a reply
  for unknown commands in the `0xBD` namespace). The client probes with a 1.5 s timeout and, on timeout,
  **reopens the socket** so a late reply can never desynchronise the stream. Firmware is shown as "unknown" then.
  It can be disabled in Settings.

## Server-side scanning (ps4debug-NG) — verified in source

Detection on connect (each probe uses a short timeout; on timeout/EOF the socket is reopened):

1. `CMD_BRANDING 0xBD000501` → no status; `u32 len` + text, NUL, capability level (NG: `"ps4debug-NG by OSR v1.3.3\0" "1.1"`).
   GoldHEN's ps4debug answers the same opcode (PS4CheaterNeo "CMD_EXT_DBG_VERSION"). **Server-side scanning is only
   attempted when the brand starts with `ps4debug-NG`**, because GoldHEN's ps4debug also defines opcodes in
   `0xBDAACCxx` (PS4CheaterNeo lists `CMD_PROC_SCAN_START/NEXT/END/FF`) whose semantics could not be inspected.
2. `CMD_PROC_AUTH 0xBDAACCFF`, body `{u32 magic=0xBB40E64D; u32 flags=2}` → `CMD_SUCCESS`, `u16 len(=64)`,
   64 challenge bytes; client answers `challenge XOR keystream`; server → `CMD_SUCCESS` or `CMD_DATA_NULL`+close.
   The keystream is L'Ecuyer's combined Tausworthe generator (lfsr113) re-seeded with 200/300/400/500 for every
   handshake, low byte per step (`proc.c` `auth_lfsr_next`, `proc_auth_xor_keystream`). ps4debug-NG's PROTOCOL.md calls
   it "intentionally undocumented"; it is implemented here from the GPL source for interoperability with the
   user's own console.
   * **Verification:** the two upstream C functions were compiled verbatim with gcc and their output is pinned as test
     vectors in `NgAuthTest` (all-zero and ramp challenges).
   * **Challenge:** 64 bytes from the server's `rand()`; the client never needs to predict it.
   * **Permissions:** the client requests `flags = 0x2`. Only bit `0x2` is checked anywhere (`SCAN_REQUIRE_AUTH`
     in proc.c and every Turbo handler except CAPS); bit `0x1` is stored but never tested.
   * **Scope:** `g_proc_auth_bits` is **server-global** and only reset when the payload starts (`server.c`), not per
     connection as ps4debug-NG's PROTOCOL.md §1.8 states; `free_client` does not touch it. The client still re-runs
     the handshake after every reconnect so a restarted payload is re-authorised automatically.
   * **Failure:** `CMD_DATA_NULL` and the server closes the connection; the client reopens it and marks scanning as
     unauthorised (scans then run on Android).
3. `TURBO_CAPS 0xBDAACC10` (no auth) → `CMD_SUCCESS` + `{u32 version; u32 engines; u32 max_threads; u32 reserved}`.
   Release build reports `engines = 0x7FF`; `TSE_SERVER_RESIDENT = 0x4` is required for the Turbo tier.

### Standard server-side scan (iterative, auth-gated)

* `CMD_PROC_SCAN_START 0xBDAACC01`, body 23 B `{u32 pid; u64 addr; u32 len; u8 valueType; u8 compareType; u8 align; u32 lenData}`
  → `CMD_SUCCESS`; client sends `lenData` value bytes; → `CMD_SUCCESS`; then batches `{u64 batchLen; batchLen bytes of
  (u32 offset-from-addr, value)}` (batchLen ≤ 0x10000) until the 8-byte all-ones sentinel; then `CMD_SUCCESS`.
  `len` is u32, so the client issues one request per ≤ 64 MiB slice (also bounds cancellation latency).
* `CMD_PROC_SCAN_COUNT 0xBDAACC02`, body 18 B `{u32 pid; u64 base; u8 vt; u8 ct; u32 lenData}` → `CMD_SUCCESS`;
  value bytes if needed; then the client streams chunks `{u32 chunkLen; entries}` (≤ 0x20000; entry = `u32 offset`
  [+ previous value for increased/decreased/changed/unchanged]) and after each chunk reads result batches until a
  sentinel; `0xFFFFFFFF` ends the command → `CMD_SUCCESS`.
  Its read window check (`addr >= windowEnd`) can straddle a window edge for unaligned candidates, so only
  naturally aligned scans use it.
* No cancel command exists for these; the client cancels between slices/chunks.

### Turbo Scan (ps4debug-NG ≥ 1.3.0, auth-gated)

* `TURBO_START 0xBDAACC11`, body 27 B (SCAN_START + `u32 flags`). Used with
  `TS_SERVER_RESIDENT (0x2) | TS_SNAPSHOT_SEGMENTS (0x10)` [+ `TS_USE_ALIASING (0x1)` if enabled in Settings]:
  → `CMD_SUCCESS`; value bytes; → `CMD_SUCCESS`; client sends `u32 nseg` + nseg × `{u64 addr; u32 len}`;
  server scans silently, then `{u32 resident_stored; u64 count}`; if stored = 0 (survivor buffer > 256 MiB) a
  sentinel follows; then `CMD_SUCCESS`. The client falls back to the iterative scan when stored = 0.
* `TURBO_COUNT 0xBDAACC12`, body 22 B (SCAN_COUNT + `u32 flags=TS_SERVER_RESIDENT [|TS_RESCAN_ALIASING]`) →
  `CMD_SUCCESS`; value bytes if needed; 8-byte sentinel; `u64 newCount`; `CMD_SUCCESS`. Narrows the resident set
  using each record's stored value as "previous".
* `TURBO_GET 0xBDAACC13`, body `{u32 start; u32 count; u32 flags}` → `CMD_SUCCESS`; `u32 hdr` (low 31 bits =
  records, bit 31 = 3-value records); records `{u64 addr; value current; value previous}`; `CMD_SUCCESS`.
* `TURBO_END 0xBDAACC14` frees the resident set; `TURBO_CANCEL 0xBDAACC17` (body `u32 pid`) must be sent on a
  **second connection** because the scanning connection is busy; the session is indeterminate afterwards and is ended.
* The resident set lives per connection slot: a reconnect loses it (the client tracks a connection epoch and
  falls back to local results).

### Value semantics

Integer compares are typed (signed for the signed `valueType` codes 1/3/5/7 used here). Float/double exact
compares use NG's fuzzy policy, which differs from this app's rounded matching, so floats are never offloaded.

### Classic `CMD_PROC_SCAN 0xBDAA0009` — not used

In jogolden's ps4debug source the loop calls `sys_proc_rw(sp->pid, sectionStartAddr, …)` for every page, i.e. it
re-reads the first page of each section, so results beyond the first page are wrong; it also scans every readable
mapping, sends one 8-byte packet per match, has no result cap and cannot be cancelled. GoldHEN's build is binary-only,
so whether it is fixed cannot be verified. It is therefore not used.

## Code tools (ps4debug-NG) — verified in source

### Assemble `0xBDAA0024` (raw literal; `assemble.c` `proc_assemble_handle`, dispatched in `proc.c` `proc_handle`)

* Request body: `u64 base_addr; u32 ks_opt_syntax;` (12 bytes) followed by the assembly text (no NUL needed).
  `base_addr` is the address the code will run at (Keystone resolves RIP-relative operands and branches against it).
  The app sends syntax `0` (Keystone default, Intel) and the text one statement per line.
* Success: `CMD_SUCCESS`, `u32 byte_len; u32 insn_count;`, then `byte_len` machine-code bytes.
* Error: `CMD_ERROR`, `u32 ks_errno; u32 msg_len;`, then the Keystone message; the handler returns 1, so the server
  **closes the connection** (the client reconnects). A build without the command answers `CMD_ERROR` alone and
  closes (`proc_handle` default) — reported as "update ps4debug-NG". Needs no attached process and no auth.

### Find references `CMD_PROC_FIND_XREFS_TO = 0xBDAA0022` (`proc.c` `proc_find_xrefs_to_handle`)

* Request body (24 bytes): `u32 pid; u64 scan_address; u32 scan_length; u64 target_address;`.
* Reply as implemented: `CMD_SUCCESS`, then a stream of `u64` instruction addresses whose RIP-relative memory operand
  resolves to `target_address`, ended by `0xFFFFFFFFFFFFFFFF`. **Upstream `PROTOCOL.md` describes a count-prefixed
  `(u64, u8 kind)` list; the code sends the sentinel-terminated stream above, which is what the app reads.**
  The server sweeps linearly with Zydis and skips one byte on undecodable data.

### Disassembly `CMD_PROC_DISASM_REGION = 0xBDAA0020` — verified, not used

Returns 32-byte metadata records per instruction (`addr, rip_rel_target, mem_disp, length, kind, mem_base_reg,
mem_index_reg, mem_scale, mnemonic_lo`) ended by a 32-byte `0xFF` sentinel — **no mnemonic or operand text**.
The app therefore decodes and formats instructions on the phone (iced-x86, MIT) from bytes read with
`CMD_PROC_READ`; boundaries before an address are found by re-synchronising a linear sweep.

## Debugger (ps4debug-NG) — verified in source

Used only by "What writes / accesses this address". Only the commands below are sent; breakpoints, register
writes, single-stepping and FS/GS writes are not used.

| Command | Body | Reply | Source |
|---|---|---|---|
| `CMD_DEBUG_ATTACH 0xBDBB0001` | `u32 pid; u32 client_ip` (network byte order); **sent as** `u32 pid; u32 client_ip; u32 debug_port` (12 bytes, PR #1) | `CMD_SUCCESS` / `CMD_ALREADY_DEBUG` / `CMD_ERROR` (closes) | `debug.c` `debug_attach_handle_svc`, routed in `server.c`; `debug_port.h` (PR #1) |
| `CMD_DEBUG_DETACH 0xBDBB0002` | — | `CMD_SUCCESS` | `debug.c` `debug_detach_handle` → `debug_full_teardown` |
| `CMD_DEBUG_SET_WATCHPOINT 0xBDBB0004` | `u32 index (0-3); u32 enabled; u32 length; u32 breaktype; u64 address` | `CMD_SUCCESS` / `CMD_INVALID_INDEX` | `debug.c` `debug_set_watchpoint_handle` |
| `CMD_DEBUG_CONTINUE 0xBDBB0010` | `u32 stop` (0 = resume) | `CMD_SUCCESS` | `debug.c` `debug_continue_handle` |
| `CMD_DEBUG_PROCESS_STOP 0xBDBB0500` | `u32 pid; u8 state` (0 resume) | `CMD_SUCCESS` | `debug.c` `debug_process_stop_handle` |

* **Attach order.** The server ptrace-attaches, continues the process, then opens a TCP connection **from the PS4
  to `client_ip:755`** (`connect_debugger`) and only then answers `CMD_SUCCESS`. The listener must therefore exist
  before the request. If that connection fails the server answers `CMD_ERROR` but the process stays ptrace-attached
  without a session (upstream behaviour, unchanged by PR #1) — the app only sends ATTACH after its listener is open,
  only in a form the payload's capability level supports, and tells the user to restart the game if it still failed.
* **Configurable interrupt port — [Pharaoh2k/ps4debug-NG PR #1](https://github.com/Pharaoh2k/ps4debug-NG/pull/1),
  commits `5a19d63` and `e5ce2bf`, not merged upstream yet.** `struct cmd_debug_attach_ex_packet {u32 pid; u32 client_ip; u32
  debug_port;}` (12 bytes, little-endian, `debug_port` in host order). `debug_attach_resolve_port` in
  `debug_port.h`: body < 12 bytes → 755; `debug_port` 0 → 755; 1..65535 → that port; > 65535 → `CMD_ERROR`
  **before** `PT_ATTACH`. Example from the PR: pid `0x55`, client `192.168.1.10`, port 7550 →
  `55 00 00 00 C0 A8 01 0A 7E 1D 00 00`.
* **Capability gate (PR #1 commit `e5ce2bf`, `capability.h`).** The `CMD_BRANDING` reply's level after the NUL is
  `PACKET_CAPABILITY_LEVEL` = `"1.2"` (released builds: `"1.1"`); `CAPABILITY_DEBUG_ATTACH_PORT` = `"1.2"`. Levels
  are compared numerically as `major.minor` (`1.10` > `1.2`). The app sends the 12-byte form with the port it
  listens on (7550, or any free port) only at level ≥ 1.2. Below that it sends the legacy 8-byte form only if it
  could also open 755, and otherwise sends no attach at all (so a failed connect-back can never leave the game
  attached).
* **Watchpoint encoding.** `length` is the DR7 LEN code (`1→0, 2→1, 8→2, 4→3`, `debug.h DBREG_DR7_LEN_*`) and
  `breaktype` the DR7 R/W code (`EXEC 0, WRONLY 1, RDWR 3`); the server ORs `breaktype | 4*length` into DR7 for the
  slot and applies DR0-3/DR7 to every thread. Addresses must be aligned to the length (x86 rule).
* **Interrupt packet** (`debug.h struct debug_interrupt_packet`, packed, `0x4A0` bytes): `u32 lwpid; u32 status;
  char tdname[40]; __reg64 (176 bytes); savefpu_ymm (832 bytes); __dbreg64 (16 × u64)`. `__reg64` is 176 bytes —
  15 GPRs (r15…rax), `u32 trapno, u16 fs, u16 gs, u32 err, u16 es, u16 ds`, then `rip, cs, rflags, rsp, ss`;
  `CMD_DEBUG_GETREGS` sends 176 bytes. (Upstream `PROTOCOL.md` says 184.) `status` is the wait4 status
  (signal = `(status >> 8) & 0xFF`, SIGTRAP = 5); DR6 bits 0-3 tell which slot fired.
* **Stops.** Events are produced by the attaching client's server thread while its socket is idle
  (`dispatch_debug_events`). After an event the process stays stopped until `CMD_DEBUG_CONTINUE`; the app resumes
  immediately and decodes the instruction afterwards. Data watchpoints trap **after** the access, so the reported
  RIP is the next instruction; the app decodes the instruction that ends at RIP.
* **Session end.** `DETACH`, or closing the command connection (`server.c free_client` → `debug_full_teardown`:
  breakpoints restored, debug registers cleared, `PT_DETACH`). Only one session can exist (`CMD_ALREADY_DEBUG`).
* **Global debug state.** The handlers check the global `g_debug_ctx.attached`, not the calling client, so `DETACH`
  (and `CONTINUE`) work from any connection; `debug_detach_handle` tears down `curdbgcli`'s session. The app uses
  this to release a session the PS4 still holds after the app's connection was lost. `CONTINUE` answers `CMD_ERROR`
  when `PT_CONTINUE` fails (e.g. the process is not stopped) and keeps the connection (handler returns 0); `DETACH`
  without a session answers `CMD_ERROR` and closes it.
* **Resume a stopped process.** Without a session, `CMD_DEBUG_PROCESS_STOP` with state 0 sends `SIGCONT` (signal 19
  on FreeBSD) to the pid — offered in Diagnostics when a game looks frozen.

## Not implemented on purpose

Kernel read/write (`0xBDCC…`), RPC/ELF loading, memory allocation/protection changes, breakpoints, register
writes, single-stepping, server-side AOB scans (`0xBDAA0501/0502`; pattern search runs on the phone), console
reboot, Turbo snapshot (Phase B) scans.

## Limits applied by the client

* Reads are split into ≤ 1 MiB `CMD_PROC_READ` requests (configurable).
* Sanity limits: version string ≤ 256 B, ≤ 4096 processes, ≤ 1,000,000 map entries. A status word whose top
  nibble is not `0x8`/`0xF` is treated as stream desynchronisation and closes the connection.
* Hosts must be private/link-local/loopback/CGNAT addresses unless explicitly allowed in Settings.
