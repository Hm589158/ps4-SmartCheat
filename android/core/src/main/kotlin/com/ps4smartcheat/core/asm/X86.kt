package com.ps4smartcheat.core.asm

import com.github.icedland.iced.x86.Code
import com.github.icedland.iced.x86.FlowControl
import com.github.icedland.iced.x86.Instruction
import com.github.icedland.iced.x86.Register
import com.github.icedland.iced.x86.dec.Decoder
import com.github.icedland.iced.x86.fmt.StringOutput
import com.github.icedland.iced.x86.fmt.intel.IntelFormatter
import com.github.icedland.iced.x86.info.InstructionInfoFactory
import com.github.icedland.iced.x86.info.OpAccess

/** How an instruction affects control flow. */
enum class Flow { NEXT, CALL, RETURN, JUMP, CONDITIONAL_JUMP, INTERRUPT, OTHER }

/** The (first) explicit memory operand of an instruction. */
data class MemoryAccess(
    val read: Boolean,
    val write: Boolean,
    /** Register names in lower case ("rax"), null when absent. */
    val base: String?,
    val index: String?,
    val scale: Int,
    val displacement: Long,
    /** Operand size in bytes (0 when not applicable, e.g. `lea`). */
    val size: Int,
)

/**
 * One decoded x86-64 instruction. [valid] is false for bytes that do not decode; such rows carry one byte so a
 * listing never stalls.
 */
class AsmInstruction(
    val address: Long,
    val bytes: ByteArray,
    /** Intel syntax, e.g. `mov dword ptr [rbx+0x1C], eax`. */
    val text: String,
    val mnemonic: String,
    val flow: Flow,
    /** Target of a direct call/jump. */
    val branchTarget: Long?,
    /** Absolute address referenced by a RIP-relative memory operand. */
    val ripTarget: Long?,
    val memory: MemoryAccess?,
    val valid: Boolean,
    /** Byte offsets (from the instruction start) of a displacement / immediates that encode addresses or constants. */
    internal val constantRanges: List<IntRange> = emptyList(),
    /** The bytes ended in the middle of an instruction (not an invalid opcode). */
    val truncated: Boolean = false,
    /**
     * General-purpose registers the instruction reads or writes (explicit operands, address registers and implicit
     * ones such as `rsp` for `push`), lower case as displayed (`eax`, `rbx`, `r8d`…). Segment registers and `rip` are
     * left out.
     */
    val registers: List<String> = emptyList(),
) {
    val length: Int get() = bytes.size
    val end: Long get() = address + bytes.size
    val writesMemory: Boolean get() = memory?.write == true
    val readsMemory: Boolean get() = memory?.read == true

    override fun toString(): String = "%X: %s".format(address, text)
}

/**
 * x86-64 decoding and formatting on the phone (iced-x86, MIT). ps4debug-NG's CMD_PROC_DISASM_REGION returns only
 * per-instruction metadata (no mnemonic text, see docs/PROTOCOL.md), so readable listings are produced here from
 * bytes read with CMD_PROC_READ.
 */
object X86 {
    const val MAX_INSTRUCTION_LENGTH = 15

    private fun formatter() = IntelFormatter().apply {
        options.hexPrefix = "0x"
        options.hexSuffix = null
        options.uppercaseHex = true
        options.spaceAfterOperandSeparator = true
        options.ripRelativeAddresses = false // show the resolved address, which is what users search for
        options.showBranchSize = false
        options.branchLeadingZeros = false
    }

    /** Decodes [bytes] located at [address] as a linear sweep. Undecodable bytes become single-byte `db` rows. */
    fun decode(bytes: ByteArray, address: Long, maxInstructions: Int = Int.MAX_VALUE, offset: Int = 0): List<AsmInstruction> {
        val out = ArrayList<AsmInstruction>()
        var pos = offset
        val fmt = formatter()
        val info = InstructionInfoFactory()
        while (pos < bytes.size && out.size < maxInstructions) {
            val ins = decodeAt(bytes, pos, address + (pos - offset), fmt, info)
            // An instruction cut off by the end of the buffer is not shown as a bogus `db` row.
            if (ins.truncated) break
            out += ins
            pos += ins.length
        }
        return out
    }

