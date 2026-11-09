package com.ps4smartcheat.core.debug

import com.ps4smartcheat.core.asm.AsmInstruction
import com.ps4smartcheat.core.asm.Flow
import com.ps4smartcheat.core.asm.X86
import com.ps4smartcheat.core.cheat.HexAddressSerializer
import com.ps4smartcheat.core.cheat.ModuleRef
import com.ps4smartcheat.core.cheat.ModuleResolver
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.util.LE
import kotlinx.serialization.Serializable

/** How sure the unwinder is that a stack slot is a real caller. */
enum class FrameConfidence(val label: String) {
    /** The slot follows a direct `call` whose target is the function the previous frame is in. */
    CONFIRMED("call verified"),
    /** Found through the saved frame-pointer chain (`rbp`). */
    FRAME_CHAIN("frame pointer chain"),
    /** Follows an indirect call (`call rax`, `call [rip+…]`): the target cannot be checked. */
    PROBABLE("indirect call"),
    /** Follows a direct call to some other function: often a stale value left on the stack. */
    WEAK("possibly stale"),
}

/** One caller on the stack at a watchpoint hit. */
@Serializable
data class StackFrame(
    /** Where the caller continues after the call returns (the value found on the stack). */
    @Serializable(with = HexAddressSerializer::class) val returnAddress: Long,
    /** The `call` instruction in the caller. */
    @Serializable(with = HexAddressSerializer::class) val callAddress: Long,
    val callText: String,
    /** Direct call target (start of the called function), null for indirect calls. */
    @Serializable(with = HexAddressSerializer::class) val callTarget: Long? = null,
    /** Byte offset from `rsp` of the stack slot holding [returnAddress]. */
    val stackOffset: Int,
    val confidence: FrameConfidence,
    /** Module-relative form of [callAddress] at capture time (for finding it again after a restart). */
    val module: ModuleRef? = null,
)

/**
 * Call stack ("stack trace") of a thread stopped at a watchpoint. ps4debug has no unwinder, so the stack memory is read
 * while the game is paused (before CONTINUE) and walked on the phone:
 *
 *  - every 8-byte slot from `rsp` up that points into executable memory **right after a `call` instruction** is a
 *    candidate return address (the call is decoded from the code bytes before it);
 *  - a direct call whose target is the start of the function the previous frame is in is **confirmed**;
 *  - slots reached through the saved `rbp` chain (`[rbp]` = caller's rbp, `[rbp+8]` = return address) are marked as such;
 *  - other candidates are shown with a lower confidence (indirect calls cannot be checked; direct calls elsewhere are
 *    often stale values left by earlier calls).
 *
 * This is the same heuristic Cheat Engine's stack view uses; without unwind tables it can include stale entries or
 * miss frames of code that keeps no return address on the stack (tail calls).
 */
object CallStack {
    /** Stack bytes read at a hit (from rsp). */
    const val DEFAULT_STACK_BYTES = 1024
    const val MAX_STACK_BYTES = 4096
    const val MAX_FRAMES = 16
    /** A function is assumed to be shorter than this when checking "call target ≤ previous frame". */
    const val MAX_FUNCTION_SIZE = 1L shl 20
    private val CALL_LENGTHS = intArrayOf(5, 6, 2, 3, 7, 4)

    /** Stack bytes at [rsp] to read: up to [want], inside the readable mapping that holds rsp; 0 when unmapped. */
    fun readableLength(rsp: Long, regions: List<MemoryRegion>, want: Int = DEFAULT_STACK_BYTES): Int {
        val r = regions.firstOrNull { it.contains(rsp) && it.isReadable } ?: return 0
        return minOf(want.toLong(), r.end - rsp, MAX_STACK_BYTES.toLong()).toInt().coerceAtLeast(0)
    }

