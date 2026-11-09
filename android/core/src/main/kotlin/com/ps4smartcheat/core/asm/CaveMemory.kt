package com.ps4smartcheat.core.asm

import com.ps4smartcheat.core.backend.CommandFailedException
import com.ps4smartcheat.core.backend.DebugBackend
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.protocol.Ps4DebugProtocol
import com.ps4smartcheat.core.protocol.ServerScanTransport
import com.ps4smartcheat.core.util.Address
import com.ps4smartcheat.core.util.LE

/**
 * ps4debug-NG process memory allocation. Verified in Pharaoh2k/ps4debug-ng v1.3.4 (`8be828f`), see docs/PROTOCOL.md:
 *  - `CMD_PROC_ALLOC = 0xBDAA000B`, body `{u32 pid; u32 length}` (debugger/source/proc.c `proc_alloc_handle`)
 *  - `CMD_PROC_FREE = 0xBDAA000C`, body `{u32 pid; u64 address; u32 length}` (`proc_free_handle`)
 *  - `CMD_PROC_ALLOC_HINTED = 0xBDAA000E`, body `{u32 pid; u64 hint; u32 length}` (`proc_alloc_hinted_handle`)
 *
 * Replies are CMD_SUCCESS (+ u64 address for the two allocations) **even when the kernel call failed**: the handlers
 * ignore `sys_proc_cmd`'s result. On failure CMD_PROC_ALLOC returns an uninitialised address and
 * CMD_PROC_ALLOC_HINTED returns the hint itself (`args.address` is the hint on input). The kernel side
 * (kdebugger/source/hooks.c `sys_proc_alloc_hinted_handle`) maps `length` rounded up to 16 KiB with VM_PROT_ALL at
 * the first free address at or above the hint (`vm_map_findspace`). So the reply is never trusted: [CaveAllocator]
 * checks the process map before and after.
 */
object NgAllocProtocol {
    const val CMD_PROC_ALLOC: Int = 0xBDAA000B.toInt()
    const val CMD_PROC_FREE: Int = 0xBDAA000C.toInt()
    const val CMD_PROC_ALLOC_HINTED: Int = 0xBDAA000E.toInt()
    const val ALLOC_PACKET_SIZE = 8
    const val ALLOC_HINTED_PACKET_SIZE = 16
    const val FREE_PACKET_SIZE = 16
    /** Kernel rounding (hooks.c / proc.c `alignedSize`). */
    const val PAGE = 0x4000L
}

/** Memory this app allocated in a game process for a code cave. Never freed or re-allocated automatically. */
data class CaveAllocation(val pid: Int, val address: Long, val length: Int, val mappedSize: Long)

class CaveAllocationException(message: String) : Exception(message)

/**
 * Allocates executable memory near an address for a code cave, validating every result against the process map:
 * the range must have been unmapped before and be mapped readable, writable and executable afterwards. Anything else
 * (e.g. the server echoing the hint, which would point into existing game memory) is refused.
 */
class CaveAllocator(private val transport: ServerScanTransport, private val backend: DebugBackend) {

    val available: Boolean get() = transport.capabilities.isNg

    /**
     * Allocates [length] bytes (1..[MAX_ALLOCATION]) at the first free address at or above [hint] (CMD_PROC_ALLOC_HINTED),
     * so a 5-byte `jmp rel32` from code near [hint] can reach it.
     */
    suspend fun allocateNear(pid: Int, hint: Long, length: Int): CaveAllocation {
        if (!available) throw CodeToolUnavailableException("Allocating memory needs ps4debug-NG.")
        if (length !in 1..MAX_ALLOCATION) throw CaveAllocationException("A code cave allocation is limited to 1..$MAX_ALLOCATION bytes.")
        val before = backend.getMemoryRegions(pid)
        val address = transport.exclusive("allocate memory") { w ->
            w.send(
                w.header(NgAllocProtocol.CMD_PROC_ALLOC_HINTED, NgAllocProtocol.ALLOC_HINTED_PACKET_SIZE),
                LE.bytes32(pid.toLong()) + LE.bytes64(hint) + LE.bytes32(length.toLong()),
            )
            val status = w.readU32()
            if (status != Ps4DebugProtocol.CMD_SUCCESS) {
                w.invalidate()
                if (status == Ps4DebugProtocol.CMD_ERROR) throw CodeToolUnavailableException(CodeTools.UNAVAILABLE)
                w.expectSuccess(NgAllocProtocol.CMD_PROC_ALLOC_HINTED, status)
            }
            w.readU64()
        }
        val after = backend.getMemoryRegions(pid)
        val problem = validate(address, length, before, after)
        if (problem != null) throw CaveAllocationException("The PS4 did not allocate memory ($problem). Nothing was written.")
        val region = after.first { it.contains(address, length) }
        return CaveAllocation(pid, address, length, region.end - address)
    }

    /**
     * Frees an allocation (CMD_PROC_FREE) and checks that it is gone from the map. Only call this after the jump into
     * the cave was removed and verified: a thread still running the cave would crash.
     */
    suspend fun free(a: CaveAllocation) {
        if (!available) throw CodeToolUnavailableException("Freeing memory needs ps4debug-NG.")
        transport.exclusive("free memory") { w ->
            w.send(
                w.header(NgAllocProtocol.CMD_PROC_FREE, NgAllocProtocol.FREE_PACKET_SIZE),
                LE.bytes32(a.pid.toLong()) + LE.bytes64(a.address) + LE.bytes32(a.length.toLong()),
            )
            val status = w.readU32()
            if (status != Ps4DebugProtocol.CMD_SUCCESS) {
                w.invalidate()
                w.expectSuccess(NgAllocProtocol.CMD_PROC_FREE, status)
            }
        }
        if (backend.getMemoryRegions(a.pid).any { overlaps(it, a.address, a.length.toLong()) }) {
            throw CaveAllocationException("The PS4 replied OK but ${Address.format(a.address)} is still mapped (not freed).")
        }
    }

    companion object {
        /** One kernel page: plenty for injected code, small enough to never matter to the game. */
        const val MAX_ALLOCATION = 0x4000

        private fun overlaps(r: MemoryRegion, start: Long, len: Long): Boolean =
            r.isValid && Address.lt(r.start, start + len) && Address.lt(start, r.end)

        /** Null when [address] is a fresh RWX mapping of at least [length] bytes; otherwise the reason. */
        fun validate(address: Long, length: Int, before: List<MemoryRegion>, after: List<MemoryRegion>): String? {
            if (address == 0L || address == -1L) return "no address returned"
            if (address % NgAllocProtocol.PAGE != 0L) return "address ${Address.format(address)} is not page aligned"
            if (before.any { overlaps(it, address, length.toLong()) }) return "${Address.format(address)} was already in use"
            val r = after.firstOrNull { it.contains(address, length) } ?: return "${Address.format(address)} is not mapped"
            if (!r.isReadable || !r.isWritable || !r.isExecutable) return "the new memory is ${r.protString()}, not rwx"
            return null
        }
    }
}