    /** Decodes exactly one instruction at [bytes]`[pos]` whose address is [address]. */
    fun decodeOne(bytes: ByteArray, pos: Int, address: Long): AsmInstruction = decodeAt(bytes, pos, address, formatter(), InstructionInfoFactory())

    private fun decodeAt(bytes: ByteArray, pos: Int, address: Long, fmt: IntelFormatter, infoFactory: InstructionInfoFactory): AsmInstruction {
        val len = minOf(MAX_INSTRUCTION_LENGTH, bytes.size - pos)
        val slice = bytes.copyOfRange(pos, pos + len)
        val decoder = Decoder(64, slice, address, 0)
        val ins = Instruction()
        decoder.decode(ins)
        if (ins.code == Code.INVALID || ins.length <= 0 || ins.length > len) {
            val b = byteArrayOf(bytes[pos])
            return AsmInstruction(
                address, b, "db 0x%02X".format(b[0].toInt() and 0xFF), "db", Flow.OTHER, null, null, null, valid = false,
                truncated = decoder.lastError == com.github.icedland.iced.x86.dec.DecoderError.NO_MORE_BYTES,
            )
        }
        val text = StringOutput().also { fmt.format(ins, it) }.toStringAndReset()
        val flow = when (ins.flowControl) {
            FlowControl.NEXT -> Flow.NEXT
            FlowControl.CALL, FlowControl.INDIRECT_CALL -> Flow.CALL
            FlowControl.RETURN -> Flow.RETURN
            FlowControl.UNCONDITIONAL_BRANCH, FlowControl.INDIRECT_BRANCH -> Flow.JUMP
            FlowControl.CONDITIONAL_BRANCH -> Flow.CONDITIONAL_JUMP
            FlowControl.INTERRUPT -> Flow.INTERRUPT
            else -> Flow.OTHER
        }
        val branch = if (ins.isCallNear || ins.isJmpNear || ins.isJmpShort || ins.isJccShortOrNear || ins.isJmpShortOrNear) ins.nearBranchTarget else null
        val rip = if (ins.isIPRelativeMemoryOperand) ins.ipRelativeMemoryAddress() else null
        val instrInfo = infoFactory.getInfo(ins)
        // Registers as written in the operands first (iced reports a written eax as rax), then implicit ones
        // (rsp for push/call, rcx for loop…) whose 64-bit register is not covered yet.
        val explicit = ArrayList<String>()
        for (i in 0 until ins.opCount) {
            if (ins.getOpKind(i) == com.github.icedland.iced.x86.OpKind.REGISTER) regName(fmt, ins.getOpRegister(i))?.let { explicit += it }
        }
        if (hasExplicitMemoryOperand(ins)) {
            regName(fmt, ins.memoryBase)?.let { explicit += it }
            regName(fmt, ins.memoryIndex)?.let { explicit += it }
        }
        fun gpr(n: String) = n !in NOT_GPR && registerValue(n, PROBE) != null
        val usedRegs = (explicit + instrInfo.usedRegisters.mapNotNull { u -> regName(fmt, u.register) })
            .filter { gpr(it) }
            .distinctBy { fullRegister(it) }
        val memory = instrInfo.usedMemory.firstOrNull { it.base != Register.NONE || it.index != Register.NONE || it.displacement != 0L }
            ?.takeIf { hasExplicitMemoryOperand(ins) }
            ?.let { m ->
                val access = m.access
                MemoryAccess(
                    read = access == OpAccess.READ || access == OpAccess.COND_READ || access == OpAccess.READ_WRITE ||
                        access == OpAccess.READ_COND_WRITE,
                    write = access == OpAccess.WRITE || access == OpAccess.COND_WRITE || access == OpAccess.READ_WRITE ||
                        access == OpAccess.READ_COND_WRITE,
                    base = regName(fmt, m.base),
                    index = regName(fmt, m.index),
                    scale = m.scale,
                    displacement = m.displacement,
                    size = com.github.icedland.iced.x86.MemorySize.getSize(m.memorySize),
                )
            }
        val co = decoder.getConstantOffsets(ins)
        val consts = buildList {
            if (co.hasDisplacement() && co.displacementSize >= 4) add(co.displacementOffset until co.displacementOffset + co.displacementSize)
            if (co.hasImmediate() && co.immediateSize >= 4) add(co.immediateOffset until co.immediateOffset + co.immediateSize)
            // rel32 branch displacements are reported as immediates by iced.
        }
        return AsmInstruction(
            address, slice.copyOf(ins.length), text, text.substringBefore(' '), flow, branch, rip, memory, valid = true,
            constantRanges = consts, registers = usedRegs,
        )
    }

