package com.ps4smartcheat.core.cheat

import com.ps4smartcheat.core.asm.AsmInstruction
import com.ps4smartcheat.core.asm.Flow
import com.ps4smartcheat.core.asm.X86
import com.ps4smartcheat.core.util.Hex

/**
 * Safety check of cheat mods before they are exported to a GoldHEN file or switched from the library.
 *
 * GoldHEN writes a mod's "on" bytes when it is enabled and its "off" bytes when it is disabled; a file is only as
 * safe as those bytes. The checks catch, without a game connection:
 *  - "off" that is not the game's original code: an injection jump saved as "off" (the hook was built while another
 *    injection was ON), or a jump into a code cave — writing it sends the game into padding;
 *  - code cut in the middle of an instruction (on or off), invalid instructions, a jump at the hook that does not
 *    lead to the mod's cave, cave code that runs off its end into padding, jumps into the middle of the replaced
 *    instructions, a cave that never returns, 64-bit absolute jumps (their target is this session's address);
 *  - caves whose "off" is not unused padding, cave parts that are not contiguous, a jump without its cave;
 *  - two mods writing the same bytes with different original ("off") bytes, or two injections sharing code.
 *
 * Offsets are relative to the game executable (as in the file); every part of a mod is in that one module, so
 * relative branch targets decode the same as in the game.
 */
object GoldHenSafety {
    enum class Kind {
        /** A typed value (data). */
        VALUE,
        /** Raw bytes (memory viewer): may be data, not decoded. */
        BYTES,
        /** An instruction patch from the disassembler: whole instructions on and off. */
        CODE,
        /** Part of an injection's code cave. */
        CAVE,
        /** The jump of an injection at the hook. */
        HOOK,
    }

    class Part(val label: String, val offset: Long, val on: ByteArray, val off: ByteArray, val kind: Kind) {
        val end: Long get() = offset + on.size
    }

    class Mod(val name: String, val parts: List<Part>)

    /** Problems per mod (same order as the input). A mod with errors must not be exported or switched. */
    class Report(val errors: List<List<String>>, val warnings: List<List<String>>) {
        fun safe(index: Int): Boolean = errors[index].isEmpty()
    }

    /** Kind of a library entry; [group] = the entries of its mod. */
    fun kindOf(c: CheatEntry, group: List<CheatEntry>): Kind = when {
        !c.isPatch -> Kind.VALUE
        c.notes.startsWith(CAVE_NOTE) -> Kind.CAVE
        c.name.endsWith(HOOK_SUFFIX) && group.any { it.notes.startsWith(CAVE_NOTE) } -> Kind.HOOK
        c.assembly != null -> Kind.CODE
        else -> Kind.BYTES
    }

    const val CAVE_NOTE = "Code cave of"
    const val HOOK_SUFFIX = " · jump"

    fun check(mods: List<Mod>): Report {
        val errors = mods.map { ArrayList<String>() }
        val warnings = mods.map { ArrayList<String>() }
        val caves = mods.flatMap { m -> caveRange(m)?.let { listOf(it) } ?: emptyList() }
        mods.forEachIndexed { i, m -> checkMod(m, caves, errors[i]) }
        crossMods(mods, errors, warnings)
        return Report(errors, warnings)
    }

    private fun caveRange(m: Mod): LongRange? {
        val caves = m.parts.filter { it.kind == Kind.CAVE }
        if (caves.isEmpty()) return null
        return caves.minOf { it.offset } until caves.maxOf { it.end }
    }

