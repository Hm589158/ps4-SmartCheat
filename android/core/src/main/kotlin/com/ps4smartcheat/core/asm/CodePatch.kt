package com.ps4smartcheat.core.asm

import com.ps4smartcheat.core.cheat.PatchState
import com.ps4smartcheat.core.edit.MAX_PATCH_BYTES
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.util.Address
import com.ps4smartcheat.core.util.Hex

/**
 * A code modification that replaces whole instructions at [address].
 * [on] = new instructions padded with NOPs to the size of the replaced instructions; [off] = the original bytes.
 * Toggling writes [on] (ON) or [off] (OFF) — the same model as a GoldHEN checkbox mod.
 */
class CodePatch(
    val address: Long,
    val off: ByteArray,
    val on: ByteArray,
    /** Assembly the patch was built from (null when entered as hex). */
    val source: String?,
    /** Original instructions that are overwritten. */
    val replaced: List<AsmInstruction>,
    /** [on] decoded, for the preview. */
    val result: List<AsmInstruction>,
    /** Things the user must read before confirming. */
    val warnings: List<String>,
    /** AOB signature of the original code (constants wildcarded), used to find the spot again after an update. */
    val signature: String,
) {
    val length: Int get() = off.size

    fun stateOf(current: ByteArray): PatchState = when {
        current.contentEquals(off) -> PatchState.OFF
        current.contentEquals(on) -> PatchState.ON
        else -> PatchState.MISMATCH
    }

    /** Side-by-side preview lines: original instruction → new instruction. */
    fun previewText(): String = buildString {
        append("Original (OFF):\n")
        replaced.forEach { append("  ").append(Address.format(it.address)).append("  ").append(Hex.encode(it.bytes, " ")).append("  ").append(it.text).append('\n') }
        append("Patched (ON):\n")
        result.forEach { append("  ").append(Address.format(it.address)).append("  ").append(Hex.encode(it.bytes, " ")).append("  ").append(it.text).append('\n') }
    }
}

class CodePatchException(message: String) : Exception(message)

object CodePatchPlanner {
    /** Bytes to read at the patch address: enough for the largest patch plus one maximal instruction. */
    const val WINDOW_BYTES = MAX_PATCH_BYTES + X86.MAX_INSTRUCTION_LENGTH

    /**
     * Plans replacing the instructions at [address] with [newBytes].
     * @param window bytes read at [address] (at least `newBytes.size + 15`, see [WINDOW_BYTES])
     * @param region the mapping containing [address], when known
     */
    fun plan(address: Long, window: ByteArray, newBytes: ByteArray, source: String?, region: MemoryRegion?): CodePatch {
        if (newBytes.isEmpty()) throw CodePatchException("The new code is empty.")
        if (newBytes.size > MAX_PATCH_BYTES) throw CodePatchException("The new code is ${newBytes.size} bytes; at most $MAX_PATCH_BYTES bytes can be patched at once.")
        if (region != null) {
            if (!region.isExecutable) throw CodePatchException("${Address.format(address)} is not in executable memory (${region.name.ifBlank { "anon" }} ${region.protString()}). Use the value editor for data.")
        }
        val replaced = ArrayList<AsmInstruction>()
        var covered = 0
        while (covered < newBytes.size) {
            if (covered >= window.size) throw CodePatchException("Not enough original bytes were read.")
            val ins = X86.decodeOne(window, covered, address + covered)
            if (!ins.valid) {
                throw CodePatchException(
                    "The original bytes at ${Address.format(address + covered)} do not decode as an instruction. " +
                        "Start the patch at an instruction boundary (open the address from the disassembly).",
                )
            }
            replaced += ins
            covered += ins.length
        }
        if (covered > MAX_PATCH_BYTES) throw CodePatchException("Replacing whole instructions needs $covered bytes (max $MAX_PATCH_BYTES).")
        if (region != null && !region.contains(address, covered)) throw CodePatchException("The patch would cross the end of the code region.")
        val off = window.copyOfRange(0, covered)
        val on = newBytes + X86.nops(covered - newBytes.size)
        if (on.contentEquals(off)) throw CodePatchException("The new code is identical to the original — nothing to patch.")
        val result = X86.decode(on, address)
        val warnings = ArrayList<String>()
        if (result.any { !it.valid }) warnings += "The new bytes do not all decode as valid instructions."
        if (replaced.size > 1) {
            warnings += "Replaces ${replaced.size} instructions (${covered} bytes). If other code jumps into the middle " +
                "(${replaced.drop(1).joinToString { Address.format(it.address) }}), the game can crash."
        }
        replaced.filter { it.flow != Flow.NEXT }.forEach { warnings += "Overwrites a control-flow instruction: ${it.text}." }
        if (covered > newBytes.size) warnings += "${covered - newBytes.size} byte(s) of NOP padding keep the following instructions intact."
        val sigTail = X86.decode(window, address).let { all ->
            // Signature: the replaced instructions plus following ones up to 16 bytes, ending early at a return or
            // jump (code after it may be unrelated and differ between builds).
            val list = ArrayList<AsmInstruction>()
            var n = 0
            for (i in all) {
                if (!i.valid || n >= maxOf(16, covered)) break
                list += i; n += i.length
                if (n >= covered && (i.flow == Flow.RETURN || i.flow == Flow.JUMP)) break
            }
            list
        }
        return CodePatch(address, off, on, source?.trim(), replaced, result, warnings, X86.signature(sigTail))
    }

    /** Parses hex such as `90 90` or `0x9090` into bytes; throws [CodePatchException] for bad input. */
    fun parseHex(text: String): ByteArray = try {
        Hex.decode(text.replace("0x", "", ignoreCase = true).replace(",", " "))
    } catch (e: Exception) {
        throw CodePatchException("Hex bytes like \"90 90\" expected")
    }
}
