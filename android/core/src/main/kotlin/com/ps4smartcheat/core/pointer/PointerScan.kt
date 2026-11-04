package com.ps4smartcheat.core.pointer

import com.ps4smartcheat.core.backend.DebugBackend
import com.ps4smartcheat.core.cheat.HexAddressSerializer
import com.ps4smartcheat.core.cheat.ModuleRef
import com.ps4smartcheat.core.cheat.ModuleResolver
import com.ps4smartcheat.core.model.MemoryRegion
import com.ps4smartcheat.core.model.regionFor
import com.ps4smartcheat.core.scan.ProgressListener
import com.ps4smartcheat.core.scan.ScanProgress
import com.ps4smartcheat.core.scan.ScanTooLargeException
import com.ps4smartcheat.core.util.Address
import com.ps4smartcheat.core.util.LE
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlin.coroutines.coroutineContext

/**
 * A pointer path from a static address inside a module image to a value:
 *
 *     p = base address (module base + [base].offset)
 *     for each offset o in [offsets]: p = read64(p) + o
 *     result = p
 *
 * so `offsets.size` pointers are dereferenced. Written like Cheat Engine as `[[eboot.bin+0x1000]+0x10]+0x8`.
 */
@Serializable
data class PointerChain(
    val base: ModuleRef,
    val offsets: List<@Serializable(with = HexAddressSerializer::class) Long>,
) {
    init { require(offsets.isNotEmpty()) { "a pointer chain needs at least one offset" } }

    val depth: Int get() = offsets.size

    fun describe(): String {
        var s = "${base.name}+0x${java.lang.Long.toHexString(base.offset).uppercase()}"
        for (o in offsets) s = "[$s]+0x${java.lang.Long.toHexString(o).uppercase()}"
        return s
    }
}

/** Outcome of following a chain in the current process. */
sealed interface PointerResolution {
    data class Resolved(val address: Long) : PointerResolution
    /** The module of the base is not loaded, or a pointer on the way leads to unmapped memory. */
    data class Broken(val reason: String) : PointerResolution
}

object PointerResolver {
    /**
     * Follows [chain] in [pid]. Only mapped, readable memory is read; a pointer into unmapped memory stops the walk
     * (ps4debug fails reads of unmapped memory, which would close the connection).
     */
    suspend fun resolve(backend: DebugBackend, pid: Int, chain: PointerChain, regions: List<MemoryRegion>, valueSize: Int = 1): PointerResolution {
        var p = ModuleResolver.resolve(chain.base, regions) ?: return PointerResolution.Broken("${chain.base.name} is not loaded")
        for ((i, o) in chain.offsets.withIndex()) {
            if (regions.regionFor(p, 8)?.isReadable != true) return PointerResolution.Broken("level ${i + 1}: ${Address.format(p)} is not mapped")
            p = LE.i64(backend.readMemory(pid, p, 8)) + o
        }
        if (regions.regionFor(p, valueSize)?.isReadable != true) return PointerResolution.Broken("result ${Address.format(p)} is not mapped")
        return PointerResolution.Resolved(p)
    }
}

/** "Who points into [low, high]" over all pointers found while building the map. */
interface PointerIndex : java.io.Closeable {
    val pointerCount: Long
    /** True when [PointerScanOptions.maxPointers] was reached and later pointers were dropped. */
    val truncated: Boolean
    val bytesScanned: Long
    fun forEachPointingInto(low: Long, high: Long, block: (location: Long, value: Long) -> Unit)
    override fun close() {}
}

/**
 * Every aligned 64-bit value found in [regions] that points into readable mapped memory, sorted by value so
 * "who points near X" is a binary search. Two parallel arrays keep this at 16 bytes per pointer.
 */
