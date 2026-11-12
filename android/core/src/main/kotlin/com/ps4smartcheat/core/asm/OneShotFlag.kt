package com.ps4smartcheat.core.asm

import kotlinx.serialization.Serializable

/**
 * The 32-bit displacement of a cave instruction that addresses the injection's one-shot flag (`[flag]` in the Target
 * Code), as offsets from the start of the bytes it belongs to (the cave, or one saved part of it): the field is at
 * [at] (4 bytes) and RIP-relative addressing counts from [next], the end of the instruction.
 */
@Serializable
data class FlagField(val at: Int, val next: Int)

/**
 * One-shot injections: Target Code can use `[flag]`, a byte (of [SIZE] reserved bytes) that starts at 0 in every game
 * session, e.g. to run a change only the first time a condition holds:
 *
 * ```
 * cmp byte ptr [flag], 0
 * jne once_done
 * mov byte ptr [flag], 1
 * ...                      ; runs once
 * once_done:
 * ```
 *
 * The flag is never inside the cave (code memory is not writable, and saved code bytes must not change while the game
 * runs). Each injection gets its own small allocation near the cave (ps4debug-NG CMD_PROC_ALLOC_HINTED, validated
 * against the memory map), made when the injection is switched ON and freed after a verified OFF. New memory reads
 * 0, so the flag starts at 0; a game restart loses it, and switching ON again makes a new one.
 *
 * The cave reaches the flag RIP-relative, so its bytes depend on where the flag is in this session. Saved injections
 * keep those 4-byte fields at 0 ([template]) plus their positions ([FlagField]); every ON binds them to the new flag
 * ([bind]). The ON/OFF check compares everything but the fields, and requires the fields to be bound ([matchesOn]).
 * GoldHEN cannot allocate memory, so one-shot injections are not exported to GoldHEN files.
 */
object OneShotFlag {
    const val NAME = "flag"
    /** Bytes reserved for one flag (byte, word, dword and qword accesses all fit). */
    const val SIZE = 8

    private val OPERAND = Regex("\\[\\s*flag\\s*]", RegexOption.IGNORE_CASE)
    private val ANY_USE = Regex("(?<![A-Za-z0-9_.@$])flag(?![A-Za-z0-9_.@$])", RegexOption.IGNORE_CASE)

    /** True when [line] uses the flag; throws for forms other than `[flag]` (e.g. `[flag+1]` or a jump to it). */
    fun uses(line: String): Boolean {
        val operands = line.substringAfter(' ', "")
        if (!ANY_USE.containsMatchIn(operands)) return false
        if (OPERAND.findAll(operands).count() != ANY_USE.findAll(operands).count()) {
            throw CodePatchException("\"$line\": the one-shot flag is used as a memory operand, exactly \"[flag]\" (e.g. cmp byte ptr [flag], 0).")
        }
        return true
    }

    /** [line] with `[flag]` replaced by a RIP-relative operand with displacement [disp]. */
    fun ripForm(line: String, disp: Long): String =
        line.replace(OPERAND, if (disp < 0) "[rip - 0x${java.lang.Long.toHexString(-disp)}]" else "[rip + 0x${java.lang.Long.toHexString(disp)}]")

    /** The guard the editor inserts: runs the code between it and `once_done:` once per flag. */
    fun guard(label: String): String = "cmp byte ptr [flag], 0\njne $label\nmov byte ptr [flag], 1\n\n$label:"

    /**
     * [bytes] (located at [address]) with every field of [fields] (offsets relative to [address]) set to reach [flag].
     * Fields must lie inside [bytes].
     */
    fun bind(bytes: ByteArray, address: Long, fields: List<FlagField>, flag: Long): ByteArray {
        val out = bytes.copyOf()
        for (f in fields) {
            require(f.at >= 0 && f.at + 4 <= out.size) { "flag field outside the bytes" }
            val disp = flag - (address + f.next)
            if (disp !in Int.MIN_VALUE..Int.MAX_VALUE) throw CodePatchException("The one-shot flag at 0x%X is more than 2 GiB from the cave.".format(flag))
            for (i in 0 until 4) out[f.at + i] = (disp shr (8 * i)).toByte()
        }
        return out
    }

    /** [bytes] with the fields set to 0: the session-independent form that is saved. */
    fun template(bytes: ByteArray, fields: List<FlagField>): ByteArray {
        val out = bytes.copyOf()
        for (f in fields) for (i in 0 until 4) out[f.at + i] = 0
        return out
    }

    /**
     * Memory [now] holds the saved ON bytes [onTemplate]: equal outside the fields, and every field bound (not 0 — a
     * displacement of 0 would address the next instruction, never a flag).
     */
    fun matchesOn(now: ByteArray, onTemplate: ByteArray, fields: List<FlagField>): Boolean {
        if (now.size != onTemplate.size) return false
        val masked = BooleanArray(now.size)
        for (f in fields) {
            for (i in 0 until 4) masked[f.at + i] = true
            if ((0 until 4).all { now[f.at + it] == 0.toByte() }) return false
        }
        return now.indices.all { masked[it] || now[it] == onTemplate[it] }
    }

    /** The flag address the bytes [now] at [address] are bound to (from the first field), or null. */
    fun boundTo(now: ByteArray, address: Long, fields: List<FlagField>): Long? {
        val f = fields.firstOrNull() ?: return null
        if (f.at + 4 > now.size) return null
        var disp = 0L
        for (i in 3 downTo 0) disp = (disp shl 8) or (now[f.at + i].toLong() and 0xFF)
        return address + f.next + disp.toInt().toLong()
    }

    /**
     * Cuts a cave of [size] bytes into saved parts of at most [max] bytes without splitting a field: the start offsets.
     */
    fun chunkStarts(size: Int, max: Int, fields: List<FlagField>): List<Int> {
        val starts = ArrayList<Int>()
        var at = 0
        while (at < size) {
            starts += at
            var end = minOf(at + max, size)
            fields.firstOrNull { it.at < end && it.at + 4 > end }?.let { end = it.at } // do not cut through a field
            if (end <= at) end = minOf(at + max, size)
            at = end
        }
        return starts
    }

    /** The fields inside [from, to) of the cave, relative to [from]. */
    fun fieldsIn(fields: List<FlagField>, from: Int, to: Int): List<FlagField> =
        fields.filter { it.at >= from && it.at + 4 <= to }.map { FlagField(it.at - from, it.next - from) }
}
