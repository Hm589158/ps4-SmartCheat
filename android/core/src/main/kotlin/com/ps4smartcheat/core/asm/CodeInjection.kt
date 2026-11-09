package com.ps4smartcheat.core.asm

import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.util.Address
import com.ps4smartcheat.core.util.Hex

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
 * A planned code-cave injection ("Assembly injection"): the instructions at [hookAddress] (the Source Code, [stolen])
 * are replaced by a jump into the cave; the cave holds the Target Code ([source], see [CaveScript]) — the user's code
 * with the original instructions wherever the user kept them and `jmp return` back to [returnAddress] wherever the
 * user wants to go back. Enable = write [caveOn] then [hookOn]; disable = write [hookOff] first, then [caveOff].
 */
class CodeInjection(
    val hookAddress: Long,
    val hookOff: ByteArray,
    val hookOn: ByteArray,
    /** The Source Code: original instructions overwritten by the jump. */
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
    /** The cave code by origin (typed code, moved original instruction, `jmp return`, `db` bytes). */
    val parts: List<CavePart>,
    /** Labels of the Target Code by address (for the listing). */
    val labels: Map<Long, List<String>>,
    /** Source instructions that no longer run (the Target Code left them out). */
    val replaced: List<AsmInstruction>,
    /** The Target Code as typed. */
    val source: String,
    val warnings: List<String>,
    /** AOB signature of the original code at the hook (constants wildcarded). */
    val signature: String,
) {
    val caveRange: LongRange get() = caveAddress until caveAddress + caveOn.size

    /** The part of the cave code that holds [address]. */
    fun partAt(address: Long): CavePart? = parts.firstOrNull { address in it.range }

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
        caveListing.forEach { ins ->
            labels[ins.address]?.forEach { append(it).append(":\n") }
            append("  ").append(Address.format(ins.address)).append("  ").append(ins.text).append('\n')
        }
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

    /** A rel32 branch at [from] reaches [to] (with room for the code in between). */
    fun reaches(from: Long, to: Long): Boolean = fitsRel32(from + MAX_CAVE_CODE, to) && fitsRel32(from, to)

    /**
     * The Source Code: the whole instructions at [hook] that a jump to a cave at [cave] overwrites — at least
     * [replaceBytes] bytes when the user chose to replace more than the jump needs.
     */
    fun sourceFor(hook: Long, window: ByteArray, cave: Long?, capacity: Int, replaceBytes: Int = 0): List<AsmInstruction> {
        val jump = if (cave == null) HookJump.REL32 else jumpFor(hook, cave, capacity)
        return steal(hook, window, maxOf(jump.size, replaceBytes))
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

    /** Target Code a new injection starts with: the Source instructions, then `jmp return`. */
    fun template(stolen: List<AsmInstruction>): String = CaveScript.template(stolen)

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
     * @param target the Target Code (see [CaveScript])
     * @param replaceBytes overwrite at least this many bytes at the hook (whole instructions; the jump's size is the minimum)
     * @param assemble assembles typed instructions for the address they will run at (Keystone on the PS4)
     */
    suspend fun plan(
        hook: Long, window: ByteArray, hookRegion: MemoryRegion?, cave: CaveSpace, target: String,
        replaceBytes: Int = 0, assemble: suspend (text: String, address: Long) -> ByteArray,
    ): CodeInjection {
        if (hookRegion != null && !hookRegion.isExecutable) {
            throw CodePatchException("${Address.format(hook)} is not in executable memory (${hookRegion.name.ifBlank { "anon" }} ${hookRegion.protString()}).")
        }
        if (cave.capacity <= 0 || cave.current.size < cave.capacity) throw CodePatchException("The cave's current bytes were not read.")
        val jump = jumpFor(hook, cave.address, cave.capacity)
        val stolen = steal(hook, window, maxOf(jump.size, replaceBytes))
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

        val stmts = CaveScript.parse(target, stolen + following.drop(stolen.size))
        val linked = CaveScript.link(stmts, cave.address, returnAddress, assemble)
        val caveOn = linked.bytes
        if (caveOn.size > MAX_CAVE_CODE) throw CodePatchException("The cave code is ${caveOn.size} bytes (max $MAX_CAVE_CODE).")
        if (caveOn.size > cave.capacity) throw CodePatchException("The cave code needs ${caveOn.size} bytes but the cave has ${cave.capacity}. Pick a larger cave.")
        val caveOff = cave.current.copyOf(caveOn.size)
        if (cave.kind == CaveKind.MODULE_PADDING && caveOff.any { it != caveOff[0] || (it != 0.toByte() && it != 0xCC.toByte()) }) {
            throw CodePatchException("The cave at ${Address.format(cave.address)} is not unused padding any more (it holds code or data).")
        }
        val hookOff = window.copyOf(stolenLen)
        val hookOn = jumpBytes(hook, cave.address, jump) + X86.nops(stolenLen - jump.size)
        // Each part is decoded on its own: an absolute jump's 8-byte target is data, and `db` bytes need not decode.
        val listing = linked.parts.flatMap { p ->
            if (p.kind == CavePartKind.RETURN) listOf(jumpRow(p.address, p.bytes, returnAddress)) else X86.decode(p.bytes, p.address)
        }
        val hookListing = listOf(jumpRow(hook, hookOn.copyOf(jump.size), cave.address)) + X86.decode(hookOn.copyOfRange(jump.size, hookOn.size), hook + jump.size)
        val labels = linked.labels.filterKeys { it != CaveScript.RETURN }.entries.groupBy({ it.value }, { it.key })
        val replaced = stolen.filter { it.address !in linked.keptOriginals }

        val warnings = ArrayList<String>()
        if (listing.any { !it.valid }) warnings += "The cave code does not all decode as valid instructions."
        if (stolen.size > 1) {
            warnings += "The jump overwrites ${stolen.size} instructions ($stolenLen bytes). Code elsewhere that jumps to " +
                stolen.drop(1).joinToString { Address.format(it.address) } + " would crash the game."
        }
        stolen.filter { it.flow != Flow.NEXT && it.address in linked.keptOriginals }.forEach { warnings += "A control-flow instruction is moved into the cave: ${it.text}." }
        if (replaced.isNotEmpty()) {
            warnings += "Not in the Target Code, so no longer run: " + replaced.joinToString("; ") { it.text } + ". Your code must do what the game needs from them."
        }
        if (linked.returns == 0) warnings += "The Target Code never jumps back with \"jmp return\"; make sure its own jmp/ret leads somewhere valid."
        if (jump == HookJump.ABS64) warnings += "The cave is more than 2 GiB away: a 14-byte jump is used."
        if (cave.kind == CaveKind.ALLOCATED) warnings += "Allocated memory: this injection lasts only while the game runs and cannot be saved or exported."
        if (cave.kind == CaveKind.MODULE_PADDING && caveOff[0] == 0.toByte() && !cave.atSegmentEnd) {
            warnings += "The cave is zero bytes inside the code mapping; it is very likely padding, but make sure nothing uses it."
        }
        warnings += "Writing the jump is not atomic: a game thread executing these bytes at that instant can crash."

        val signature = CodePatchPlanner.signatureOf(window, hook, stolenLen)
        return CodeInjection(
            hook, hookOff, hookOn, stolen, returnAddress, jump, cave.kind, cave.address, caveOff, caveOn, listing,
            hookListing, linked.parts, labels, replaced, target.trim(), warnings, signature,
        )
    }
}