    /**
     * The `call` that ends exactly at [returnAddress], decoded from [code] = the bytes `[returnAddress - code.size, returnAddress)`.
     */
    fun callEndingAt(returnAddress: Long, code: ByteArray): AsmInstruction? {
        for (len in CALL_LENGTHS) {
            if (len > code.size) continue
            val ins = X86.decodeOne(code, code.size - len, returnAddress - len)
            if (ins.valid && ins.length == len && ins.flow == Flow.CALL) return ins
        }
        return null
    }

    /**
     * Walks [stack] (read at [rsp]) for callers of the code at [rip]. [code] returns the 8 bytes before an address
     * (or null when unreadable); it is called only for slots pointing into executable memory, at most [maxProbes] times.
     */
    suspend fun unwind(
        rip: Long, rsp: Long, rbp: Long, stack: ByteArray, regions: List<MemoryRegion>,
        code: suspend (address: Long, length: Int) -> ByteArray?,
        maxFrames: Int = MAX_FRAMES, maxProbes: Int = 128,
    ): List<StackFrame> {
        val exec = regions.filter { it.isValid && it.isExecutable && it.isReadable }
        fun u64(off: Int) = LE.i64(stack, off)
        // Saved frame-pointer chain inside the bytes we have.
        val chainSlots = HashSet<Int>()
        var fp = rbp
        var links = 0
        while (links++ < MAX_FRAMES * 2) {
            val off = fp - rsp
            if (off < 0 || off + 16 > stack.size) break
            chainSlots += (off + 8).toInt()
            val next = u64(off.toInt())
            if (next <= fp) break // the chain must go up the stack
            fp = next
        }
        val frames = ArrayList<StackFrame>()
        var prevPc = rip
        var probes = 0
        var off = 0
        while (off + 8 <= stack.size && frames.size < maxFrames && probes < maxProbes) {
            val v = u64(off)
            val slot = off
            off += 8
            if (exec.none { it.contains(v - 1) && it.contains(v - 2) }) continue
            val start = maxOf(v - 8, exec.first { it.contains(v - 1) }.start)
            probes++
            val bytes = code(start, (v - start).toInt()) ?: continue
            val call = callEndingAt(v, bytes) ?: continue
            val target = call.branchTarget
            val confidence = when {
                target != null && target <= prevPc && prevPc - target < MAX_FUNCTION_SIZE -> FrameConfidence.CONFIRMED
                slot in chainSlots -> FrameConfidence.FRAME_CHAIN
                target == null -> FrameConfidence.PROBABLE
                else -> FrameConfidence.WEAK
            }
            frames += StackFrame(v, call.address, call.text, target, slot, confidence, ModuleResolver.refFor(call.address, regions))
            // The next caller must call into the function this frame is in.
            if (confidence != FrameConfidence.WEAK) prevPc = v
        }
        return frames
    }

    /** [unwind] with code read through [read] in 256-byte blocks, cached (one read serves many slots). */
    suspend fun unwindWithReader(
        rip: Long, rsp: Long, rbp: Long, stack: ByteArray, regions: List<MemoryRegion>,
        read: suspend (address: Long, length: Int) -> ByteArray,
    ): List<StackFrame> {
        val cache = HashMap<Long, ByteArray?>()
        suspend fun block(b: Long): ByteArray? = cache.getOrPut(b) {
            val r = regions.firstOrNull { it.contains(b) && it.isReadable } ?: return@getOrPut null
            val len = minOf(BLOCK, r.end - b).toInt()
            runCatching { read(b, len) }.getOrNull()
        }
        return unwind(rip, rsp, rbp, stack, regions, { a, n ->
            val out = ByteArray(n)
            var i = 0
            while (i < n) {
                val addr = a + i
                val b = addr and (BLOCK - 1).inv()
                val data = block(b) ?: return@unwind null
                val o = (addr - b).toInt()
                if (o >= data.size) return@unwind null
                val k = minOf(n - i, data.size - o)
                data.copyInto(out, i, o, o + k)
                i += k
            }
            out
        })
    }

    private const val BLOCK = 256L
}
