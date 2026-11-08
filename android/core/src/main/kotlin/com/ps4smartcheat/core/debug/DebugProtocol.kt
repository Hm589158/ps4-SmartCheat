package com.ps4smartcheat.core.debug

import com.ps4smartcheat.core.util.LE
import com.ps4smartcheat.core.util.cString

/**
 * ps4debug-NG debugger commands (0xBDBBxxxx). Verified in Pharaoh2k/ps4debug-ng v1.3.4
 * `debugger/include/protocol.h`, `debugger/include/debug.h`, `debugger/source/debug.c`, `debugger/source/server.c`
 * (see docs/PROTOCOL.md §Debugger). Breakpoints, register writes, single-stepping and kernel commands are not used.
 */
object DebugProtocol {
    const val CMD_DEBUG_ATTACH: Int = 0xBDBB0001.toInt()
    const val CMD_DEBUG_DETACH: Int = 0xBDBB0002.toInt()
    const val CMD_DEBUG_SET_WATCHPOINT: Int = 0xBDBB0004.toInt()
    const val CMD_DEBUG_GETREGS: Int = 0xBDBB0008.toInt()
    const val CMD_DEBUG_CONTINUE: Int = 0xBDBB0010.toInt()
    const val CMD_DEBUG_PROCESS_STOP: Int = 0xBDBB0500.toInt()

    /** `struct cmd_debug_attach_packet { u32 pid; u32 client_ip; }` — client_ip in network byte order. */
    const val ATTACH_PACKET_SIZE = 8
    /** `{u32 index; u32 enabled; u32 length; u32 breaktype; u64 address}` (debug.c `debug_set_watchpoint_handle`). */
    const val WATCHPOINT_PACKET_SIZE = 24
    /** `{u32 stop}`: 0 resumes (debug.c `debug_continue_handle`). */
    const val CONTINUE_PACKET_SIZE = 4
    /** `{u32 pid; u8 state}`: 0 = SIGCONT, 1 = SIGSTOP, 2 = SIGKILL when no debug session is active. */
    const val PROCESS_STOP_PACKET_SIZE = 5

    /** The server connects back to the client on this port when attaching (debug.h DEBUG_PORT). */
    const val DEFAULT_INTERRUPT_PORT = 755
    const val MAX_WATCHPOINTS = 4

    /** `struct debug_interrupt_packet` (packed): lwpid, status, tdname[40], __reg64, savefpu_ymm, __dbreg64. */
    const val INTERRUPT_PACKET_SIZE = 0x4A0
    const val REGS_OFFSET = 48
    /** sizeof(struct __reg64): 15 GPRs + trapno/fs/gs/err/es/ds + rip, cs, rflags, rsp, ss. The upstream doc says 184; the struct and GETREGS send 176. */
    const val REG64_SIZE = 176
    const val FPU_SIZE = 832
    const val DBREGS_OFFSET = REGS_OFFSET + REG64_SIZE + FPU_SIZE
    const val DBREGS_SIZE = 128

    /** DR7 R/W encodings (debug.h DBREG_DR7_*). */
    const val BREAK_EXEC = 0
    const val BREAK_WRITE = 1
    const val BREAK_READ_WRITE = 3

    /** DR7 LEN encoding: 1 → 0, 2 → 1, 8 → 2, 4 → 3 (debug.h DBREG_DR7_LEN_*). */
    fun lengthCode(size: Int): Int = when (size) {
        1 -> 0; 2 -> 1; 4 -> 3; 8 -> 2
        else -> throw IllegalArgumentException("watch size must be 1, 2, 4 or 8 bytes")
    }

    const val SIGTRAP = 5
}

enum class WatchType(val wire: Int, val label: String) {
    WRITES(DebugProtocol.BREAK_WRITE, "writes"),
    ACCESSES(DebugProtocol.BREAK_READ_WRITE, "reads or writes"),
}

/** General-purpose registers of a stopped thread (FreeBSD `struct reg` / ps4debug `__reg64`). */
class Registers(val values: Map<String, Long>) {
    val rip: Long get() = values.getValue("rip")
    val rsp: Long get() = values.getValue("rsp")
    val rbp: Long get() = values.getValue("rbp")
    operator fun get(name: String): Long? = values[name]

    /** The general-purpose registers in the usual display order. */
    val general: List<Pair<String, Long>> get() = DISPLAY.mapNotNull { n -> values[n]?.let { n to it } }

    companion object {
        private val GPR = listOf("r15", "r14", "r13", "r12", "r11", "r10", "r9", "r8", "rdi", "rsi", "rbp", "rbx", "rdx", "rcx", "rax")
        val DISPLAY = listOf("rax", "rbx", "rcx", "rdx", "rsi", "rdi", "rbp", "rsp", "r8", "r9", "r10", "r11", "r12", "r13", "r14", "r15", "rip", "rflags")

        fun parse(b: ByteArray, off: Int = 0): Registers {
            require(b.size - off >= DebugProtocol.REG64_SIZE) { "register block too short" }
            val m = LinkedHashMap<String, Long>()
            GPR.forEachIndexed { i, n -> m[n] = LE.i64(b, off + i * 8) }
            // trapno u32, fs u16, gs u16, err u32, es u16, ds u16 = 16 bytes, then rip, cs, rflags, rsp, ss
            val tail = off + 15 * 8 + 16
            m["rip"] = LE.i64(b, tail)
            m["cs"] = LE.i64(b, tail + 8)
            m["rflags"] = LE.i64(b, tail + 16)
            m["rsp"] = LE.i64(b, tail + 24)
            m["ss"] = LE.i64(b, tail + 32)
            return Registers(m)
        }
    }
}

/** One stop reported on the interrupt channel. The target is halted until [DebugSession.resume]. */
class InterruptEvent(
    val lwpid: Int,
    /** wait4() status: the stop signal is `(status >> 8) & 0xFF`. */
    val status: Int,
    val threadName: String,
    val registers: Registers,
    val debugRegisters: LongArray,
) {
    val signal: Int get() = (status ushr 8) and 0xFF
    val dr6: Long get() = debugRegisters[6]
    val dr7: Long get() = debugRegisters[7]
    /** Watchpoint slots that fired according to DR6 (B0..B3). */
    val firedSlots: Set<Int> get() = (0 until 4).filter { (dr6 ushr it) and 1L == 1L }.toSet()

    companion object {
        fun parse(p: ByteArray): InterruptEvent {
            require(p.size >= DebugProtocol.INTERRUPT_PACKET_SIZE) { "interrupt packet too short" }
            val dr = LongArray(16) { LE.i64(p, DebugProtocol.DBREGS_OFFSET + it * 8) }
            return InterruptEvent(LE.i32(p, 0), LE.i32(p, 4), p.cString(8, 40), Registers.parse(p, DebugProtocol.REGS_OFFSET), dr)
        }
    }
}