    private val NOT_GPR = setOf("rip", "eip", "ip", "cs", "ds", "es", "ss", "fs", "gs")
    private val GPR64 = listOf("rax", "rbx", "rcx", "rdx", "rsi", "rdi", "rbp", "rsp") + (8..15).map { "r$it" }
    /** Every general-purpose register present: used to tell general-purpose names from vector/mask/segment ones. */
    private val PROBE: Map<String, Long> = GPR64.associateWith { 0L }

    /** The 64-bit register a name belongs to (`eax`/`ax`/`al`/`ah` → `rax`, `r8d` → `r8`); unknown names unchanged. */
    fun fullRegister(name: String): String {
        val n = name.lowercase()
        return GPR64.firstOrNull { full -> registerValue(n, mapOf(full to -1L)) != null } ?: n
    }

    private fun hasExplicitMemoryOperand(ins: Instruction): Boolean =
        (0 until ins.opCount).any { ins.getOpKind(it) == com.github.icedland.iced.x86.OpKind.MEMORY }

    private fun regName(fmt: IntelFormatter, reg: Int): String? = if (reg == Register.NONE) null else fmt.formatRegister(reg)

    /**
     * Returns the address from which a linear sweep through [window] (located at [windowAddress]) lands exactly on
     * [target], preferring the earliest start; null if none does. Used to show a few instructions *before* an
     * address without starting in the middle of one.
     */
    fun syncStart(window: ByteArray, windowAddress: Long, target: Long): Long? {
        val maxStart = (target - windowAddress).toInt()
        if (maxStart < 0 || maxStart > window.size) return null
        for (start in 0..maxStart) {
            var pos = start
            while (pos < maxStart) {
                val ins = decodeOne(window, pos, windowAddress + pos)
                if (!ins.valid) break
                pos += ins.length
            }
            if (pos == maxStart) return windowAddress + start
        }
        return null
    }

    /**
     * A data watchpoint traps *after* the accessing instruction, so the interrupted RIP points at the next
     * instruction. This finds the instruction that ends exactly at [end] inside [window] (which ends at or after
     * [end] and starts at [windowAddress]). Candidates with a memory operand are preferred; when [accessed] is given,
     * one whose effective address (from [registers], if known) overlaps it wins.
     */
    fun instructionEndingAt(
        window: ByteArray, windowAddress: Long, end: Long,
        accessed: LongRange? = null, registers: Map<String, Long> = emptyMap(),
    ): AsmInstruction? {
        val candidates = ArrayList<AsmInstruction>()
        for (len in 1..MAX_INSTRUCTION_LENGTH) {
            val start = end - len
            val pos = (start - windowAddress).toInt()
            if (pos < 0 || pos >= window.size) continue
            val ins = decodeOne(window, pos, start)
            if (ins.valid && ins.length == len) candidates += ins
        }
        if (candidates.isEmpty()) return null
        val withMemory = candidates.filter { it.memory != null }
        if (accessed != null) {
            withMemory.firstOrNull { ins -> effectiveAddress(ins, registers)?.let { ea -> ea in accessed || accessed.first in ea until ea + maxOf(1, ins.memory!!.size) } == true }
                ?.let { return it }
        }
        // Longest decodes are the least likely to be a suffix of another instruction.
        return (withMemory.ifEmpty { candidates }).maxByOrNull { it.length }
    }

