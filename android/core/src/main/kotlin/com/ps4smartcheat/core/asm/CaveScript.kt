package com.ps4smartcheat.core.asm

import com.github.icedland.iced.x86.CodeWriter
import com.github.icedland.iced.x86.Instruction
import com.github.icedland.iced.x86.dec.Decoder
import com.github.icedland.iced.x86.enc.BlockEncoder
import com.github.icedland.iced.x86.enc.InstructionBlock
import com.ps4smartcheat.core.util.Address
import java.io.ByteArrayOutputStream

/** What a part of the cave code came from. */
enum class CavePartKind(val tag: String) {
    /** Instructions typed by the user (assembled on the PS4). */
    CODE("new"),
    /** An instruction from the Source Code, moved here and re-encoded for its new address. */
    ORIGINAL("orig"),
    /** `jmp return`: back to the instruction after the hook. */
    RETURN("ret"),
    /** Raw bytes from a `db` line. */
    BYTES("db"),
}

/** One encoded part of the cave code. */
class CavePart(val kind: CavePartKind, val address: Long, val bytes: ByteArray, val text: String) {
    val range: LongRange get() = address until address + bytes.size
}

/**
 * The Target Code of an injection: what runs in the cave instead of the hooked instructions.
 *
 * It is prefilled with the hooked (Source) instructions followed by `jmp return`; the user adds code before, after or
 * between them, edits or deletes them, and may return from any place, as often as needed:
 *
 * ```
 * cmp ebx, 5
 * jne return        // conditional return: skips the original code
 * sub eax, 1        // a line identical to a Source instruction is moved, not re-assembled
 * mov dword ptr [0x500120], eax
 * jmp return        // back to the game, right after the hook
 * ```
 *
 * Lines are statements as in [CodeTools.normalise] (`//` and `#` comments, `;` also separates statements). Besides
 * instructions for the assembler it understands:
 *  - `return` – the address after the hooked instructions; usable by any branch (`jmp return`, `je return`, …).
 *    `jmp return` alone is encoded on the phone (rel32, or an absolute jump when the cave is more than 2 GiB away).
 *  - `name:` – a label; any branch or call may target it (resolved on the phone, so labels work across the moved
 *    original instructions too).
 *  - a line equal to one of the Source instructions (spacing and case ignored) – that instruction, relocated with
 *    iced's BlockEncoder (RIP-relative operands and branches keep their targets). Re-assembling the displayed text
 *    instead would be wrong: it shows RIP-relative operands as absolute addresses.
 *  - `db 90 90` / `db 0x90, 0x90` – raw bytes.
 *
 * Without the PS4 assembler (classic ps4debug) Target Code made only of Source instructions, `db` and `jmp return`
 * still works: none of those need Keystone.
 */
object CaveScript {
    /** Name of the return label (the instruction after the hooked bytes). */
    const val RETURN = "return"
    private const val MAX_PASSES = 10

    sealed interface Stmt {
        val text: String
        data class Label(val name: String, override val text: String) : Stmt
        /** A line equal to [ins] (one of the instructions at the hook). */
        data class Original(val ins: AsmInstruction, override val text: String) : Stmt
        data class Return(override val text: String) : Stmt
        data class Bytes(val bytes: ByteArray, override val text: String) : Stmt
        data class Asm(override val text: String) : Stmt
    }

    /** Assembled Target Code, located at the cave address. */
    class Linked(
        val bytes: ByteArray,
        val parts: List<CavePart>,
        /** Label name → address (includes [RETURN]). */
        val labels: Map<String, Long>,
        /** Addresses of the hook instructions the Target Code contains (the others no longer run). */
        val keptOriginals: Set<Long>,
        val returns: Int,
        /** Fields that address the one-shot flag, relative to the cave start (empty when `[flag]` is not used). */
        val flagFields: List<FlagField> = emptyList(),
    ) {
        val usesFlag: Boolean get() = flagFields.isNotEmpty()
    }

    private val LABEL = Regex("^([A-Za-z_.@$][A-Za-z0-9_.@$]*)\\s*:(?!:)\\s*(.*)$")
    private val REGISTER = Regex(
        "^(r(ax|bx|cx|dx|si|di|bp|sp|ip)|e(ax|bx|cx|dx|si|di|bp|sp|ip)|[abcd][xlh]|[sd]il?|[bs]pl?|r([89]|1[0-5])[dwb]?|" +
            "[xyz]mm([0-9]|[12][0-9]|3[01])|[cdefgs]s)$",
        RegexOption.IGNORE_CASE,
    )