    private fun checkMod(m: Mod, allCaves: List<LongRange>, out: MutableList<String>) {
        for (p in m.parts) {
            if (p.on.size != p.off.size) out += "${p.label}: on and off have different lengths (${p.on.size} / ${p.off.size} bytes)."
            if (p.on.isEmpty()) out += "${p.label}: no bytes."
        }
        if (out.isNotEmpty()) return

        val caves = m.parts.filter { it.kind == Kind.CAVE }.sortedBy { it.offset }
        val hooks = m.parts.filter { it.kind == Kind.HOOK }
        if (caves.isNotEmpty() && hooks.size != 1) {
            out += if (hooks.isEmpty()) "the code cave has no jump to it — the mod is incomplete." else "the mod has ${hooks.size} jumps to one cave."
            return
        }
        if (hooks.isNotEmpty() && caves.isEmpty()) { out += "${hooks.first().label}: the jump's code cave is missing from the mod."; return }

        // Original bytes must be the game's code: never a jump into a cave, never an injection jump.
        for (p in m.parts.filter { it.kind == Kind.HOOK || it.kind == Kind.CODE }) {
            originalProblem(p, allCaves)?.let { out += it }
        }
        for (p in m.parts.filter { it.kind == Kind.CODE }) {
            cutProblem(p.on, p.offset)?.let { out += "${p.label}: ON $it" }
            cutProblem(p.off, p.offset)?.let { out += "${p.label}: OFF (original) $it" }
        }
        if (caves.isEmpty()) return

        val hook = hooks.single()
        caves.zipWithNext().firstOrNull { (a, b) -> b.offset != a.end }?.let { (a, b) ->
            out += "the cave parts are not contiguous (${a.label} ends at ${hex(a.end)}, ${b.label} starts at ${hex(b.offset)})."
            return
        }
        val caveStart = caves.first().offset
        val caveEnd = caves.last().end
        val caveOff = caves.flatMap { it.off.toList() }
        if (caveOff.any { it != caveOff[0] } || (caveOff[0] != 0.toByte() && caveOff[0] != 0xCC.toByte())) {
            out += "the cave's OFF bytes are not unused padding (00 or CC) — the cave was not empty when the injection was saved."
        }
        if (hook.offset < caveEnd && caveStart < hook.end) out += "the cave overlaps the hook."

        // The hook: one jump to the cave start, then nops, exactly as long as the original instructions.
        val jump = X86.decodeOne(hook.on, 0, hook.offset)
        when {
            isAbsoluteJump(hook.on, 0) -> out += "${hook.label}: a 64-bit absolute jump holds this session's cave address; it is wrong in another game launch. Use a cave within 2 GiB of the hook."
            !jump.valid || jump.flow != Flow.JUMP || jump.branchTarget != caveStart ->
                out += "${hook.label}: ON is not a jump to the cave at ${hex(caveStart)} (it is \"${jump.text}\")."
            else -> {
                val rest = X86.decode(hook.on, hook.offset + jump.length, offset = jump.length)
                if (rest.any { !it.valid || !it.mnemonic.startsWith("nop") } || jump.length + rest.sumOf { it.length } != hook.on.size) {
                    out += "${hook.label}: ON has bytes after the jump that are not whole nops."
                }
            }
        }
        cutProblem(hook.off, hook.offset)?.let { out += "${hook.label}: OFF (original) $it" }
        if (out.isNotEmpty()) return

        // The cave code: every path must end in a jump or return; it may never run off its end into the padding.
        val code = caves.fold(ByteArray(0)) { acc, p -> acc + p.on }
        out += caveFlow(code, caveStart, hook.offset, hook.end)
    }

    /** Problem of an original ("off") instruction sequence that is really an earlier patch. */
    private fun originalProblem(p: Part, caves: List<LongRange>): String? {
        val ins = X86.decode(p.off, p.offset).takeWhile { it.valid && !it.truncated }
        ins.firstOrNull { t -> t.branchTarget != null && caves.any { t.branchTarget in it } }?.let {
            return "${p.label}: OFF (the original code) is \"${it.text}\", a jump into a code cave — it was saved while an injection " +
                "was ON there. Writing it would send the game into the cave's padding. Restart the game, then build and save the injection again."
        }
        val first = ins.firstOrNull()
        if (first != null && p.off[0] == 0xE9.toByte() && first.flow == Flow.JUMP) {
            val nopsAfter = ins.drop(1).all { it.mnemonic.startsWith("nop") }
            if (p.kind == Kind.HOOK || nopsAfter) {
                return "${p.label}: OFF (the original code) starts with \"${first.text}\" — the hook was built while another injection's jump " +
                    "was written there, so its jump was saved as the original. Restart the game (or switch the other injection OFF), then build and save again."
            }
        }
        if (isAbsoluteJump(p.off, 0)) return "${p.label}: OFF (the original code) is a 64-bit absolute jump — an injection jump, not the game's code."
        return null
    }

    /** "ends in the middle of an instruction" etc., or null when [bytes] are whole valid instructions. */
    private fun cutProblem(bytes: ByteArray, address: Long): String? {
        val ins = X86.decode(bytes, address) // an instruction cut by the end of the bytes is left out
        ins.firstOrNull { !it.valid }?.let { return "has an invalid instruction at ${hex(it.address)}." }
        val covered = ins.sumOf { it.length }
        return if (covered == bytes.size) null else "ends in the middle of an instruction at ${hex(address + covered)}."
    }