class PointerMap internal constructor(
    /** Location of each pointer, ordered by [values]. */
    private val locations: LongArray,
    private val values: LongArray,
    val count: Int,
    override val truncated: Boolean,
    override val bytesScanned: Long,
) : PointerIndex {
    override val pointerCount: Long get() = count.toLong()

    /** Calls [block] for every pointer whose value lies in `[low, high]` (unsigned). */
    override fun forEachPointingInto(low: Long, high: Long, block: (location: Long, value: Long) -> Unit) {
        var i = lowerBound(low)
        while (i < count && java.lang.Long.compareUnsigned(valueAt(i), high) <= 0) {
            block(locationAt(i), valueAt(i))
            i++
        }
    }

    fun locationAt(i: Int): Long = locations[i]
    fun valueAt(i: Int): Long = values[i]

    fun lowerBound(v: Long): Int {
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (java.lang.Long.compareUnsigned(values[mid], v) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /** Builds a map from parallel arrays (first [count] entries used); sorts them in place by value. */
        fun build(locations: LongArray, values: LongArray, count: Int, truncated: Boolean = false, bytesScanned: Long = 0): PointerMap {
            ParallelSort.sortByValue(values, locations, count)
            return PointerMap(locations, values, count, truncated, bytesScanned)
        }

        fun build(entries: List<Pair<Long, Long>>): PointerMap =
            build(LongArray(entries.size) { entries[it].first }, LongArray(entries.size) { entries[it].second }, entries.size)
    }
}

/** In-place sort of two parallel arrays by the first one (unsigned), without boxing. */
internal object ParallelSort {
    fun sortByValue(keys: LongArray, other: LongArray, count: Int) = sort(keys, other, 0, count - 1)

    private fun less(a: Long, b: Long) = java.lang.Long.compareUnsigned(a, b) < 0

    private fun swap(k: LongArray, o: LongArray, i: Int, j: Int) {
        val t = k[i]; k[i] = k[j]; k[j] = t
        val u = o[i]; o[i] = o[j]; o[j] = u
    }

    private fun sort(k: LongArray, o: LongArray, lo0: Int, hi0: Int) {
        var lo = lo0
        var hi = hi0
        while (hi - lo >= 16) {
            // Median of three as pivot, Hoare partition.
            val mid = (lo + hi) ushr 1
            if (less(k[mid], k[lo])) swap(k, o, mid, lo)
            if (less(k[hi], k[lo])) swap(k, o, hi, lo)
            if (less(k[hi], k[mid])) swap(k, o, hi, mid)
            val pivot = k[mid]
            var i = lo
            var j = hi
            while (i <= j) {
                while (less(k[i], pivot)) i++
                while (less(pivot, k[j])) j--
                if (i <= j) { swap(k, o, i, j); i++; j-- }
            }
            // Recurse into the smaller part and loop on the larger one: stack depth stays O(log n).
            if (j - lo < hi - i) {
                if (lo < j) sort(k, o, lo, j)
                lo = i
            } else {
                if (i < hi) sort(k, o, i, hi)
                hi = j
            }
        }
        for (i in lo + 1..hi) {
            var j = i
            while (j > lo && less(k[j], k[j - 1])) { swap(k, o, j, j - 1); j-- }
        }
    }
}

/**
 * Pointer map kept in sorted runs on disk (memory-mapped, outside the Java heap), so big games do not need the whole
 * map in phone RAM. Each run file holds `[value, location]` little-endian pairs sorted by value.
 */
class SpilledPointerIndex internal constructor(
    private val files: List<java.io.File>,
    private val counts: List<Int>,
    override val truncated: Boolean,
    override val bytesScanned: Long,
) : PointerIndex {
    private val runs: List<java.nio.LongBuffer> = files.map { f ->
        java.io.RandomAccessFile(f, "r").use { raf ->
            raf.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, raf.length())
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).asLongBuffer()
        }
    }
    override val pointerCount: Long = counts.sumOf { it.toLong() }
    val runCount: Int get() = files.size

    override fun forEachPointingInto(low: Long, high: Long, block: (location: Long, value: Long) -> Unit) {
        for ((r, buf) in runs.withIndex()) {
            val n = counts[r]
            var lo = 0
            var hi = n
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (java.lang.Long.compareUnsigned(buf.get(2 * mid), low) < 0) lo = mid + 1 else hi = mid
            }
            var i = lo
            while (i < n) {
                val v = buf.get(2 * i)
                if (java.lang.Long.compareUnsigned(v, high) > 0) break
                block(buf.get(2 * i + 1), v)
                i++
            }
        }
    }

    override fun close() { files.forEach { it.delete() } }

    internal companion object {
        /** Sorts the first [n] pairs by value and writes them as one run file. */
        fun writeRun(dir: java.io.File, index: Int, locs: LongArray, vals: LongArray, n: Int): java.io.File {
            ParallelSort.sortByValue(vals, locs, n)
            val f = java.io.File.createTempFile("pointers-$index-", ".run", dir)
            java.io.FileOutputStream(f).channel.use { ch ->
                val bb = java.nio.ByteBuffer.allocate(1 shl 16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) {
                    if (bb.remaining() < 16) { bb.flip(); while (bb.hasRemaining()) ch.write(bb); bb.clear() }
                    bb.putLong(vals[i]).putLong(locs[i])
                }
                bb.flip(); while (bb.hasRemaining()) ch.write(bb)
            }
            return f
        }
    }
}

