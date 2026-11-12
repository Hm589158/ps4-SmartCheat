package com.ps4smartcheat.mockserver

import com.github.icedland.iced.x86.Code
import com.github.icedland.iced.x86.Instruction
import com.github.icedland.iced.x86.dec.Decoder
import java.io.ByteArrayOutputStream

/**
 * Tiny stand-in for the Keystone assembler embedded in ps4debug-NG, enough for tests. Supports:
 * `nop`, `ret`, `int3`, `mov r32, imm`, `xor r32, r32`, `add`/`sub`/`cmp r32, imm`, `jmp <address>` (always
 * rel32) and `jcc <address>` (rel8 when the target is within reach, else rel32 — so label layouts must settle), `mov dword ptr [r64+disp], imm`,
 * and `mov`/`cmp byte|dword ptr [rip ± disp], imm` (RIP-relative: the displacement counts from the next instruction).
 * Anything else fails like Keystone does (CMD_ERROR + errno + message).
 */
object MiniAssembler {
    class AsmError(val errno: Int, message: String) : Exception(message)

    private val R32 = listOf("eax", "ecx", "edx", "ebx", "esp", "ebp", "esi", "edi")
    private val R64 = listOf("rax", "rcx", "rdx", "rbx", "rsp", "rbp", "rsi", "rdi")
    /** ModRM reg field of the group-1 instructions. */
    private val ARITH = mapOf("add" to 0, "sub" to 5, "cmp" to 7)
    private val JCC = mapOf(
        "jb" to 2, "jae" to 3, "je" to 4, "jz" to 4, "jne" to 5, "jnz" to 5, "jbe" to 6, "ja" to 7,
        "jl" to 0xC, "jge" to 0xD, "jle" to 0xE, "jg" to 0xF,
    )

    private val RIP_OPERAND = Regex("(byte|dword) ptr \\[\\s*rip\\s*(?:([+-])\\s*(0x[0-9a-f]+|\\d+))?\\s*]")

    fun assemble(text: String, base: Long): Pair<ByteArray, Int> {
        val out = ByteArrayOutputStream()
        var count = 0
        for (raw in text.split('\n', ';')) {
            val line = raw.trim().lowercase()
            if (line.isEmpty()) continue
            out.write(encode(line, base + out.size()))
            count++
        }
        if (count == 0) throw AsmError(1, "Invalid operand (KS_ERR_ASM_INVALIDOPERAND)")
        return out.toByteArray() to count
    }

    private fun num(s: String): Long = s.trim().let { if (it.startsWith("0x")) java.lang.Long.parseUnsignedLong(it.drop(2), 16) else it.toLong() }

