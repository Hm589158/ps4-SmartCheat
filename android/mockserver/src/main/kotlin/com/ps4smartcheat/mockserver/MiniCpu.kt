package com.ps4smartcheat.mockserver

import com.github.icedland.iced.x86.Code
import com.github.icedland.iced.x86.Instruction
import com.github.icedland.iced.x86.OpKind
import com.github.icedland.iced.x86.Register
import com.github.icedland.iced.x86.dec.Decoder

/**
 * A very small x86-64 interpreter for the mock "game": it runs a routine from the mock's memory so that code patches
 * and code-cave injections made by the app have a real, observable effect in tests. Supports only what the demo
 * routine and simple injected code need — 32-bit `mov`/`add`/`sub`/`xor`/`inc`/`dec` on registers and
 * RIP-relative or `[reg+disp]` memory, `mov r32, imm`, NOPs, `jmp rel8/rel32`, `jmp qword ptr [rip+0]`
 * (absolute jump), `call rel32` and `ret`. Anything else throws [Unsupported], so a test never silently passes on
 * code the mock did not execute.
 */
class MiniCpu(private val server: MockPs4DebugServer, private val pid: Int) {
    class Unsupported(message: String) : RuntimeException(message)

    /** rax..r15 by index (0 = rax). */
    val regs = LongArray(16)
    var rsp = 0x7EFF_0000L
    /** Executed instruction addresses, in order. */
    val trace = ArrayList<Long>()

    private fun idx64(r: Int): Int? = when (r) {
        in Register.RAX..Register.R15 -> r - Register.RAX
        else -> null
    }

    private fun idx32(r: Int): Int? = when (r) {
        in Register.EAX..Register.R15D -> r - Register.EAX
        else -> null
    }

    /** General-purpose registers for the debugger emulation (rsp is left to its default). */
    private fun named(): Map<String, Long> = NAMES.withIndex().filter { it.index != 4 }.associate { (i, n) -> n to regs[i] }

    /** Runs from [entry] until the routine's own `ret` (call depth 0). */
    fun run(entry: Long, maxSteps: Int = 500) {
        var rip = entry
        var depth = 0
        repeat(maxSteps) {
            val bytes = server.peek(pid, rip, 15.coerceAtMost(available(rip)))
            val ins = Instruction()
            Decoder(64, bytes, rip, 0).decode(ins)
            if (ins.code == Code.INVALID) throw Unsupported("invalid instruction at 0x%X".format(rip))
            trace += rip
            val next = rip + ins.length
            when (ins.code) {
                Code.NOPD, Code.NOPW, Code.NOPQ, Code.NOP_RM32, Code.NOP_RM16, Code.NOP_RM64 -> rip = next
                Code.RETNQ -> { if (depth == 0) return; depth--; rip = pop() }
                Code.CALL_REL32_64 -> { push(next); depth++; rip = ins.nearBranchTarget }
                Code.JMP_REL32_64, Code.JMP_REL8_64 -> rip = ins.nearBranchTarget
                Code.JMP_RM64 -> {
                    if (ins.getOpKind(0) != OpKind.MEMORY || !ins.isIPRelativeMemoryOperand) throw Unsupported("indirect jmp at 0x%X".format(rip))
                    rip = read(ins.ipRelativeMemoryAddress(), 8, rip, ins.length)
                }
                Code.MOV_R32_IMM32 -> { set32(ins.op0Register, ins.immediate32.toLong()); rip = next }
                Code.MOV_R32_RM32 -> { set32(ins.op0Register, src32(ins, 1, rip)); rip = next }
                Code.MOV_RM32_R32 -> { store32(ins, get32(ins.op1Register), rip); rip = next }
                Code.MOV_RM32_IMM32 -> { store32(ins, ins.immediate32.toLong(), rip); rip = next }
                Code.ADD_RM32_IMM8, Code.ADD_RM32_IMM32, Code.SUB_RM32_IMM8, Code.SUB_RM32_IMM32,
                Code.ADD_EAX_IMM32, Code.SUB_EAX_IMM32 -> {
                    val imm = if (ins.code == Code.ADD_RM32_IMM8 || ins.code == Code.SUB_RM32_IMM8) ins.immediate8to32.toLong() else ins.immediate32.toLong()
                    val sign = if (ins.code == Code.SUB_RM32_IMM8 || ins.code == Code.SUB_RM32_IMM32 || ins.code == Code.SUB_EAX_IMM32) -1 else 1
                    val cur = if (ins.getOpKind(0) == OpKind.REGISTER) get32(ins.op0Register) else src32(ins, 0, rip)
                    val v = (cur + sign * imm) and 0xFFFF_FFFFL
                    if (ins.getOpKind(0) == OpKind.REGISTER) set32(ins.op0Register, v) else store32(ins, v, rip)
                    rip = next
                }
                Code.INC_RM32, Code.DEC_RM32 -> {
                    val d = if (ins.code == Code.INC_RM32) 1 else -1
                    if (ins.getOpKind(0) != OpKind.REGISTER) throw Unsupported("inc/dec memory at 0x%X".format(rip))
                    set32(ins.op0Register, (get32(ins.op0Register) + d) and 0xFFFF_FFFFL)
                    rip = next
                }
                Code.XOR_RM32_R32, Code.XOR_R32_RM32 -> {
                    if (ins.getOpKind(0) != OpKind.REGISTER || ins.getOpKind(1) != OpKind.REGISTER) throw Unsupported("xor memory at 0x%X".format(rip))
                    set32(ins.op0Register, get32(ins.op0Register) xor get32(ins.op1Register))
                    rip = next
                }
                else -> throw Unsupported("mock CPU cannot run ${ins.code} at 0x%X".format(rip))
            }
        }
        throw Unsupported("routine did not return within $maxSteps instructions")
    }

