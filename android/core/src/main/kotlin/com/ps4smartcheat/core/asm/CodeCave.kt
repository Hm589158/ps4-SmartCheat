package com.ps4smartcheat.core.asm

import com.ps4smartcheat.core.backend.DebugBackend
import com.ps4smartcheat.core.cheat.ModuleRef
import com.ps4smartcheat.core.cheat.ModuleResolver
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.util.Address

/** Where injected code lives. */
enum class CaveKind {
    /** Unused padding inside the game executable: same address every run, so it can be saved and exported. */
    MODULE_PADDING,
    /** Memory allocated with ps4debug-NG for this session only (address changes every run; never saved). */
    ALLOCATED,
}

/** A run of padding bytes in executable code. */
data class CaveCandidate(
    val address: Long,
    val size: Int,
    /** The padding byte (0x00 or 0xCC). */
    val fill: Byte,
    /** Runs up to the end of the code mapping (page padding after the last function): the safest kind. */
    val atSegmentEnd: Boolean,
    val module: ModuleRef?,
    /** |address − hook|, for ranking. */
    val distance: Long,
) {
    val end: Long get() = address + size
    /** Reachable with a 5-byte `jmp rel32` from the hook (and back). */
    val nearHook: Boolean get() = distance < 0x7FFF0000L

    val confidence: String get() = when {
        atSegmentEnd -> "end of code (page padding)"
        fill == 0xCC.toByte() -> "int3 padding between functions"
        else -> "zero bytes inside code — check it is unused"
    }
}

/**
 * Finds code caves: runs of padding (`int3` = 0xCC between functions, zeros after the end of the code) in the
 * executable mappings of the module that contains the hook. Read-only and bounded: at most [MAX_SCAN_BYTES] are read,
 * the end of each code mapping first, then the code around the hook.
 *
 * Padding is recognised by bytes only, so the first bytes of a run may belong to the instruction before it (e.g. the
 * zero high bytes of `mov eax, 0x100`). A cave therefore starts [LEAD_MARGIN] bytes into the run, 16-byte aligned, and
 * ends one byte before the next code.
 */
object CaveFinder {
    const val MIN_SIZE = 32
    const val MAX_SCAN_BYTES = 2 * 1024 * 1024
    const val TAIL_SCAN = 0x10000
    const val NEAR_SCAN = 0x40000
    const val CHUNK = 0x10000
    const val LEAD_MARGIN = 8
    /** Zero runs inside code are only offered when this long (short ones are often data or immediates). */
    const val MIN_INNER_ZERO_RUN = 128

    /** Runs of padding in [bytes] (located at [start]); [mappingEnd] marks the end of the code mapping. */
    fun scan(bytes: ByteArray, start: Long, mappingEnd: Long, minSize: Int = MIN_SIZE): List<Pair<Long, Int>> {
        val out = ArrayList<Pair<Long, Int>>()
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            if (b != 0.toByte() && b != 0xCC.toByte()) { i++; continue }
            var j = i
            while (j < bytes.size && bytes[j] == b) j++
            val runStart = start + i
            val runEnd = start + j
            val toEnd = runEnd == mappingEnd
            val usableStart = (runStart + LEAD_MARGIN + 15) and 15L.inv()
            val usableEnd = if (toEnd) runEnd else runEnd - 1
            val size = usableEnd - usableStart
            val longEnough = when {
                toEnd -> true
                b == 0xCC.toByte() -> true
                else -> j - i >= MIN_INNER_ZERO_RUN
            }
            if (longEnough && size >= minSize) out += usableStart to size.toInt()
            i = j
        }
        return out
    }

    /**
     * Candidates for a cave of at least [minSize] bytes for code hooked at [hook], best first: page padding at the end
     * of the code, then int3 padding, then long zero runs; nearer first. Ranges in [reserved] (caves of other saved or
     * active injections) are left out.
     */
    suspend fun find(
        backend: DebugBackend, pid: Int, regions: List<MemoryRegion>, hook: Long, minSize: Int = MIN_SIZE,
        reserved: List<LongRange> = emptyList(), maxResults: Int = 20,
    ): List<CaveCandidate> {
        val hookRegion = regions.firstOrNull { it.contains(hook) } ?: throw CodePatchException("${Address.format(hook)} is not mapped.")
        val module = hookRegion.name
        val code = regions.filter { it.isValid && it.isExecutable && it.isReadable && it.name == module && it.name.isNotBlank() }
            .ifEmpty { listOf(hookRegion) }
        // Windows to read: the tail of every code mapping, then around the hook. Merged and capped.
        val windows = ArrayList<LongRange>()
        for (r in code) windows += maxOf(r.start, r.end - TAIL_SCAN) until r.end
        windows += maxOf(hookRegion.start, hook - NEAR_SCAN) until minOf(hookRegion.end, hook + NEAR_SCAN)
        var budget = MAX_SCAN_BYTES.toLong()
        val seen = HashSet<Long>()
        val found = ArrayList<CaveCandidate>()
        for (w in merge(windows)) {
            if (budget <= 0) break
            val len = minOf(w.last - w.first + 1, budget)
            budget -= len
            val bytes = ByteArray(len.toInt())
            var off = 0L
            while (off < len) {
                val n = minOf(CHUNK.toLong(), len - off).toInt()
                backend.readMemory(pid, w.first + off, n).copyInto(bytes, off.toInt())
                off += n
            }
            val mapping = code.firstOrNull { it.contains(w.first) } ?: hookRegion
            for ((a, size) in scan(bytes, w.first, mapping.end, minSize)) {
                if (!seen.add(a)) continue
                val range = a until a + size
                if (reserved.any { it.first < range.last + 1 && range.first < it.last + 1 }) continue
                // The hook itself must never be inside a cave.
                if (hook in range || hook + 14 in range) continue
                val fill = bytes[(a - w.first).toInt()]
                found += CaveCandidate(a, size, fill, a + size == mapping.end, ModuleResolver.refFor(a, regions), kotlin.math.abs(a - hook))
            }
        }
        return found.sortedWith(
            compareBy<CaveCandidate>({ !it.nearHook }, { if (it.atSegmentEnd) 0 else if (it.fill == 0xCC.toByte()) 1 else 2 }, { it.distance }),
        ).take(maxResults)
    }

    private fun merge(ranges: List<LongRange>): List<LongRange> {
        val out = ArrayList<LongRange>()
        // Keep the original priority order (tails first) but drop overlaps with earlier windows.
        for (r in ranges) {
            var cur: LongRange? = r
            for (o in out) {
                val c = cur ?: break
                if (c.first >= o.first && c.last <= o.last) cur = null
                else if (c.first < o.first && c.last >= o.first) cur = c.first until o.first
                else if (c.first <= o.last && c.last > o.last) cur = (o.last + 1)..c.last
            }
            cur?.takeIf { !it.isEmpty() }?.let { out += it }
        }
        return out
    }
}