data class PointerScanOptions(
    /** Maximum number of dereferences in a chain. */
    val maxDepth: Int = 4,
    /** Largest offset added after a dereference (structure field offset). */
    val maxOffset: Int = 0x1000,
    /** Pointer chains returned at most. */
    val maxResults: Int = 2_000,
    /** Pointers kept in the map (16 bytes each, in RAM or in run files when spilling). */
    val maxPointers: Int = 4_000_000,
    /** When spilling to disk: pointers per sorted run (RAM used while building ≈ 16 bytes × this). */
    val runSize: Int = 2_000_000,
    /** Bytes of memory read to build the map. */
    val maxScanBytes: Long = 512L * 1024 * 1024,
    /** Addresses explored per level, bounding the search's fan-out. */
    val maxNodesPerLevel: Int = 200_000,
    val chunkSize: Int = 1 shl 20,
) {
    init {
        require(maxDepth in 1..8) { "maxDepth must be 1..8" }
        require(maxOffset in 0..0x100000 && maxOffset % 4 == 0) { "maxOffset must be 0..0x100000 and a multiple of 4" }
        require(maxResults > 0 && maxPointers > 0 && maxNodesPerLevel > 0 && runSize > 0) { "limits must be positive" }
    }
}

data class PointerScanResult(
    val target: Long,
    val chains: List<PointerChain>,
    val pointerCount: Long,
    val mapTruncated: Boolean,
    val resultsTruncated: Boolean,
    val bytesScanned: Long,
)

object PointerScanner {
    /** Regions searched for pointers: readable, not executable (pointers live in data, heap and stacks). */
    fun mapRegions(regions: List<MemoryRegion>): List<MemoryRegion> =
        regions.filter { it.isValid && it.isReadable && !it.isExecutable }

    /** Static bases: data regions belonging to a module image (eboot.bin, *.sprx, …). */
    fun isStatic(region: MemoryRegion): Boolean = ModuleResolver.isModuleName(region.name) && !region.isExecutable

    /** Bytes [buildMap] reads for this memory map (non-executable readable regions, optionally size-capped). */
    fun estimateBytes(regions: List<MemoryRegion>, maxRegionBytes: Long = 0): Long =
        mapRegions(regions).filter { maxRegionBytes <= 0 || it.size <= maxRegionBytes }.sumOf { it.size }