    /** Canonical form used to recognise a Source instruction in the Target Code. */
    fun canonical(text: String): String = text.lowercase().replace(Regex("\\s+"), "")

    /** The Target Code a new injection starts with: the Source instructions, then `jmp return`. */
    fun template(stolen: List<AsmInstruction>): String =
        (stolen.map { it.text } + "jmp $RETURN").joinToString("\n")

    /** The Source Code shown read-only: one instruction per line. */
    fun source(stolen: List<AsmInstruction>): String = stolen.joinToString("\n") { it.text }

    /**
     * Splits [target] into statements. [known] are the instructions decoded at the hook (the Source instructions and
     * the ones after them): a line equal to one of them is that instruction, moved. Throws [CodePatchException] for
     * malformed labels or `db` lines.
     */
    fun parse(target: String, known: List<AsmInstruction>): List<Stmt> {
        // Identical instructions (e.g. two `sub eax, 1`) are matched in order, so each one counts as kept.
        val originals = known.filter { it.valid }.groupBy { canonical(it.text) }
        val used = HashMap<String, Int>()
        fun original(canon: String): AsmInstruction {
            val list = originals.getValue(canon)
            val n = used.merge(canon, 1, Int::plus)!! - 1
            return list[minOf(n, list.size - 1)]
        }
        val out = ArrayList<Stmt>()
        for (raw in CodeTools.normalise(target).lines()) {
            var line = raw.trim()
            while (true) {
                val m = LABEL.find(line) ?: break
                val name = m.groupValues[1]
                if (name.equals(RETURN, ignoreCase = true)) throw CodePatchException("\"$RETURN\" is reserved for the jump back; pick another label name.")
                if (name.equals(OneShotFlag.NAME, ignoreCase = true)) throw CodePatchException("\"${OneShotFlag.NAME}\" is reserved for the one-shot flag; pick another label name.")
                if (REGISTER.matches(name)) throw CodePatchException("\"$name\" is a register name; pick another label name.")
                out += Stmt.Label(name, "$name:")
                line = m.groupValues[2].trim()
            }
            if (line.isEmpty()) continue
            val canon = canonical(line)
            val mnemonic = line.substringBefore(' ').lowercase()
            out += when {
                canon == "jmp$RETURN" -> Stmt.Return(line)
                canon in originals -> Stmt.Original(original(canon), line)
                mnemonic == "db" || mnemonic == ".byte" -> Stmt.Bytes(parseBytes(line.substringAfter(' ', "")), line)
                else -> Stmt.Asm(line).also { OneShotFlag.uses(line) } // validates the [flag] form early
            }
        }
        val dup = out.filterIsInstance<Stmt.Label>().groupBy { it.name }.filterValues { it.size > 1 }.keys.firstOrNull()
        if (dup != null) throw CodePatchException("The label \"$dup\" is defined more than once.")
        return out
    }

