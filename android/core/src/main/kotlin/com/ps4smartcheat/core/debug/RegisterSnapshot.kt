package com.ps4smartcheat.core.debug

import com.ps4smartcheat.core.asm.AsmInstruction
import com.ps4smartcheat.core.asm.X86

/**
 * General-purpose register values captured at a watchpoint hit, for showing next to the disassembly. They are the
 * values right after the trapping instruction ran (data watchpoints trap after the access); other instructions may
 * have seen different values.
 */
data class RegisterSnapshot(
    /** 64-bit registers by lower-case name (`rax`…`r15`, `rsp`, `rbp`, `rip`, `rflags`). */
    val values: Map<String, Long>,
    /** The instruction that hit the watchpoint. */
    val instructionAddress: Long,
    val thread: String,
    val capturedAtMillis: Long,
    /** True when read back from a saved session (not the live debugger). */
    val fromSaved: Boolean,
) {
    /** One register an instruction uses: its name as written and its value (masked to the register's width). */
    data class Use(val name: String, val value: Long, val bits: Int)

    /** Values of the registers [ins] uses that are in the snapshot. */
    fun usedBy(ins: AsmInstruction): List<Use> = ins.registers.mapNotNull { n ->
        X86.registerValue(n, values)?.let { Use(n, it, X86.registerBits(n)) }
    }

    /** Address of [ins]'s memory operand computed from the snapshot, or null. */
    fun effectiveAddress(ins: AsmInstruction): Long? = X86.effectiveAddress(ins, values)

    companion object {
        fun of(r: AccessRecord): RegisterSnapshot =
            RegisterSnapshot(r.registers.values, r.instructionAddress, r.threadName, r.lastSeenMillis, fromSaved = false)

        /** From a saved session; [instructionAddress] is where the instruction is now (it may have moved after a restart). */
        fun of(a: SavedAccess, instructionAddress: Long, capturedAtMillis: Long): RegisterSnapshot = RegisterSnapshot(
            a.registers.mapNotNull { (k, v) -> v.removePrefix("0x").removePrefix("0X").toULongOrNull(16)?.let { k.lowercase() to it.toLong() } }.toMap(),
            instructionAddress, a.thread, capturedAtMillis, fromSaved = true,
        )
    }
}
