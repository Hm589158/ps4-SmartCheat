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
 * RIP-relative or `[reg+disp]` memory, `mov r32, imm`, `mov r64, r64`, `push`/`pop r64`, NOPs, `jmp rel8/rel32`,
 * `cmp r/m32, imm` followed by a signed or unsigned `jcc rel8/rel32`,
 * `jmp qword ptr [rip+0]` (absolute jump), `call rel32`, `call r64` and `ret`. The stack lives in the mock's memory
 * ([stackTop] must be in a writable region), so a watchpoint hit sees real return addresses on it. Anything else throws [Unsupported], so a test never silently passes on
 * code the mock did not execute.
 */
class MiniCpu(private val server: MockPs4DebugServer, private val pid: Int, stackTop: Long = DemoGame.STACK_TOP) {
    class Unsupported(message: String) : RuntimeException(message)

    /** rax..r15 by index (0 = rax). */
    val regs = LongArray(16)
    var rsp = stackTop
    /** Executed instruction addresses, in order. */
    val trace = ArrayList<Long>()
    /** Operands of the last `cmp` (32-bit), for the conditional jumps. */
    private var cmpA = 0L
    private var cmpB = 0L

    private fun idx64(r: Int): Int? = when (r) {
        in Register.RAX..Register.R15 -> r - Register.RAX
        else -> null
    }

    private fun idx32(r: Int): Int? = when (r) {
        in Register.EAX..Register.R15D -> r - Register.EAX
        else -> null
    }

    /** General-purpose registers for the debugger emulation. */
    private fun named(): Map<String, Long> = NAMES.withIndex().associate { (i, n) -> n to if (i == 4) rsp else regs[i] }

    private fun get64(r: Int): Long { val i = idx64(r) ?: throw Unsupported("register $r"); return if (i == 4) rsp else regs[i] }
    private fun set64(r: Int, v: Long) { val i = idx64(r) ?: throw Unsupported("register $r"); if (i == 4) rsp = v else regs[i] = v }

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
                Code.CALL_RM64 -> {
                    if (ins.getOpKind(0) != OpKind.REGISTER) throw Unsupported("call through memory at 0x%X".format(rip))
                    push(next); depth++; rip = get64(ins.op0Register)
                }
                Code.PUSH_R64 -> { push(get64(ins.op0Register)); rip = next }
                Code.POP_R64 -> { set64(ins.op0Register, pop()); rip = next }
                Code.MOV_RM64_R64, Code.MOV_R64_RM64 -> {
                    if (ins.getOpKind(0) != OpKind.REGISTER || ins.getOpKind(1) != OpKind.REGISTER) throw Unsupported("64-bit memory move at 0x%X".format(rip))
                    set64(ins.op0Register, get64(ins.op1Register)); rip = next
                }
                Code.JMP_REL32_64, Code.JMP_REL8_64 -> rip = ins.nearBranchTarget
                Code.CMP_RM32_IMM8, Code.CMP_RM32_IMM32, Code.CMP_EAX_IMM32 -> {
                    cmpA = if (ins.getOpKind(0) == OpKind.REGISTER) get32(ins.op0Register) else src32(ins, 0, rip)
                    cmpB = (if (ins.code == Code.CMP_RM32_IMM8) ins.immediate8to32.toLong() else ins.immediate32.toLong()) and 0xFFFF_FFFFL
                    rip = next
                }
                in JCC -> {
                    val sa = cmpA.toInt()
                    val sb = cmpB.toInt()
                    val taken = when (JCC.getValue(ins.code)) {
                        "e" -> cmpA == cmpB
                        "ne" -> cmpA != cmpB
                        "l" -> sa < sb
                        "ge" -> sa >= sb
                        "le" -> sa <= sb
                        "g" -> sa > sb
                        "b" -> cmpA < cmpB
                        "ae" -> cmpA >= cmpB
                        "be" -> cmpA <= cmpB
                        else -> cmpA > cmpB
                    }
                    rip = if (taken) ins.nearBranchTarget else next
                }
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

    private fun push(v: Long) {
        rsp -= 8
        server.poke(pid, rsp, ByteArray(8) { i -> (v shr (8 * i)).toByte() })
    }

    private fun pop(): Long {
        val b = server.peek(pid, rsp, 8)
        rsp += 8
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    companion object {
        private val JCC: Map<Int, String> = mapOf(
            Code.JE_REL8_64 to "e", Code.JE_REL32_64 to "e", Code.JNE_REL8_64 to "ne", Code.JNE_REL32_64 to "ne",
            Code.JL_REL8_64 to "l", Code.JL_REL32_64 to "l", Code.JGE_REL8_64 to "ge", Code.JGE_REL32_64 to "ge",
            Code.JLE_REL8_64 to "le", Code.JLE_REL32_64 to "le", Code.JG_REL8_64 to "g", Code.JG_REL32_64 to "g",
            Code.JB_REL8_64 to "b", Code.JB_REL32_64 to "b", Code.JAE_REL8_64 to "ae", Code.JAE_REL32_64 to "ae",
            Code.JBE_REL8_64 to "be", Code.JBE_REL32_64 to "be", Code.JA_REL8_64 to "a", Code.JA_REL32_64 to "a",
        )
        val NAMES = listOf("rax", "rcx", "rdx", "rbx", "rsp", "rbp", "rsi", "rdi", "r8", "r9", "r10", "r11", "r12", "r13", "r14", "r15")
    }
}
