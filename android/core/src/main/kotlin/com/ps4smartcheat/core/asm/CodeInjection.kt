package com.ps4smartcheat.core.asm

import com.github.icedland.iced.x86.CodeWriter
import com.github.icedland.iced.x86.Instruction
import com.github.icedland.iced.x86.dec.Decoder
import com.github.icedland.iced.x86.enc.BlockEncoder
import com.github.icedland.iced.x86.enc.InstructionBlock
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.util.Address
import com.ps4smartcheat.core.util.Hex
import java.io.ByteArrayOutputStream

/** Where the instructions overwritten by the jump run. */
enum class OriginalCode(val label: String) {
    /** After the new code (Cheat Engine's default template). */
    AFTER("after my code"),
    BEFORE("before my code"),
    /** Not at all: the new code replaces them. */
    SKIP("not (replace them)"),
}

/** The jump written at the hook. */
enum class HookJump(val size: Int, val label: String) {
    /** `jmp rel32` — the cave is within ±2 GiB. */
    REL32(5, "jmp rel32 (5 bytes)"),
    /** `jmp qword ptr [rip+0]` followed by the 8-byte target. */
    ABS64(14, "jmp [rip] + 8-byte address (14 bytes)"),
}

enum class InjectionState(val label: String) {
    /** Hook and cave hold the original bytes. */
    OFF("off (original code)"),
    /** Jump and cave code in place. */
    ON("on (injected)"),
    /** Cave code written but the jump is not: harmless (never runs), left by an interrupted enable/disable. */
    CAVE_ONLY("cave written, jump not in place"),
    /** Anything else: another game build, or the game/other tool changed the bytes. Never written. */
    MISMATCH("does not match"),
}

/** Free space the cave code is written to; [current] = the bytes there now (at least [capacity] long). */
class CaveSpace(
    val address: Long, val capacity: Int, val kind: CaveKind, val current: ByteArray,
    /** Page padding up to the end of the code mapping (see [CaveCandidate.atSegmentEnd]). */
    val atSegmentEnd: Boolean = false,
)

/**
 * A planned code-cave injection ("Assembly injection"): the instructions at [hookAddress] are replaced by a jump into
 * the cave; the cave holds the new code, the moved original instructions ([original]) and a jump back to
 * [returnAddress]. Enable = write [caveOn] then [hookOn]; disable = write [hookOff] first, then [caveOff].
 */
class CodeInjection(
    val hookAddress: Long,
    val hookOff: ByteArray,
    val hookOn: ByteArray,
    /** Original instructions overwritten by the jump (and moved to the cave unless [original] is SKIP). */
    val stolen: List<AsmInstruction>,
    val returnAddress: Long,
    val jump: HookJump,
    val caveKind: CaveKind,
    val caveAddress: Long,
    val caveOff: ByteArray,
    val caveOn: ByteArray,
    /** [caveOn] decoded (an absolute jump with its 8-byte target is one row). */
    val caveListing: List<AsmInstruction>,
    /** [hookOn] decoded. */
    val hookListing: List<AsmInstruction>,
    /** Addresses of the parts in the cave. */
    val userCode: LongRange,
    val movedOriginal: LongRange?,
    val exitJump: LongRange,
    val original: OriginalCode,
    /** The new code as typed (assembly or hex). */
    val source: String,
    val sourceIsAssembly: Boolean,
    val warnings: List<String>,
    /** AOB signature of the original code at the hook (constants wildcarded). */
    val signature: String,
) {
    val caveRange: LongRange get() = caveAddress until caveAddress + caveOn.size

    fun state(hookNow: ByteArray, caveNow: ByteArray): InjectionState {
        val hookOn = hookNow.contentEquals(this.hookOn)
        val hookOff = hookNow.contentEquals(this.hookOff)
        val caveOn = caveNow.contentEquals(this.caveOn)
        val caveOff = caveNow.contentEquals(this.caveOff)
        return when {
            hookOn && caveOn -> InjectionState.ON
            hookOff && caveOff -> InjectionState.OFF
            hookOff && caveOn -> InjectionState.CAVE_ONLY
            else -> InjectionState.MISMATCH
        }
    }

    /** Text listing for previews and logs. */
    fun describe(): String = buildString {
        append("Hook ").append(Address.format(hookAddress)).append(" (").append(jump.label).append(")\n")
        append("  OFF ").append(Hex.encode(hookOff, " ")).append('\n')
        stolen.forEach { append("      ").append(it.text).append('\n') }
        append("  ON  ").append(Hex.encode(hookOn, " ")).append('\n')
        append("Cave ").append(Address.format(caveAddress)).append(" (").append(caveOn.size).append(" bytes)\n")
        caveListing.forEach { append("  ").append(Address.format(it.address)).append("  ").append(it.text).append('\n') }
        append("Returns to ").append(Address.format(returnAddress))
    }
}

object InjectionPlanner {
    /** Bytes to read at the hook: jump size + longest instruction, plus following code checked for jumps back in. */
    const val HOOK_WINDOW = 128
    /** Largest cave code (new code + moved instructions + jump back). */
    const val MAX_CAVE_CODE = 1024