    /**
     * Reads [regions] and collects aligned qwords that point into any readable region of [allRegions].
     * With [spillDir], pointers are written to sorted run files of [PointerScanOptions.runSize] so RAM stays bounded;
     * the caller must [PointerIndex.close] the result to delete them.
     */
    suspend fun buildMap(
        backend: DebugBackend,
        pid: Int,
        regions: List<MemoryRegion>,
        allRegions: List<MemoryRegion>,
        options: PointerScanOptions = PointerScanOptions(),
        spillDir: java.io.File? = null,
        progress: ProgressListener = {},
    ): PointerIndex {
        val total = regions.sumOf { it.size }
        if (total > options.maxScanBytes) {
            throw ScanTooLargeException(
                "Pointer map needs ${total / (1024 * 1024)} MiB, limit is ${options.maxScanBytes / (1024 * 1024)} MiB. " +
                    "Lower \"Max region size\" in Settings to skip huge regions.",
            )
        }
        val targets = allRegions.filter { it.isValid && it.isReadable }.sortedWith { a, b -> java.lang.Long.compareUnsigned(a.start, b.start) }
        val tStarts = LongArray(targets.size) { targets[it].start }
        val tEnds = LongArray(targets.size) { targets[it].end }
        fun mapped(v: Long): Boolean {
            // Last region starting at or below v.
            var lo = 0
            var hi = targets.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (java.lang.Long.compareUnsigned(tStarts[mid], v) <= 0) lo = mid + 1 else hi = mid
            }
            val i = lo - 1
            return i >= 0 && java.lang.Long.compareUnsigned(v, tEnds[i]) < 0
        }
        val bufferCap = if (spillDir != null) minOf(options.runSize, options.maxPointers) else options.maxPointers
        var locs = LongArray(minOf(1024, bufferCap))
        var vals = LongArray(minOf(1024, bufferCap))
        var n = 0
        var total2 = 0L
        val runFiles = ArrayList<java.io.File>()
        val runCounts = ArrayList<Int>()
        var truncated = false
        var done = 0L
        progress(ScanProgress(0, total, 0, "Pointer map"))
        try {
        outer@ for (r in regions) {
            var pos = (r.start + 7) and 7L.inv()
            while (pos + 8 <= r.end) {
                coroutineContext.ensureActive()
                val len = (minOf(options.chunkSize.toLong(), r.end - pos) and 7L.inv()).toInt()
                if (len == 0) break
                val buf = backend.readMemory(pid, pos, len)
                var off = 0
                while (off + 8 <= len) {
                    val v = LE.i64(buf, off)
                    if (v != 0L && (v and 3L) == 0L && mapped(v)) {
                        if (total2 >= options.maxPointers) { truncated = true; break@outer }
                        if (n == bufferCap && spillDir != null) {
                            runFiles += SpilledPointerIndex.writeRun(spillDir, runFiles.size, locs, vals, n)
                            runCounts += n
                            n = 0
                        }
                        if (n == locs.size) {
                            val grow = minOf(locs.size.toLong() * 2, bufferCap.toLong()).toInt().coerceAtLeast(n + 1)
                            locs = locs.copyOf(grow); vals = vals.copyOf(grow)
                        }
                        locs[n] = pos + off
                        vals[n] = v
                        n++
                        total2++
                    }
                    off += 8
                }
                pos += len
                done += len
                progress(ScanProgress(done, total, total2, "Pointer map"))
            }
        }
        } catch (t: Throwable) {
            runFiles.forEach { it.delete() } // cancelled or failed mid-scan: no stray run files
            throw t
        }
        if (runFiles.isEmpty()) return PointerMap.build(locs, vals, n, truncated, done)
        try {
            if (n > 0) { runFiles += SpilledPointerIndex.writeRun(spillDir!!, runFiles.size, locs, vals, n); runCounts += n }
            return SpilledPointerIndex(runFiles, runCounts, truncated, done)
        } catch (t: Throwable) {
            runFiles.forEach { it.delete() }
            throw t
        }
    }

    /**
     * Searches backwards from [target]: level 1 finds pointers whose value lies within maxOffset below target,
     * level 2 pointers to those pointers, and so on. A pointer stored in a static module region ends a chain.
     */
    suspend fun findChains(
        map: PointerIndex,
        target: Long,
        regions: List<MemoryRegion>,
        options: PointerScanOptions = PointerScanOptions(),
        progress: ProgressListener = {},
    ): PointerScanResult {
        val sorted = regions.filter { it.isValid }.sortedWith { a, b -> java.lang.Long.compareUnsigned(a.start, b.start) }
        val starts = LongArray(sorted.size) { sorted[it].start }
        fun regionOf(a: Long): MemoryRegion? {
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (java.lang.Long.compareUnsigned(starts[mid], a) <= 0) lo = mid + 1 else hi = mid
            }
            return sorted.getOrNull(lo - 1)?.takeIf { it.contains(a, 8) }
        }
        val chains = ArrayList<PointerChain>()
        // A node is an address we want something to point at, plus the offsets from it to the target.
        class Node(val address: Long, val tail: List<Long>)
        var level = listOf(Node(target, emptyList()))
        val visited = HashSet<Long>()
        var resultsTruncated = false
        for (depth in 1..options.maxDepth) {
            coroutineContext.ensureActive()
            val next = ArrayList<Node>()
            for (node in level) {
                val low = node.address - options.maxOffset
                map.forEachPointingInto(if (java.lang.Long.compareUnsigned(low, node.address) > 0) 0 else low, node.address) { loc, value ->
                    val tail = listOf(node.address - value) + node.tail
                    val r = regionOf(loc)
                    if (r != null && isStatic(r)) {
                        val ref = ModuleResolver.refFor(loc, regions)
                        if (ref != null) {
                            if (chains.size < options.maxResults) chains += PointerChain(ref, tail) else resultsTruncated = true
                        }
                    }
                    if (depth < options.maxDepth && next.size < options.maxNodesPerLevel && visited.add(loc)) next += Node(loc, tail)
                }
                if (resultsTruncated) break
            }
            progress(ScanProgress(depth.toLong(), options.maxDepth.toLong(), chains.size.toLong(), "Searching chains"))
            if (resultsTruncated || next.isEmpty()) break
            level = next
        }
        // Shortest, then smallest offsets first: those are the most likely to be stable.
        val ordered = chains.distinct().sortedWith(compareBy<PointerChain>({ it.depth }, { it.offsets.sumOf { o -> o } }))
        return PointerScanResult(target, ordered, map.pointerCount, map.truncated, resultsTruncated, map.bytesScanned)
    }

    /**
     * Keeps the chains that lead to [expected] in the current process — run after the game was restarted and the
     * value was found again with a normal scan. Chains that break are dropped.
     */
    suspend fun filter(
        backend: DebugBackend,
        pid: Int,
        chains: List<PointerChain>,
        regions: List<MemoryRegion>,
        expected: Long,
        progress: ProgressListener = {},
    ): List<PointerChain> {
        val out = ArrayList<PointerChain>()
        for ((i, c) in chains.withIndex()) {
            coroutineContext.ensureActive()
            val r = PointerResolver.resolve(backend, pid, c, regions)
            if (r is PointerResolution.Resolved && r.address == expected) out += c
            progress(ScanProgress(i + 1L, chains.size.toLong(), out.size.toLong(), "Checking chains"))
        }
        return out
    }
}
