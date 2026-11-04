# ps4debug protocol (as implemented)

This file separates **verified facts** (read in upstream source code) from **assumptions**.
Only the commands listed under "Used by PS4 SmartCheat" are sent by the app.

## Sources inspected

| Source | What was used |
|---|---|
| `GoldHEN/ps4debug` | README only (repository ships `ps4debug.bin`, no source). Lists FW 11.00 as supported, v1.1.19. |
| `Pharaoh2k/ps4debug-NG` | `debugger/include/protocol.h`, `proc.h`, `debugger/source/server.c`, `proc.c`, `fw.c`, `console.c`, `PROTOCOL.md` |
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
* UDP 1010 discovery (`0xFFFFAAAA` echo) and TCP 755 debug-interrupt channel exist but are **not used**.

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
   user's own console. The auth flag is **server-global** (`g_proc_auth_bits`, reset only at payload start).
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

## Not implemented on purpose

Kernel read/write (`0xBDCC…`), RPC/ELF loading, debugger attach/breakpoints, console reboot, AOB scans, Turbo
snapshot (Phase B) scans.

## Limits applied by the client

* Reads are split into ≤ 1 MiB `CMD_PROC_READ` requests (configurable).
* Sanity limits: version string ≤ 256 B, ≤ 4096 processes, ≤ 1,000,000 map entries. A status word whose top
  nibble is not `0x8`/`0xF` is treated as stream desynchronisation and closes the connection.
* Hosts must be private/link-local/loopback/CGNAT addresses unless explicitly allowed in Settings.
