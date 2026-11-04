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

## Not implemented on purpose

Kernel read/write (`0xBDCC…`), RPC/ELF loading, debugger attach/breakpoints, console reboot, and the
ps4debug-NG scan/turbo-scan family (requires a challenge/response whose keystream is intentionally undocumented).

## Limits applied by the client

* Reads are split into ≤ 1 MiB `CMD_PROC_READ` requests (configurable).
* Sanity limits: version string ≤ 256 B, ≤ 4096 processes, ≤ 1,000,000 map entries. A status word whose top
  nibble is not `0x8`/`0xF` is treated as stream desynchronisation and closes the connection.
* Hosts must be private/link-local/loopback/CGNAT addresses unless explicitly allowed in Settings.