    private fun parseBytes(text: String): ByteArray {
        val tokens = text.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) throw CodePatchException("\"db\" needs bytes, e.g. db 90 90")
        return ByteArray(tokens.size) { i ->
            val t = tokens[i].removePrefix("0x").removePrefix("0X").removeSuffix("h").removeSuffix("H")
            if (t.isEmpty() || t.length > 2 || t.any { Character.digit(it, 16) < 0 }) throw CodePatchException("\"${tokens[i]}\" is not a byte (db takes hex bytes like 90 or 0x90).")
            t.toInt(16).toByte()
        }
    }

    /** One original instruction re-encoded for [to] (RIP-relative operands and branches keep their targets). */
    fun relocate(ins: AsmInstruction, to: Long): ByteArray {
        val decoder = Decoder(64, ins.bytes, ins.address, 0)
        val list = arrayListOf<Instruction>(decoder.decode())
        val out = ByteArrayOutputStream()
        val r = BlockEncoder.tryEncode(64, InstructionBlock(CodeWriter { b -> out.write(b.toInt()) }, list, to))
        if (r is String) throw CodePatchException("\"${ins.text}\" cannot be moved to ${Address.format(to)}: $r")
        return out.toByteArray()
    }

    /**
     * Assembles one `[flag]` line at [at] RIP-relative to [flag]: first with displacement 0 to learn the length (a
     * RIP-relative displacement is always 32 bits), then with the real one. Checks with the decoder that the result
     * addresses the flag. Returns the bytes and the offset of the displacement in them.
     */
    private suspend fun assembleFlagLine(
        line: String, at: Long, flag: Long, cache: HashMap<Pair<String, Long>, ByteArray>,
        assemble: suspend (text: String, address: Long) -> ByteArray,
    ): Pair<ByteArray, Int> {
        val probeText = OneShotFlag.ripForm(line, 0)
        val probe = cache.getOrPut(probeText to at) { assemble(probeText, at) }
        if (probe.isEmpty()) throw CodePatchException("The assembler produced no bytes for: $line")
        val disp = flag - (at + probe.size)
        if (disp !in Int.MIN_VALUE..Int.MAX_VALUE) throw CodePatchException("\"$line\": the one-shot flag is more than 2 GiB from the cave.")
        val text = OneShotFlag.ripForm(line, disp)
        val bytes = cache.getOrPut(text to at) { assemble(text, at) }
        val decoder = Decoder(64, bytes, at, 0)
        val ins = decoder.decode()
        if (bytes.size != probe.size || ins.length != bytes.size || !ins.isIPRelativeMemoryOperand || ins.ipRelativeMemoryAddress() != flag) {
            throw CodePatchException("\"$line\" could not be encoded to address the one-shot flag (one instruction with one [flag] operand).")
        }
        return bytes to decoder.getConstantOffsets(ins).displacementOffset.toInt()
    }

    /** Replaces label names used as operands with their address (`jne skip` → `jne 0x40F010`). */
    private fun substitute(text: String, labels: Map<String, Long>): String {
        val mnemonic = text.substringBefore(' ')
        val operands = text.substring(mnemonic.length)
        if (operands.isBlank()) return text
        val replaced = Regex("(?<![A-Za-z0-9_.@$])[A-Za-z_.@$][A-Za-z0-9_.@$]*").replace(operands) { m ->
            val key = labelKey(m.value, labels) ?: return@replace m.value
            val before = operands.substring(0, m.range.first)
            if (before.count { it == '[' } > before.count { it == ']' }) {
                throw CodePatchException("\"$text\": labels and \"$RETURN\" can only be jump or call targets, not memory operands.")
            }
            "0x" + java.lang.Long.toHexString(labels.getValue(key)).uppercase()
        }
        return mnemonic + replaced
    }

    private fun labelKey(word: String, labels: Map<String, Long>): String? = when {
        word in labels -> word
        word.equals(RETURN, ignoreCase = true) -> RETURN
        else -> null
    }

    /** How many statements of [stmts] go back to `return` (`jmp return`, `je return`, …). */
    fun returnsIn(stmts: List<Stmt>): Int = stmts.count { it is Stmt.Return || (it is Stmt.Asm && referencesReturn(it.text)) }

    private fun referencesReturn(text: String) =
        Regex("(?<![A-Za-z0-9_.@$])$RETURN(?![A-Za-z0-9_.@$])", RegexOption.IGNORE_CASE).containsMatchIn(text.substringAfter(' ', ""))

    /**
     * Encodes [stmts] for a cave at [cave] that returns to [returnAddress]. User instructions are assembled by
     * [assemble] at their final address, a run of consecutive ones at a time. Label addresses depend on the size of
     * what precedes them (the assembler may pick short or near branches), so the layout is repeated until every label
     * stays where it was in the previous pass.
     */
    /** True when one of [stmts] uses the one-shot flag (`[flag]`). */
    fun usesFlag(stmts: List<Stmt>): Boolean = stmts.any { it is Stmt.Asm && OneShotFlag.uses(it.text) }

    /**
     * @param flag address of the one-shot flag that `[flag]` lines address (RIP-relative, so within 2 GiB of the cave);
     *   required when the Target Code uses `[flag]`. Each such line is assembled on its own, twice: once to learn its
     *   length, once with the displacement to the flag; the position of the displacement is returned in
     *   [Linked.flagFields] so it can be bound to another flag later ([OneShotFlag.bind]).
     */
    suspend fun link(
        stmts: List<Stmt>, cave: Long, returnAddress: Long,
        flag: Long? = null,
        assemble: suspend (text: String, address: Long) -> ByteArray,
    ): Linked {
        if (stmts.none { it !is Stmt.Label }) throw CodePatchException("The Target Code is empty. Keep the original instructions and \"jmp $RETURN\", or write your own.")
        val last = stmts.last { it !is Stmt.Label }
        val endsSafely = when (last) {
            is Stmt.Return -> true
            is Stmt.Original -> last.ins.flow == Flow.JUMP || last.ins.flow == Flow.RETURN
            is Stmt.Asm -> last.text.substringBefore(' ').lowercase() in setOf("jmp", "ret", "retn", "ud2", "int3")
            is Stmt.Bytes, is Stmt.Label -> false
        }
        if (!endsSafely) {
            throw CodePatchException("The Target Code must end with \"jmp $RETURN\" (or another jmp/ret): after \"${last.text}\" the CPU would run into the bytes after the cave.")
        }
        val far = !InjectionPlanner.reaches(cave, returnAddress)
        if (far) {
            stmts.filterIsInstance<Stmt.Asm>().firstOrNull { referencesReturn(it.text) }?.let {
                throw CodePatchException(
                    "\"${it.text}\": the cave is more than 2 GiB from the hook, so only a plain \"jmp $RETURN\" can go back. " +
                        "For a condition, branch to a label with \"jmp $RETURN\" after it.",
                )
            }
        }

        if (usesFlag(stmts) && flag == null) throw CodePatchException("The Target Code uses [flag] but no flag address was given.")
        // Runs of consecutive assembler statements are assembled together; a [flag] line is assembled on its own.
        fun flagged(s: Any?) = s is Stmt.Asm && OneShotFlag.uses(s.text)
        val segments = ArrayList<Any>()
        for (s in stmts) {
            val prev = segments.lastOrNull()
            if (s is Stmt.Asm && prev is MutableList<*> && !flagged(s) && !flagged(prev.last())) {
                @Suppress("UNCHECKED_CAST")
                (prev as MutableList<Stmt.Asm>) += s
            } else {
                segments += if (s is Stmt.Asm) mutableListOf(s) else s
            }
        }
        val cache = HashMap<Pair<String, Long>, ByteArray>()
        val names = stmts.filterIsInstance<Stmt.Label>().map { it.name }
        var labels: Map<String, Long> = names.associateWith { cave } + (RETURN to returnAddress)
        repeat(MAX_PASSES) {
            val parts = ArrayList<CavePart>()
            val flagFields = ArrayList<FlagField>()
            val found = LinkedHashMap<String, Long>()
            var at = cave
            for (seg in segments) {
                when (seg) {
                    is Stmt.Label -> { found[seg.name] = at; continue }
                    is Stmt.Original -> parts += CavePart(CavePartKind.ORIGINAL, at, relocate(seg.ins, at), seg.ins.text)
                    is Stmt.Return -> parts += CavePart(CavePartKind.RETURN, at, InjectionPlanner.jumpBytes(at, returnAddress), "jmp $RETURN")
                    is Stmt.Bytes -> parts += CavePart(CavePartKind.BYTES, at, seg.bytes, seg.text)
                    is MutableList<*> -> if (seg.size == 1 && flagged(seg.first())) {
                        val line = (seg.single() as Stmt.Asm).text
                        val (bytes, field) = assembleFlagLine(substitute(line, labels), at, flag!!, cache, assemble)
                        flagFields += FlagField((at - cave).toInt() + field, (at - cave).toInt() + bytes.size)
                        parts += CavePart(CavePartKind.CODE, at, bytes, line)
                    } else {
                        val text = seg.joinToString("\n") { substitute((it as Stmt.Asm).text, labels) }
                        val bytes = cache.getOrPut(text to at) { assemble(text, at) }
                        if (bytes.isEmpty()) throw CodePatchException("The assembler produced no bytes for: ${seg.joinToString("; ") { (it as Stmt.Asm).text }}")
                        parts += CavePart(CavePartKind.CODE, at, bytes, seg.joinToString("\n") { (it as Stmt.Asm).text })
                    }
                }
                at += parts.last().bytes.size
                if (at - cave > InjectionPlanner.MAX_CAVE_CODE) throw CodePatchException("The cave code is longer than ${InjectionPlanner.MAX_CAVE_CODE} bytes.")
            }
            found[RETURN] = returnAddress
            if (found == labels) {
                val out = ByteArrayOutputStream()
                parts.forEach { out.write(it.bytes) }
                val kept = stmts.filterIsInstance<Stmt.Original>().map { it.ins.address }.toSet()
                return Linked(out.toByteArray(), parts, found, kept, returnsIn(stmts), flagFields)
            }
            labels = found
        }
        throw CodePatchException("The label addresses did not settle after $MAX_PASSES passes. Simplify the branches between labels.")
    }
}