    /**
     * Effective address of the memory operand from register values (lower-case 64-bit names, e.g. "rbx"). 32-bit
     * register names are resolved from their 64-bit parent. Returns null when a needed register is unknown.
     */
    fun effectiveAddress(ins: AsmInstruction, registers: Map<String, Long>): Long? {
        ins.ripTarget?.let { return it }
        val m = ins.memory ?: return null
        fun value(name: String?): Long? = if (name == null) 0L else registerValue(name, registers)
        val base = value(m.base) ?: return null
        val index = value(m.index) ?: return null
        return base + index * maxOf(1, m.scale) + m.displacement
    }

    /**
     * Value of register [name] (any width: `rax`, `eax`, `ax`, `al`, `ah`, `r8d`, `r8w`, `r8b`…) from a snapshot of the
     * 64-bit registers ([values], lower-case `rax`…`r15`, `rip`, `rsp`, `rflags`). Null when not in the snapshot
     * (e.g. vector registers).
     */
    fun registerValue(name: String, values: Map<String, Long>): Long? {
        val n = name.lowercase()
        values[n]?.let { return it }
        legacy32.matchEntire(n)?.let { return values["r" + it.groupValues[1]]?.and(0xFFFFFFFFL) }
        numbered.matchEntire(n)?.let { m ->
            val v = values["r" + m.groupValues[1]] ?: return null
            return when (m.groupValues[2]) { "d" -> v and 0xFFFFFFFFL; "w" -> v and 0xFFFFL; "b", "l" -> v and 0xFFL; else -> v }
        }
        legacy16.matchEntire(n)?.let { return values["r$n"]?.and(0xFFFFL) }
        low8abcd.matchEntire(n)?.let { return values["r" + it.groupValues[1] + "x"]?.and(0xFFL) }
        high8.matchEntire(n)?.let { return values["r" + it.groupValues[1] + "x"]?.let { v -> (v ushr 8) and 0xFFL } }
        low8other.matchEntire(n)?.let { return values["r" + it.groupValues[1]]?.and(0xFFL) }
        return null
    }

    /** Width in bits of a general-purpose register name (64 for unknown names). */
    fun registerBits(name: String): Int {
        val n = name.lowercase()
        return when {
            legacy32.matches(n) || (numbered.matchEntire(n)?.groupValues?.get(2) == "d") -> 32
            legacy16.matches(n) || (numbered.matchEntire(n)?.groupValues?.get(2) == "w") -> 16
            low8abcd.matches(n) || high8.matches(n) || low8other.matches(n) || (numbered.matchEntire(n)?.groupValues?.get(2) in setOf("b", "l")) -> 8
            else -> 64
        }
    }

    private val legacy32 = Regex("^e(ax|bx|cx|dx|si|di|bp|sp)$")
    private val legacy16 = Regex("^(ax|bx|cx|dx|si|di|bp|sp)$")
    private val numbered = Regex("^r(\\d{1,2})([dwbl]?)$")
    private val low8abcd = Regex("^([abcd])l$")
    private val high8 = Regex("^([abcd])h$")
    private val low8other = Regex("^(si|di|bp|sp)l$")

    /**
     * An AOB signature for the code at [address]: the instruction bytes with 32-bit displacements and immediates
     * (addresses, RIP-relative offsets, constants that change between builds) replaced by `??`.
     */
    fun signature(instructions: List<AsmInstruction>): String = instructions.joinToString(" ") { ins ->
        ins.bytes.indices.joinToString(" ") { i ->
            if (ins.constantRanges.any { i in it }) "??" else "%02X".format(ins.bytes[i].toInt() and 0xFF)
        }
    }

    /** Single-byte NOPs. Kept simple so patches stay readable in GoldHEN files. */
    fun nops(count: Int): ByteArray = ByteArray(count) { 0x90.toByte() }
}
