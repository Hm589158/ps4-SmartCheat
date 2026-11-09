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
    /** Callers at the hit (empty when not captured). */
    val callStack: List<StackFrame> = emptyList(),
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
            RegisterSnapshot(r.registers.values, r.instructionAddress, r.threadName, r.lastSeenMillis, fromSaved = false, callStack = r.callStack.orEmpty())

        /** From a saved session; [instructionAddress] is where the instruction is now (it may have moved after a restart). */
        fun of(a: SavedAccess, instructionAddress: Long, capturedAtMillis: Long): RegisterSnapshot = RegisterSnapshot(
            a.registers.mapNotNull { (k, v) -> v.removePrefix("0x").removePrefix("0X").toULongOrNull(16)?.let { k.lowercase() to it.toLong() } }.toMap(),
            instructionAddress, a.thread, capturedAtMillis, fromSaved = true, callStack = a.callStack,
        )
    }
}

/** Register values as text: hexadecimal padded to the register's width, plus decimal. */
object RegisterFormat {
    /** `0x00001387` for 32 bits, `0x0000000000001387` for 64. */
    fun hex(value: Long, bits: Int): String = "0x" + java.lang.Long.toHexString(mask(value, bits)).uppercase().padStart(bits / 4, '0')

    /** Unsigned decimal of the [bits]-wide value, plus the signed value when the top bit is set (`4294967295 / -1`). */
    fun decimal(value: Long, bits: Int): String {
        val v = mask(value, bits)
        val unsigned = if (bits == 64) java.lang.Long.toUnsignedString(v) else v.toString()
        val negative = bits in 1..64 && (v ushr (bits - 1)) and 1L == 1L
        if (!negative) return unsigned
        val signed = if (bits == 64) v else v - (1L shl bits)
        return "$unsigned / $signed"
    }

    /** `0x00001387 (4999)`. */
    fun both(value: Long, bits: Int): String = hex(value, bits) + " (" + decimal(value, bits) + ")"

    /** `eax   = 0x00001387 (4999)`. */
    fun line(name: String, value: Long, bits: Int, width: Int = 6): String = name.padEnd(width) + "= " + both(value, bits)

    private fun mask(v: Long, bits: Int): Long = if (bits >= 64) v else v and ((1L shl bits) - 1)
}