    private fun encode(line: String, at: Long): ByteArray {
        fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        when (line) {
            "nop" -> return byteArrayOf(0x90.toByte())
            "ret" -> return byteArrayOf(0xC3.toByte())
            "int3" -> return byteArrayOf(0xCC.toByte())
        }
        val m = line.split(Regex("\\s+"), limit = 2)
        val ops = m.getOrNull(1)?.split(',')?.map { it.trim() } ?: emptyList()
        try {
            when {
                m[0] == "mov" && ops.size == 2 && ops[0] in R32 -> return byteArrayOf((0xB8 + R32.indexOf(ops[0])).toByte()) + le32(num(ops[1]))
                m[0] == "xor" && ops.size == 2 && ops[0] in R32 && ops[1] in R32 ->
                    return byteArrayOf(0x31, (0xC0 + R32.indexOf(ops[1]) * 8 + R32.indexOf(ops[0])).toByte())
                m[0] in ARITH && ops.size == 2 && ops[0] in R32 -> {
                    val r = R32.indexOf(ops[0])
                    val imm = num(ops[1])
                    val ext = ARITH.getValue(m[0])
                    return if (imm in -128..127) byteArrayOf(0x83.toByte(), (0xC0 + ext * 8 + r).toByte(), imm.toByte())
                    else byteArrayOf(0x81.toByte(), (0xC0 + ext * 8 + r).toByte()) + le32(imm)
                }
                m[0] == "jmp" && ops.size == 1 -> return byteArrayOf(0xE9.toByte()) + le32(num(ops[0]) - (at + 5))
                m[0] in JCC && ops.size == 1 -> {
                    val cc = JCC.getValue(m[0])
                    val d = num(ops[0]) - (at + 2)
                    return if (d in -128..127) byteArrayOf((0x70 + cc).toByte(), d.toByte())
                    else byteArrayOf(0x0F, (0x80 + cc).toByte()) + le32(num(ops[0]) - (at + 6))
                }
                (m[0] == "mov" || m[0] == "cmp") && ops.size == 2 && RIP_OPERAND.matches(ops[0]) -> {
                    val g = RIP_OPERAND.matchEntire(ops[0])!!.groupValues
                    val disp = if (g[3].isEmpty()) 0L else num(g[3]) * (if (g[2] == "-") -1 else 1)
                    if (disp !in Int.MIN_VALUE..Int.MAX_VALUE) throw AsmError(2, "Invalid operand (KS_ERR_ASM_INVALIDOPERAND)")
                    val imm = num(ops[1])
                    return when {
                        g[1] == "byte" && m[0] == "mov" -> byteArrayOf(0xC6.toByte(), 0x05) + le32(disp) + byteArrayOf(imm.toByte())
                        g[1] == "byte" -> byteArrayOf(0x80.toByte(), 0x3D) + le32(disp) + byteArrayOf(imm.toByte())
                        m[0] == "mov" -> byteArrayOf(0xC7.toByte(), 0x05) + le32(disp) + le32(imm)
                        imm in -128..127 -> byteArrayOf(0x83.toByte(), 0x3D) + le32(disp) + byteArrayOf(imm.toByte())
                        else -> byteArrayOf(0x81.toByte(), 0x3D) + le32(disp) + le32(imm)
                    }
                }
                m[0] == "mov" && ops.size == 2 && ops[0].startsWith("dword ptr [") -> {
                    val inner = ops[0].removePrefix("dword ptr [").removeSuffix("]")
                    val reg = inner.substringBefore('+').substringBefore('-').trim()
                    val r = R64.indexOf(reg)
                    if (r < 0 || r == 4 || r == 5) throw AsmError(2, "Invalid operand (KS_ERR_ASM_INVALIDOPERAND)")
                    val disp = when {
                        '+' in inner -> num(inner.substringAfter('+'))
                        '-' in inner -> -num(inner.substringAfter('-'))
                        else -> 0L
                    }
                    val imm = le32(num(ops[1]))
                    return if (disp in -128..127) byteArrayOf(0xC7.toByte(), (0x40 + r).toByte(), disp.toByte()) + imm
                    else byteArrayOf(0xC7.toByte(), (0x80 + r).toByte()) + le32(disp) + imm
                }
            }
        } catch (e: NumberFormatException) {
            throw AsmError(2, "Invalid operand (KS_ERR_ASM_INVALIDOPERAND)")
        }
        throw AsmError(3, "Invalid mnemonic (KS_ERR_ASM_MNEMONICFAIL)")
    }

    /**
     * Linear sweep like ps4debug-NG's disasm_iterate (undecodable byte → skip one): (instruction address, RIP-relative
     * target) for instructions with a RIP-relative memory operand.
     */
    fun ripTargets(code: ByteArray, address: Long): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        var pos = 0
        val ins = Instruction()
        while (pos < code.size) {
            val d = Decoder(64, code.copyOfRange(pos, minOf(code.size, pos + 15)), address + pos, 0)
            d.decode(ins)
            if (ins.code == Code.INVALID) { pos++; continue }
            if (ins.isIPRelativeMemoryOperand) out += (address + pos) to ins.ipRelativeMemoryAddress()
            pos += ins.length
        }
        return out
    }
}