    private fun available(rip: Long): Int {
        val r = server.process(pid)!!.regions.first { rip >= it.start && rip < it.end }
        return (r.end - rip).toInt()
    }

    private fun get32(r: Int): Long = regs[idx32(r) ?: throw Unsupported("register $r")] and 0xFFFF_FFFFL
    private fun set32(r: Int, v: Long) { regs[idx32(r) ?: throw Unsupported("register $r")] = v and 0xFFFF_FFFFL }

    private fun address(ins: Instruction): Long = when {
        ins.isIPRelativeMemoryOperand -> ins.ipRelativeMemoryAddress()
        ins.memoryIndex == Register.NONE && idx64(ins.memoryBase) != null -> regs[idx64(ins.memoryBase)!!] + ins.memoryDisplacement64
        else -> throw Unsupported("memory operand form")
    }

    private fun src32(ins: Instruction, op: Int, rip: Long): Long =
        if (ins.getOpKind(op) == OpKind.REGISTER) get32(ins.getOpRegister(op)) else read(address(ins), 4, rip, ins.length)

    private fun store32(ins: Instruction, v: Long, rip: Long) {
        if (ins.getOpKind(0) == OpKind.REGISTER) { set32(ins.op0Register, v); return }
        val a = address(ins)
        server.poke(pid, a, byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        server.gameAccess(pid, rip, ins.length, a, 4, write = true, registers = named())
    }

    private fun read(a: Long, size: Int, rip: Long, len: Int): Long {
        server.gameAccess(pid, rip, len, a, size, write = false, registers = named())
        val b = server.peek(pid, a, size)
        var v = 0L
        for (i in size - 1 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    private val stack = ArrayDeque<Long>()
    private fun push(v: Long) { stack.addLast(v); rsp -= 8 }
    private fun pop(): Long { rsp += 8; return stack.removeLast() }

    companion object {
        val NAMES = listOf("rax", "rcx", "rdx", "rbx", "rsp", "rbp", "rsi", "rdi", "r8", "r9", "r10", "r11", "r12", "r13", "r14", "r15")
    }
}