    /**
     * Follows every path of the cave code from its start (like the CPU would, without running it). Bytes after an
     * unconditional jump (data, `db`) are never decoded.
     */
    private fun caveFlow(code: ByteArray, start: Long, hookStart: Long, returnAddress: Long): List<String> {
        val end = start + code.size
        val out = ArrayList<String>()
        val seen = HashSet<Long>()
        val work = ArrayDeque<Long>().apply { add(start) }
        var returns = 0
        var exits = 0
        fun target(from: AsmInstruction, t: Long) {
            when {
                t in start until end -> work.add(t)
                t == returnAddress -> returns++
                t in hookStart until returnAddress -> out += "cave code at ${hex(from.address)} (${from.text}) jumps into the replaced instructions at the hook — they are now the jump."
                else -> exits++
            }
        }
        while (work.isNotEmpty() && out.size < 5) {
            var at = work.removeFirst()
            while (at !in seen) {
                seen += at
                if (at !in start until end) { out += "cave code runs past the end of the cave at ${hex(at)} into padding."; break }
                val pos = (at - start).toInt()
                if (isAbsoluteJump(code, pos)) {
                    out += "cave code at ${hex(at)} uses a 64-bit absolute jump: its target is this session's address and is wrong in another game launch."
                    break
                }
                val ins = X86.decodeOne(code, pos, at)
                if (ins.truncated || ins.end > end) { out += "cave code at ${hex(at)} ends in the middle of an instruction (the cave is cut)."; break }
                if (!ins.valid) { out += "cave code at ${hex(at)} is not a valid instruction."; break }
                when (ins.flow) {
                    Flow.INTERRUPT -> { out += "cave code at ${hex(at)} is \"${ins.text}\": it stops the game."; break }
                    Flow.RETURN -> { exits++; break }
                    Flow.JUMP -> {
                        val t = ins.branchTarget
                        if (t == null) exits++ else target(ins, t)
                        break
                    }
                    Flow.CONDITIONAL_JUMP -> ins.branchTarget?.let { target(ins, it) }
                    Flow.CALL -> ins.branchTarget?.let { t -> if (t in start until end || t in hookStart until returnAddress) target(ins, t) }
                    else -> Unit
                }
                at = ins.end
                if (at == end) { out += "cave code falls off the end of the cave (no jump back after ${hex(ins.address)})."; break }
            }
        }
        if (out.isEmpty() && returns == 0 && exits == 0) out += "the cave code never leaves the cave (no jmp return)."
        return out
    }

    /** Two mods at the same bytes: different originals mean one was saved over the other; injections never share. */
    private fun crossMods(mods: List<Mod>, errors: List<MutableList<String>>, warnings: List<MutableList<String>>) {
        val all = mods.flatMapIndexed { i, m -> m.parts.map { i to it } }
        for (x in all.indices) for (y in x + 1 until all.size) {
            val (i, a) = all[x]
            val (j, b) = all[y]
            if (i == j) continue
            val from = maxOf(a.offset, b.offset)
            val to = minOf(a.end, b.end)
            if (from >= to) continue
            val code = setOf(Kind.CAVE, Kind.HOOK)
            if (a.kind in code || b.kind in code) {
                errors[i] += "${a.label} overlaps ${b.label} of \"${mods[j].name}\" at ${hex(from)}: two injections at the same code."
                errors[j] += "${b.label} overlaps ${a.label} of \"${mods[i].name}\" at ${hex(from)}: two injections at the same code."
                continue
            }
            val sameOriginal = (from until to).all { o -> a.off[(o - a.offset).toInt()] == b.off[(o - b.offset).toInt()] }
            if (!sameOriginal) {
                val msg = "different original bytes than \"%s\" at ${hex(from)} — one of them was saved while the other was ON."
                errors[i] += "${a.label}: " + msg.format(mods[j].name)
                errors[j] += "${b.label}: " + msg.format(mods[i].name)
            } else {
                warnings[i] += "${a.label} changes the same bytes as \"${mods[j].name}\": enable only one of them."
            }
        }
    }

    /** `jmp qword ptr [rip+0]` (FF 25 00000000) followed by an 8-byte address. */
    private fun isAbsoluteJump(b: ByteArray, pos: Int): Boolean =
        pos + 6 <= b.size && b[pos] == 0xFF.toByte() && b[pos + 1] == 0x25.toByte() &&
            b[pos + 2] == 0.toByte() && b[pos + 3] == 0.toByte() && b[pos + 4] == 0.toByte() && b[pos + 5] == 0.toByte()

    private fun hex(offset: Long) = "+0x" + java.lang.Long.toHexString(offset).uppercase()

    /** Parts of a library mod (entries of one group), for [check]. */
    fun parts(members: List<CheatEntry>, offset: (CheatEntry) -> Long?, on: (CheatEntry) -> ByteArray?): List<Part> =
        members.mapNotNull { c ->
            val o = offset(c) ?: return@mapNotNull null
            val bytesOn = on(c) ?: return@mapNotNull null
            val off = c.originalBytes?.let { runCatching { Hex.decode(it) }.getOrNull() } ?: return@mapNotNull null
            Part(c.name, o, bytesOn, off, kindOf(c, members))
        }
}