    private const val REL32_LIMIT = 0x7FFF_0000L

    private fun fitsRel32(from: Long, to: Long): Boolean {
        val d = to - from
        return d in -REL32_LIMIT..REL32_LIMIT
    }

    /** The smallest jump that reaches a cave at [cave] (of [capacity] bytes) from [hook] and back. */
    fun jumpFor(hook: Long, cave: Long, capacity: Int): HookJump =
        if (fitsRel32(hook + 5, cave) && fitsRel32(cave, hook) && fitsRel32(cave + capacity, hook + 32)) HookJump.REL32 else HookJump.ABS64

    /** Whole instructions at [hook] covering at least [bytes] bytes. */
    fun steal(hook: Long, window: ByteArray, bytes: Int): List<AsmInstruction> {
        val out = ArrayList<AsmInstruction>()
        var covered = 0
        while (covered < bytes) {
            if (covered >= window.size) throw CodePatchException("Not enough code was read at ${Address.format(hook)}.")
            val ins = X86.decodeOne(window, covered, hook + covered)
            if (!ins.valid) {
                throw CodePatchException(
                    "The bytes at ${Address.format(hook + covered)} do not decode as an instruction. " +
                        "Hook an instruction boundary (pick it in the disassembly).",
                )
            }
            out += ins
            covered += ins.length
        }
        return out
    }

    /** Assembly template shown when an injection is started: the moved instructions as comments. */
    fun template(stolen: List<AsmInstruction>, original: OriginalCode): String = buildString {
        when (original) {
            OriginalCode.AFTER -> append("// Your code. The original instructions run after it:\n")
            OriginalCode.BEFORE -> append("// Your code. The original instructions ran just before it:\n")
            OriginalCode.SKIP -> append("// Your code replaces these instructions:\n")
        }
        stolen.forEach { append("//   ").append(it.text).append('\n') }
        append("// Keep registers and flags the game needs (push/pop what you change).\n")
    }

    /**
     * Moves [stolen] (decoded from [window] at its address) to [to], re-encoding RIP-relative operands and branches
     * for the new location with iced's BlockEncoder (a short branch becomes a near one when needed).
     */
    fun relocate(stolen: List<AsmInstruction>, window: ByteArray, to: Long): ByteArray {
        if (stolen.isEmpty()) return ByteArray(0)
        val from = stolen.first().address
        val len = stolen.sumOf { it.length }
        val decoder = Decoder(64, window.copyOf(len), from, 0)
        val list = ArrayList<Instruction>()
        repeat(stolen.size) { list += decoder.decode() }
        val out = ByteArrayOutputStream()
        val r = BlockEncoder.tryEncode(64, InstructionBlock(CodeWriter { b -> out.write(b.toInt()) }, list, to))
        if (r is String) throw CodePatchException("The original instructions cannot be moved to ${Address.format(to)}: $r")
        return out.toByteArray()
    }

    /** `jmp` from [at] to [target]: rel32 when it reaches, else `jmp qword ptr [rip+0]` + target. */
    fun jumpBytes(at: Long, target: Long, kind: HookJump? = null): ByteArray {
        val k = kind ?: if (fitsRel32(at + 5, target)) HookJump.REL32 else HookJump.ABS64
        return when (k) {
            HookJump.REL32 -> {
                val d = target - (at + 5)
                require(fitsRel32(at + 5, target)) { "rel32 jump out of range" }
                byteArrayOf(0xE9.toByte(), d.toByte(), (d shr 8).toByte(), (d shr 16).toByte(), (d shr 24).toByte())
            }
            HookJump.ABS64 -> byteArrayOf(0xFF.toByte(), 0x25, 0, 0, 0, 0) + ByteArray(8) { i -> (target shr (8 * i)).toByte() }
        }
    }

    /** A jump written by [jumpBytes] as one listing row (the absolute form's 8-byte target is data, not code). */
    fun jumpRow(at: Long, bytes: ByteArray, target: Long): AsmInstruction =
        if (bytes.size == HookJump.ABS64.size && bytes[0] == 0xFF.toByte() && bytes[1] == 0x25.toByte()) {
            AsmInstruction(at, bytes, "jmp qword ptr [rip] → ${Address.format(target)}", "jmp", Flow.JUMP, target, null, null, valid = true)
        } else {
            X86.decodeOne(bytes, 0, at)
        }

    /**
     * Plans an injection. Nothing is written.
     *
     * @param window bytes read at [hook] ([HOOK_WINDOW], fewer only at the end of the mapping)
     * @param code produces the new code for the address it will run at (Keystone on the PS4, or fixed hex bytes)
     */
    suspend fun plan(
        hook: Long, window: ByteArray, hookRegion: MemoryRegion?, cave: CaveSpace, original: OriginalCode,
        source: String, sourceIsAssembly: Boolean, code: suspend (address: Long) -> ByteArray,
    ): CodeInjection {
        if (hookRegion != null && !hookRegion.isExecutable) {
            throw CodePatchException("${Address.format(hook)} is not in executable memory (${hookRegion.name.ifBlank { "anon" }} ${hookRegion.protString()}).")
        }
        if (cave.capacity <= 0 || cave.current.size < cave.capacity) throw CodePatchException("The cave's current bytes were not read.")
        val jump = jumpFor(hook, cave.address, cave.capacity)
        val stolen = steal(hook, window, jump.size)
        val stolenLen = stolen.sumOf { it.length }
        if (hookRegion != null && !hookRegion.contains(hook, stolenLen)) throw CodePatchException("The jump would cross the end of the code region.")
        val returnAddress = hook + stolenLen
        val caveRange = cave.address until cave.address + cave.capacity
        if (hook < caveRange.last + 1 && caveRange.first < returnAddress) throw CodePatchException("The cave overlaps the hooked instructions.")

        // Code after the hook that jumps back into the middle of the overwritten bytes would land inside the jump.
        val following = X86.decode(window, hook).takeWhile { it.valid }
        following.drop(stolen.size).firstOrNull { t -> t.branchTarget != null && t.branchTarget > hook && t.branchTarget < returnAddress }?.let {
            throw CodePatchException(
                "${Address.format(it.address)} (${it.text}) jumps into the middle of the instructions the hook overwrites. " +
                    "Hook a different instruction.",
            )
        }

        // Layout: [new code][moved original][jmp back] (AFTER), [moved original][new code][jmp back] (BEFORE) or
        // [new code][jmp back] (SKIP). Each part is encoded for its final address.
        val parts = ByteArrayOutputStream()
        var at = cave.address
        var userRange: LongRange
        var movedRange: LongRange? = null
        suspend fun user() {
            val b = code(at)
            if (b.isEmpty()) throw CodePatchException("The new code is empty.")
            userRange = at until at + b.size
            parts.write(b); at += b.size
        }
        fun moved() {
            val b = relocate(stolen, window, at)
            movedRange = at until at + b.size
            parts.write(b); at += b.size
        }
        userRange = LongRange.EMPTY
        when (original) {
            OriginalCode.AFTER -> { user(); moved() }
            OriginalCode.BEFORE -> { moved(); user() }
            OriginalCode.SKIP -> user()
        }
        val exit = jumpBytes(at, returnAddress)
        val exitRange = at until at + exit.size
        parts.write(exit)
        val caveOn = parts.toByteArray()
        if (caveOn.size > MAX_CAVE_CODE) throw CodePatchException("The cave code is ${caveOn.size} bytes (max $MAX_CAVE_CODE).")
        if (caveOn.size > cave.capacity) throw CodePatchException("The cave code needs ${caveOn.size} bytes but the cave has ${cave.capacity}. Pick a larger cave.")
        val caveOff = cave.current.copyOf(caveOn.size)
        if (cave.kind == CaveKind.MODULE_PADDING && caveOff.any { it != caveOff[0] || (it != 0.toByte() && it != 0xCC.toByte()) }) {
            throw CodePatchException("The cave at ${Address.format(cave.address)} is not unused padding any more (it holds code or data).")
        }
        val hookOff = window.copyOf(stolenLen)
        val hookOn = jumpBytes(hook, cave.address, jump) + X86.nops(stolenLen - jump.size)
        val listing = X86.decode(caveOn.copyOf(exitRange.first.minus(cave.address).toInt()), cave.address) + jumpRow(exitRange.first, exit, returnAddress)
        val hookListing = listOf(jumpRow(hook, hookOn.copyOf(jump.size), cave.address)) + X86.decode(hookOn.copyOfRange(jump.size, hookOn.size), hook + jump.size)

        val warnings = ArrayList<String>()
        if (listing.any { !it.valid }) warnings += "The cave code does not all decode as valid instructions."
        if (stolen.size > 1) {
            warnings += "The jump overwrites ${stolen.size} instructions ($stolenLen bytes). Code elsewhere that jumps to " +
                stolen.drop(1).joinToString { Address.format(it.address) } + " would crash the game."
        }
        stolen.filter { it.flow != Flow.NEXT }.forEach { warnings += "A control-flow instruction is moved into the cave: ${it.text}." }
        if (original == OriginalCode.SKIP) warnings += "The original instructions do not run any more; your code must do what the game needs from them."
        if (jump == HookJump.ABS64) warnings += "The cave is more than 2 GiB away: a 14-byte jump is used."
        if (cave.kind == CaveKind.ALLOCATED) warnings += "Allocated memory: this injection lasts only while the game runs and cannot be saved or exported."
        if (cave.kind == CaveKind.MODULE_PADDING && caveOff[0] == 0.toByte() && !cave.atSegmentEnd) {
            warnings += "The cave is zero bytes inside the code mapping; it is very likely padding, but make sure nothing uses it."
        }
        warnings += "Writing the jump is not atomic: a game thread executing these bytes at that instant can crash."

        val signature = CodePatchPlanner.signatureOf(window, hook, stolenLen)
        return CodeInjection(
            hook, hookOff, hookOn, stolen, returnAddress, jump, cave.kind, cave.address, caveOff, caveOn, listing,
            hookListing, userRange, movedRange, exitRange, original, source.trim(), sourceIsAssembly, warnings, signature,
        )
    }
}
